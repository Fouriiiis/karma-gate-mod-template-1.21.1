package dev.fouriis.karmagate.client.gridproject;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.List;

public final class GridProjectRenderer {
    private GridProjectRenderer() {}

    // Reduced quad limit - shell slabs approach should need far fewer
    private static final int MAX_QUADS = 500_000;
    private static final float SURFACE_NUDGE = 0.00125f;

    // Flag for test zone initialization
    private static boolean testZonesInitialized = false;
    
    // Reusable mutable block positions (avoid allocations)
    private static final BlockPos.Mutable POS = new BlockPos.Mutable();
    private static final BlockPos.Mutable NEIGHBOR_POS = new BlockPos.Mutable();

    private static final Identifier GLYPHS_TEXTURE = Identifier.of("karma-gate-mod", "textures/projector/glyphs.png");

    public static void renderLate(float tickDelta, Camera camera) {
        if (GridProjectShaders.PROGRAM == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        if (world == null || camera == null) return;

        // Initialize test zones on first render
        if (!testZonesInitialized) {
            ProjectionZone.initTestZones();
            testZonesInitialized = true;
        }

        List<ProjectionZone> zones = ProjectionZone.getZones();
        if (zones.isEmpty()) return;

        if (mc.getWindow() == null
            || mc.getWindow().getFramebufferWidth() <= 0
            || mc.getWindow().getFramebufferHeight() <= 0) return;

        Vec3d camPos = camera.getPos();

        ShaderProgram program = GridProjectShaders.PROGRAM;

        // Set shader uniforms
        setUniform1f(program, "uTime", (float) (world.getTime() + tickDelta));

        // Build view matrix (standard late render setup)
        Matrix4f view = new Matrix4f()
            .rotation(camera.getRotation())
            .transpose()
            .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // Pass both view matrix and its inverse to shader for world-space reconstruction
        setUniformMat4(program, "uViewMat", view);
        Matrix4f invView = new Matrix4f(view).invert();
        setUniformMat4(program, "uInvViewMat", invView);

        // Build our own projection matrix to avoid shader mod interference
        double dynFovDeg = ((GameRendererAccessor) mc.gameRenderer)
            .karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float near = 0.05f;
        float far = (float) (mc.options.getClampedViewDistance() * 16.0 * 4.0);
        Matrix4f customProj = new Matrix4f().setPerspective(fovRad, aspect, near, far);

        // Save current state
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        
        // Setup render state - use Tessellator directly for full control
        RenderSystem.setShader(() -> program);
        RenderSystem.setShaderTexture(0, GLYPHS_TEXTURE);
        RenderSystem.setProjectionMatrix(customProj, VertexSorter.BY_DISTANCE);
        
        // Set ModelViewMat to identity - we pass view matrix as custom uniform
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();

        Tessellator tessellator = Tessellator.getInstance();
        
        int quadsEmitted = 0;

        try {
            // Iterate over each zone
            for (ProjectionZone zone : zones) {
                final float zoneCenterX = zone.getCenterXf();
                final float zoneCenterZ = zone.getCenterZf();
                final float zoneRadius = zone.getRadiusf();

                // Per-zone projection parameters (used by the shader)
                setUniform1f(program, "uZoneCenterX", zoneCenterX);
                setUniform1f(program, "uZoneCenterZ", zoneCenterZ);
                setUniform1f(program, "uZoneRadius", zoneRadius);

                final int a = 255;
                final int c = 255;
                final int fullBright = LightmapTextureManager.pack(15, 15);
                
                int[][] shellSlabs = zone.getShellSlabs();
                
                for (int[] slab : shellSlabs) {
                    int slabMinX = slab[0], slabMaxX = slab[1];
                    int slabMinY = slab[2], slabMaxY = slab[3];
                    int slabMinZ = slab[4], slabMaxZ = slab[5];
                    
                    // Start a new buffer for this slab
                    BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
                    int slabQuads = 0;
                    
                    for (int x = slabMinX; x <= slabMaxX; x++) {
                        for (int z = slabMinZ; z <= slabMaxZ; z++) {
                            for (int y = slabMinY; y <= slabMaxY; y++) {
                                POS.set(x, y, z);

                                BlockState state = world.getBlockState(POS);
                                if (state.isAir()) continue;
                                if (!state.isOpaqueFullCube(world, POS)) continue;

                                final float fcx = x + 0.5f;
                                final float fcz = z + 0.5f;
                                final float toCenterX = zoneCenterX - fcx;
                                final float toCenterZ = zoneCenterZ - fcz;
                                
                                boolean showUp    = shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.UP);
                                boolean showDown  = shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.DOWN);
                                boolean showNorth = toCenterZ < 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.NORTH);
                                boolean showSouth = toCenterZ > 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.SOUTH);
                                boolean showWest  = toCenterX < 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.WEST);
                                boolean showEast  = toCenterX > 0 && shouldRenderFace(world, POS, NEIGHBOR_POS, Direction.EAST);

                                if (!(showUp || showDown || showNorth || showSouth || showWest || showEast)) continue;
                                
                                float x0 = x;
                                float y0 = y;
                                float z0 = z;
                                float x1 = x + 1.0f;
                                float y1 = y + 1.0f;
                                float z1 = z + 1.0f;

                                // Emit quads in WORLD SPACE - shader will transform
                                if (showUp) {
                                    emitQuadWorld(buffer,
                                        x0, y1 + SURFACE_NUDGE, z0,
                                        x1, y1 + SURFACE_NUDGE, z0,
                                        x1, y1 + SURFACE_NUDGE, z1,
                                        x0, y1 + SURFACE_NUDGE, z1,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }

                                if (showDown) {
                                    emitQuadWorld(buffer,
                                        x0, y0 - SURFACE_NUDGE, z1,
                                        x1, y0 - SURFACE_NUDGE, z1,
                                        x1, y0 - SURFACE_NUDGE, z0,
                                        x0, y0 - SURFACE_NUDGE, z0,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }

                                if (showNorth) {
                                    emitQuadWorld(buffer,
                                        x1, y0, z0 - SURFACE_NUDGE,
                                        x0, y0, z0 - SURFACE_NUDGE,
                                        x0, y1, z0 - SURFACE_NUDGE,
                                        x1, y1, z0 - SURFACE_NUDGE,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }

                                if (showSouth) {
                                    emitQuadWorld(buffer,
                                        x0, y0, z1 + SURFACE_NUDGE,
                                        x1, y0, z1 + SURFACE_NUDGE,
                                        x1, y1, z1 + SURFACE_NUDGE,
                                        x0, y1, z1 + SURFACE_NUDGE,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }

                                if (showWest) {
                                    emitQuadWorld(buffer,
                                        x0 - SURFACE_NUDGE, y0, z0,
                                        x0 - SURFACE_NUDGE, y0, z1,
                                        x0 - SURFACE_NUDGE, y1, z1,
                                        x0 - SURFACE_NUDGE, y1, z0,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }

                                if (showEast) {
                                    emitQuadWorld(buffer,
                                        x1 + SURFACE_NUDGE, y0, z1,
                                        x1 + SURFACE_NUDGE, y0, z0,
                                        x1 + SURFACE_NUDGE, y1, z0,
                                        x1 + SURFACE_NUDGE, y1, z1,
                                        c, c, c, a, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    slabQuads++;
                                }
                            }
                        }
                    }
                    
                    // Draw this slab's buffer
                    if (slabQuads > 0) {
                        BufferRenderer.drawWithGlobalProgram(buffer.end());
                    }
                    
                    quadsEmitted += slabQuads;
                    if (quadsEmitted >= MAX_QUADS) {
                        return;
                    }
                }
            }
        } finally {
            // Restore state
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
        }
    }

    private static boolean shouldRenderFace(World world, BlockPos.Mutable pos, BlockPos.Mutable neighborPos, Direction dir) {
        neighborPos.set(pos).move(dir);
        BlockState neighbor = world.getBlockState(neighborPos);
        if (neighbor.isAir()) return true;
        return !neighbor.isOpaqueFullCube(world, neighborPos);
    }

    private static void setUniform1f(ShaderProgram program, String name, float x) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(x);
    }

    private static void setUniformMat4(ShaderProgram program, String name, Matrix4f mat) {
        if (program == null || mat == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(mat);
    }

    /**
     * Emits a quad in WORLD SPACE with UV coordinates for the shader.
     * The shader will handle view transformation using uViewMat uniform.
     */
    private static void emitQuadWorld(
        BufferBuilder buffer,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        int r, int g, int b, int a,
        int light,
        float zoneCenterX, float zoneCenterZ,
        float zoneRadius
    ) {
        float u0 = computeSquarePerimeterU(x0, z0, zoneCenterX, zoneCenterZ, zoneRadius);
        float u1 = computeSquarePerimeterU(x1, z1, zoneCenterX, zoneCenterZ, zoneRadius);
        float u2 = computeSquarePerimeterU(x2, z2, zoneCenterX, zoneCenterZ, zoneRadius);
        float u3 = computeSquarePerimeterU(x3, z3, zoneCenterX, zoneCenterZ, zoneRadius);

        float period = 8.0f * Math.max(zoneRadius, 1e-6f);
        float[] us = {u0, u1, u2, u3};
        fixUvWrappingPerimeter(us, period);
        u0 = us[0]; u1 = us[1]; u2 = us[2]; u3 = us[3];

        // Emit vertices in world space - shader will transform to view/clip space
        buffer.vertex(x0, y0, z0)
            .color(r, g, b, a)
            .texture(u0, y0)
            .light(light);

        buffer.vertex(x1, y1, z1)
            .color(r, g, b, a)
            .texture(u1, y1)
            .light(light);

        buffer.vertex(x2, y2, z2)
            .color(r, g, b, a)
            .texture(u2, y2)
            .light(light);

        buffer.vertex(x3, y3, z3)
            .color(r, g, b, a)
            .texture(u3, y3)
            .light(light);
    }

    /**
     * Computes square-cylinder perimeter U coordinate:
     * - Shoot a ray from the zone center through the vertex (XZ plane)
     * - Intersect it with the square boundary of radius R (max(|x|,|z|)=R)
     * - Convert boundary point to continuous perimeter distance [0..8R)
     */
    private static float computeSquarePerimeterU(float worldX, float worldZ,
                                                 float centerX, float centerZ,
                                                 float radius) {
        float R = Math.max(radius, 1e-6f);

        float rx = worldX - centerX;
        float rz = worldZ - centerZ;

        float len = (float) Math.sqrt(rx * rx + rz * rz);
        // Stable fallback direction if we are exactly at center
        float dx = (len > 1e-9f) ? (rx / len) : 1.0f;
        float dz = (len > 1e-9f) ? (rz / len) : 0.0f;

        // Ray-square boundary intersection (scale so max(|x|,|z|)=R)
        float m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m < 1e-9f) m = 1.0f;

        float hx = dx * (R / m);
        float hz = dz * (R / m);

        // Convert boundary point to perimeter distance [0..8R)
        // Origin at (x=+R, z=-R), increasing CCW: East -> North -> West -> South
        float ax = Math.abs(hx);
        float az = Math.abs(hz);

        float u;
        if (ax >= az) {
            if (hx >= 0.0f) {
                // East side: x=+R, z:-R..R
                u = hz + R;                 // [0..2R)
            } else {
                // West side: x=-R, z:R..-R
                u = 4.0f * R + (R - hz);    // [4R..6R)
            }
        } else {
            if (hz >= 0.0f) {
                // North side: z=+R, x:R..-R
                u = 2.0f * R + (R - hx);    // [2R..4R)
            } else {
                // South side: z=-R, x:-R..R
                u = 6.0f * R + (hx + R);    // [6R..8R)
            }
        }

        float perim = 8.0f * R;
        u = u % perim;
        if (u < 0.0f) u += perim;

        return u;
    }

    /**
     * Fix wrapping for a quad crossing the seam at U=0/perimeter.
     * If the quad spans more than half the perimeter, shift the low side up by +period.
     */
    private static void fixUvWrappingPerimeter(float[] us, float period) {
        if (period <= 0.0f) return;

        float uMin = us[0], uMax = us[0];
        for (int i = 1; i < 4; i++) {
            uMin = Math.min(uMin, us[i]);
            uMax = Math.max(uMax, us[i]);
        }

        if (uMax - uMin > period * 0.5f) {
            float mid = (uMin + uMax) * 0.5f;
            for (int i = 0; i < 4; i++) {
                if (us[i] < mid) us[i] += period;
            }
        }
    }
}