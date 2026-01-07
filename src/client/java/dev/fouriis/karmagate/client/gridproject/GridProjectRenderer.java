package dev.fouriis.karmagate.client.gridproject;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.mixin.client.GameRendererAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
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
        RenderSystem.setShader(() -> program);

        // Set shader uniforms
        setUniform1f(program, "uTime", (float) (world.getTime() + tickDelta));

        // Build view matrix (standard late render setup)
        Matrix4f view = new Matrix4f()
            .rotation(camera.getRotation())
            .transpose()
            .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // Provide inverse view matrix so the shader can recover true world-space positions
        Matrix4f invView = new Matrix4f(view).invert();
        setUniformMat4(program, "uInvViewMat", invView);

        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(matrices, tickDelta);
        }
        matrices.peek().getPositionMatrix().mul(view);

        Matrix4f m = matrices.peek().getPositionMatrix();

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = immediate.getBuffer(GridProjectRenderLayer.get());

        final int fullBright = LightmapTextureManager.pack(15, 15);
        final int overlay = OverlayTexture.DEFAULT_UV;

        int quadsEmitted = 0;

        RenderSystem.disableCull();
        try {
            // Iterate over each zone
            for (ProjectionZone zone : zones) {
                // Use pre-computed float values from zone (avoid double->float per iteration)
                final float zoneCenterX = zone.getCenterXf();
                final float zoneCenterZ = zone.getCenterZf();
                final float zoneRadius = zone.getRadiusf();

                // Per-zone projection parameters (used by the shader)
                setUniform1f(program, "uZoneCenterX", zoneCenterX);
                setUniform1f(program, "uZoneCenterZ", zoneCenterZ);
                setUniform1f(program, "uZoneRadius", zoneRadius);

                // Full brightness, no distance fade
                final int a = 255;
                final int c = 255;
                
                // Iterate only over pre-computed shell slabs (massive reduction in iterations)
                int[][] shellSlabs = zone.getShellSlabs();
                
                for (int[] slab : shellSlabs) {
                    int slabMinX = slab[0], slabMaxX = slab[1];
                    int slabMinY = slab[2], slabMaxY = slab[3];
                    int slabMinZ = slab[4], slabMaxZ = slab[5];
                    
                    // Iterate in XZ-first order for better cache locality
                    for (int x = slabMinX; x <= slabMaxX; x++) {
                        for (int z = slabMinZ; z <= slabMaxZ; z++) {
                            for (int y = slabMinY; y <= slabMaxY; y++) {
                                POS.set(x, y, z);

                                BlockState state = world.getBlockState(POS);
                                if (state.isAir()) continue;
                                
                                // Only render on full solid opaque blocks
                                if (!state.isOpaqueFullCube(world, POS)) continue;

                                // Pre-compute block center for backface culling
                                final float fcx = x + 0.5f;
                                final float fcz = z + 0.5f;
                                
                                // Vector from block center to zone center
                                final float toCenterX = zoneCenterX - fcx;
                                final float toCenterZ = zoneCenterZ - fcz;
                                
                                // Check which faces are exposed AND apply backface culling
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

                                // UP face
                                if (showUp) {
                                    emitQuadHorizontal(vc, m,
                                        x0, y1 + SURFACE_NUDGE, z0,
                                        x1, y1 + SURFACE_NUDGE, z0,
                                        x1, y1 + SURFACE_NUDGE, z1,
                                        x0, y1 + SURFACE_NUDGE, z1,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                // DOWN face
                                if (showDown) {
                                    emitQuadHorizontal(vc, m,
                                        x0, y0 - SURFACE_NUDGE, z1,
                                        x1, y0 - SURFACE_NUDGE, z1,
                                        x1, y0 - SURFACE_NUDGE, z0,
                                        x0, y0 - SURFACE_NUDGE, z0,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                // NORTH (-Z)
                                if (showNorth) {
                                    emitQuadWithWorldPos(vc, m,
                                        x1, y0, z0 - SURFACE_NUDGE,
                                        x0, y0, z0 - SURFACE_NUDGE,
                                        x0, y1, z0 - SURFACE_NUDGE,
                                        x1, y1, z0 - SURFACE_NUDGE,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                // SOUTH (+Z)
                                if (showSouth) {
                                    emitQuadWithWorldPos(vc, m,
                                        x0, y0, z1 + SURFACE_NUDGE,
                                        x1, y0, z1 + SURFACE_NUDGE,
                                        x1, y1, z1 + SURFACE_NUDGE,
                                        x0, y1, z1 + SURFACE_NUDGE,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                // WEST (-X)
                                if (showWest) {
                                    emitQuadWithWorldPos(vc, m,
                                        x0 - SURFACE_NUDGE, y0, z0,
                                        x0 - SURFACE_NUDGE, y0, z1,
                                        x0 - SURFACE_NUDGE, y1, z1,
                                        x0 - SURFACE_NUDGE, y1, z0,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                // EAST (+X)
                                if (showEast) {
                                    emitQuadWithWorldPos(vc, m,
                                        x1 + SURFACE_NUDGE, y0, z1,
                                        x1 + SURFACE_NUDGE, y0, z0,
                                        x1 + SURFACE_NUDGE, y1, z0,
                                        x1 + SURFACE_NUDGE, y1, z1,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius
                                    );
                                    quadsEmitted++;
                                }

                                if (quadsEmitted >= MAX_QUADS) {
                                    immediate.draw();
                                    return;
                                }
                            }
                        }
                    }
                }
            }

            immediate.draw();
        } finally {
            RenderSystem.enableCull();
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
     * Emits a quad with square-cylinder projection from zone center (for vertical faces).
     *
     * UV0.x = perimeter distance around square boundary (wraps around corners)
     * UV0.y = world Y height
     */
    private static void emitQuadWithWorldPos(
        VertexConsumer vc,
        Matrix4f m,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        int r, int g, int b, int a,
        int overlay, int light,
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

        vc.vertex(m, x0, y0, z0)
            .color(r, g, b, a)
            .texture(u0, y0)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x1, y1, z1)
            .color(r, g, b, a)
            .texture(u1, y1)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x2, y2, z2)
            .color(r, g, b, a)
            .texture(u2, y2)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x3, y3, z3)
            .color(r, g, b, a)
            .texture(u3, y3)
            .overlay(overlay)
            .light(light);
    }

    /**
     * Emits a quad for horizontal faces (UP/DOWN) with radial projection from zone center.
     * Like 4 projectors at the center facing N/S/E/W, projecting onto the floor/ceiling.
     *
     * UV.x = perimeter position around the center (same coordinate system as vertical walls)
     * UV.y = world Y height (square-cylinder projection)
     */
    private static void emitQuadHorizontal(
        VertexConsumer vc,
        Matrix4f m,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3,
        int r, int g, int b, int a,
        int overlay, int light,
        float zoneCenterX, float zoneCenterZ,
        float zoneRadius
    ) {
        // U = perimeter position (same as vertical faces for seamless wrapping)
        float u0 = computeSquarePerimeterU(x0, z0, zoneCenterX, zoneCenterZ, zoneRadius);
        float u1 = computeSquarePerimeterU(x1, z1, zoneCenterX, zoneCenterZ, zoneRadius);
        float u2 = computeSquarePerimeterU(x2, z2, zoneCenterX, zoneCenterZ, zoneRadius);
        float u3 = computeSquarePerimeterU(x3, z3, zoneCenterX, zoneCenterZ, zoneRadius);

        // Fix wrapping for quads that cross the seam
        float period = 8.0f * Math.max(zoneRadius, 1e-6f);
        float[] us = {u0, u1, u2, u3};
        fixUvWrappingPerimeter(us, period);
        u0 = us[0]; u1 = us[1]; u2 = us[2]; u3 = us[3];

        vc.vertex(m, x0, y0, z0)
            .color(r, g, b, a)
            .texture(u0, y0)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x1, y1, z1)
            .color(r, g, b, a)
            .texture(u1, y1)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x2, y2, z2)
            .color(r, g, b, a)
            .texture(u2, y2)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, x3, y3, z3)
            .color(r, g, b, a)
            .texture(u3, y3)
            .overlay(overlay)
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