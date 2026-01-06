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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.List;

public final class GridProjectRenderer {
    private GridProjectRenderer() {}

    private static final int MAX_QUADS = 60_000;
    private static final float SURFACE_NUDGE = 0.00125f;
    private static final float MIN_FADE_BRIGHTNESS = 32.0f;

    // Maximum render distance from camera
    private static final int RENDER_DISTANCE = 64;

    // Flag for test zone initialization
    private static boolean testZonesInitialized = false;

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
        BlockPos camBlock = BlockPos.ofFloored(camPos);

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

        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighborPos = new BlockPos.Mutable();

        RenderSystem.disableCull();
        try {
            // Iterate over each zone
            for (ProjectionZone zone : zones) {
                double zoneCenterX = zone.getCenterX();
                double zoneCenterZ = zone.getCenterZ();
                double zoneRadius = computeZoneRadius(zone);

                // Per-zone projection parameters (used by the shader for per-fragment ray projection)
                setUniform1f(program, "uZoneCenterX", (float) zoneCenterX);
                setUniform1f(program, "uZoneCenterZ", (float) zoneCenterZ);
                setUniform1f(program, "uZoneRadius",  (float) zoneRadius);

                BlockPos zoneMin = zone.getMin();
                BlockPos zoneMax = zone.getMax();

                // Clamp iteration to render distance from camera
                int minX = Math.max(zoneMin.getX(), camBlock.getX() - RENDER_DISTANCE);
                int maxX = Math.min(zoneMax.getX(), camBlock.getX() + RENDER_DISTANCE);
                int minY = Math.max(zoneMin.getY(), camBlock.getY() - RENDER_DISTANCE);
                int maxY = Math.min(zoneMax.getY(), camBlock.getY() + RENDER_DISTANCE);
                int minZ = Math.max(zoneMin.getZ(), camBlock.getZ() - RENDER_DISTANCE);
                int maxZ = Math.min(zoneMax.getZ(), camBlock.getZ() + RENDER_DISTANCE);

                if (minX > maxX || minY > maxY || minZ > maxZ) continue;

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            pos.set(x, y, z);

                            BlockState state = world.getBlockState(pos);
                            if (state.isAir()) continue;

                            // Distance fade from camera
                            double dx = x + 0.5 - camPos.x;
                            double dy = y + 0.5 - camPos.y;
                            double dz = z + 0.5 - camPos.z;
                            double distSq = dx * dx + dy * dy + dz * dz;

                            float dist = (float) Math.sqrt(distSq);
                            float t = 1.0f - (dist / (RENDER_DISTANCE + 0.5f));
                            t = MathHelper.clamp(t, 0.0f, 1.0f);
                            float fade = t * t;
                            if (fade <= 0.01f) continue;

                            int a = (int) (fade * 255.0f);
                            int c = (int) MathHelper.clamp(
                                MathHelper.lerp(fade, MIN_FADE_BRIGHTNESS, 255.0f),
                                0.0f, 255.0f
                            );

                            // Check which faces are exposed
                            boolean showUp    = shouldRenderFace(world, pos, neighborPos, Direction.UP);
                            boolean showDown  = shouldRenderFace(world, pos, neighborPos, Direction.DOWN);
                            boolean showNorth = shouldRenderFace(world, pos, neighborPos, Direction.NORTH);
                            boolean showSouth = shouldRenderFace(world, pos, neighborPos, Direction.SOUTH);
                            boolean showWest  = shouldRenderFace(world, pos, neighborPos, Direction.WEST);
                            boolean showEast  = shouldRenderFace(world, pos, neighborPos, Direction.EAST);

                            if (!(showUp || showDown || showNorth || showSouth || showWest || showEast)) continue;

                            final double wx = pos.getX();
                            final double wy = pos.getY();
                            final double wz = pos.getZ();

                            if (state.isOpaqueFullCube(world, pos)) {
                                double x0 = wx;
                                double y0 = wy;
                                double z0 = wz;
                                double x1 = wx + 1.0;
                                double y1 = wy + 1.0;
                                double z1 = wz + 1.0;

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
                                }

                                // NORTH (-Z)
                                if (showNorth) {
                                    emitQuadWithWorldPos(vc, m,
                                        x1, y0, z0 - SURFACE_NUDGE,
                                        x0, y0, z0 - SURFACE_NUDGE,
                                        x0, y1, z0 - SURFACE_NUDGE,
                                        x1, y1, z0 - SURFACE_NUDGE,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius,
                                        true
                                    );
                                }

                                // SOUTH (+Z)
                                if (showSouth) {
                                    emitQuadWithWorldPos(vc, m,
                                        x0, y0, z1 + SURFACE_NUDGE,
                                        x1, y0, z1 + SURFACE_NUDGE,
                                        x1, y1, z1 + SURFACE_NUDGE,
                                        x0, y1, z1 + SURFACE_NUDGE,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius,
                                        true
                                    );
                                }

                                // WEST (-X)
                                if (showWest) {
                                    emitQuadWithWorldPos(vc, m,
                                        x0 - SURFACE_NUDGE, y0, z0,
                                        x0 - SURFACE_NUDGE, y0, z1,
                                        x0 - SURFACE_NUDGE, y1, z1,
                                        x0 - SURFACE_NUDGE, y1, z0,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius,
                                        true
                                    );
                                }

                                // EAST (+X)
                                if (showEast) {
                                    emitQuadWithWorldPos(vc, m,
                                        x1 + SURFACE_NUDGE, y0, z1,
                                        x1 + SURFACE_NUDGE, y0, z0,
                                        x1 + SURFACE_NUDGE, y1, z0,
                                        x1 + SURFACE_NUDGE, y1, z1,
                                        c, c, c, a, overlay, fullBright,
                                        zoneCenterX, zoneCenterZ, zoneRadius,
                                        true
                                    );
                                }
                            } else {
                                VoxelShape shape = state.getOutlineShape(world, pos);
                                if (shape.isEmpty()) continue;

                                final int fc = c;
                                final int fa = a;
                                final double fZoneCenterX = zoneCenterX;
                                final double fZoneCenterZ = zoneCenterZ;
                                final double fZoneRadius = zoneRadius;

                                final boolean fShowUp = showUp;
                                final boolean fShowDown = showDown;
                                final boolean fShowNorth = showNorth;
                                final boolean fShowSouth = showSouth;
                                final boolean fShowWest = showWest;
                                final boolean fShowEast = showEast;

                                shape.forEachBox((minBX, minBY, minBZ, maxBX, maxBY, maxBZ) -> {
                                    double bx0 = wx + minBX;
                                    double by0 = wy + minBY;
                                    double bz0 = wz + minBZ;
                                    double bx1 = wx + maxBX;
                                    double by1 = wy + maxBY;
                                    double bz1 = wz + maxBZ;

                                    if (fShowUp) {
                                        emitQuadHorizontal(vc, m,
                                            bx0, by1 + SURFACE_NUDGE, bz0,
                                            bx1, by1 + SURFACE_NUDGE, bz0,
                                            bx1, by1 + SURFACE_NUDGE, bz1,
                                            bx0, by1 + SURFACE_NUDGE, bz1,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius
                                        );
                                    }

                                    if (fShowDown) {
                                        emitQuadHorizontal(vc, m,
                                            bx0, by0 - SURFACE_NUDGE, bz1,
                                            bx1, by0 - SURFACE_NUDGE, bz1,
                                            bx1, by0 - SURFACE_NUDGE, bz0,
                                            bx0, by0 - SURFACE_NUDGE, bz0,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius
                                        );
                                    }

                                    if (fShowNorth) {
                                        emitQuadWithWorldPos(vc, m,
                                            bx1, by0, bz0 - SURFACE_NUDGE,
                                            bx0, by0, bz0 - SURFACE_NUDGE,
                                            bx0, by1, bz0 - SURFACE_NUDGE,
                                            bx1, by1, bz0 - SURFACE_NUDGE,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius,
                                            true
                                        );
                                    }

                                    if (fShowSouth) {
                                        emitQuadWithWorldPos(vc, m,
                                            bx0, by0, bz1 + SURFACE_NUDGE,
                                            bx1, by0, bz1 + SURFACE_NUDGE,
                                            bx1, by1, bz1 + SURFACE_NUDGE,
                                            bx0, by1, bz1 + SURFACE_NUDGE,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius,
                                            true
                                        );
                                    }

                                    if (fShowWest) {
                                        emitQuadWithWorldPos(vc, m,
                                            bx0 - SURFACE_NUDGE, by0, bz0,
                                            bx0 - SURFACE_NUDGE, by0, bz1,
                                            bx0 - SURFACE_NUDGE, by1, bz1,
                                            bx0 - SURFACE_NUDGE, by1, bz0,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius,
                                            true
                                        );
                                    }

                                    if (fShowEast) {
                                        emitQuadWithWorldPos(vc, m,
                                            bx1 + SURFACE_NUDGE, by0, bz1,
                                            bx1 + SURFACE_NUDGE, by0, bz0,
                                            bx1 + SURFACE_NUDGE, by1, bz0,
                                            bx1 + SURFACE_NUDGE, by1, bz1,
                                            fc, fc, fc, fa, overlay, fullBright,
                                            fZoneCenterX, fZoneCenterZ, fZoneRadius,
                                            true
                                        );
                                    }
                                });
                            }

                            quadsEmitted += 6;
                            if (quadsEmitted >= MAX_QUADS) {
                                immediate.draw();
                                return;
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
     * Square-cylinder projector radius that encloses the zone, measured from its center.
     * This controls the square perimeter length: period = 8 * radius.
     */
    private static double computeZoneRadius(ProjectionZone zone) {
        double cx = zone.getCenterX();
        double cz = zone.getCenterZ();

        BlockPos min = zone.getMin();
        BlockPos max = zone.getMax();

        // use outer boundary (max + 1) so the perimeter encloses the full blocks
        double maxDx = Math.max(Math.abs(min.getX() - cx), Math.abs((max.getX() + 1) - cx));
        double maxDz = Math.max(Math.abs(min.getZ() - cz), Math.abs((max.getZ() + 1) - cz));
        return Math.max(maxDx, maxDz);
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
        double x0, double y0, double z0,
        double x1, double y1, double z1,
        double x2, double y2, double z2,
        double x3, double y3, double z3,
        int r, int g, int b, int a,
        int overlay, int light,
        double zoneCenterX, double zoneCenterZ,
        double zoneRadius,
        boolean fixPerimeterWrap
    ) {
        float u0 = computeSquarePerimeterU(x0, z0, zoneCenterX, zoneCenterZ, zoneRadius);
        float u1 = computeSquarePerimeterU(x1, z1, zoneCenterX, zoneCenterZ, zoneRadius);
        float u2 = computeSquarePerimeterU(x2, z2, zoneCenterX, zoneCenterZ, zoneRadius);
        float u3 = computeSquarePerimeterU(x3, z3, zoneCenterX, zoneCenterZ, zoneRadius);

        if (fixPerimeterWrap) {
            float period = (float) (8.0 * Math.max(zoneRadius, 1e-6));
            float[] us = {u0, u1, u2, u3};
            fixUvWrappingPerimeter(us, period);
            u0 = us[0]; u1 = us[1]; u2 = us[2]; u3 = us[3];
        }

        vc.vertex(m, (float)x0, (float)y0, (float)z0)
            .color(r, g, b, a)
            .texture(u0, (float)y0)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x1, (float)y1, (float)z1)
            .color(r, g, b, a)
            .texture(u1, (float)y1)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x2, (float)y2, (float)z2)
            .color(r, g, b, a)
            .texture(u2, (float)y2)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x3, (float)y3, (float)z3)
            .color(r, g, b, a)
            .texture(u3, (float)y3)
            .overlay(overlay)
            .light(light);
    }

    /**
     * Emits a quad for horizontal faces (UP/DOWN) with radial projection from zone center.
     * Like 4 projectors at the center facing N/S/E/W, projecting onto the floor/ceiling.
     *
     * UV.x = perimeter position around the center (same coordinate system as vertical walls)
     * UV.y = distance from the zone center (so grid lines converge toward center)
     */
    private static void emitQuadHorizontal(
        VertexConsumer vc,
        Matrix4f m,
        double x0, double y0, double z0,
        double x1, double y1, double z1,
        double x2, double y2, double z2,
        double x3, double y3, double z3,
        int r, int g, int b, int a,
        int overlay, int light,
        double zoneCenterX, double zoneCenterZ,
        double zoneRadius
    ) {
        // U = perimeter position (same as vertical faces for seamless wrapping)
        // V = world Y height (matches vertical faces; top/bottom become converging spokes)
        float u0 = computeSquarePerimeterU(x0, z0, zoneCenterX, zoneCenterZ, zoneRadius);
        float u1 = computeSquarePerimeterU(x1, z1, zoneCenterX, zoneCenterZ, zoneRadius);
        float u2 = computeSquarePerimeterU(x2, z2, zoneCenterX, zoneCenterZ, zoneRadius);
        float u3 = computeSquarePerimeterU(x3, z3, zoneCenterX, zoneCenterZ, zoneRadius);

        // Fix wrapping for quads that cross the seam
        float period = (float) (8.0 * Math.max(zoneRadius, 1e-6));
        float[] us = {u0, u1, u2, u3};
        fixUvWrappingPerimeter(us, period);
        u0 = us[0]; u1 = us[1]; u2 = us[2]; u3 = us[3];

        // V = world Y height (square-cylinder projection)
        // This makes the top/bottom faces show straight "spokes" that converge to the zone center,
        // matching the raycast-perimeter U mapping used on the walls.
        float v0 = (float) y0;
        float v1 = (float) y1;
        float v2 = (float) y2;
        float v3 = (float) y3;

        

        vc.vertex(m, (float)x0, (float)y0, (float)z0)
            .color(r, g, b, a)
            .texture(u0, v0)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x1, (float)y1, (float)z1)
            .color(r, g, b, a)
            .texture(u1, v1)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x2, (float)y2, (float)z2)
            .color(r, g, b, a)
            .texture(u2, v2)
            .overlay(overlay)
            .light(light);

        vc.vertex(m, (float)x3, (float)y3, (float)z3)
            .color(r, g, b, a)
            .texture(u3, v3)
            .overlay(overlay)
            .light(light);
    }

    /**
     * Computes square-cylinder perimeter U coordinate:
     * - Shoot a ray from the zone center through the vertex (XZ plane)
     * - Intersect it with the square boundary of radius R (max(|x|,|z|)=R)
     * - Convert boundary point to continuous perimeter distance [0..8R)
     */
    private static float computeSquarePerimeterU(double worldX, double worldZ,
                                                 double centerX, double centerZ,
                                                 double radius) {
        double R = Math.max(radius, 1e-6);

        double rx = worldX - centerX;
        double rz = worldZ - centerZ;

        double len = Math.sqrt(rx * rx + rz * rz);
        // Stable fallback direction if we are exactly at center
        double dx = (len > 1e-9) ? (rx / len) : 1.0;
        double dz = (len > 1e-9) ? (rz / len) : 0.0;

        // Ray-square boundary intersection (scale so max(|x|,|z|)=R)
        double m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m < 1e-9) m = 1.0;

        double hx = dx * (R / m);
        double hz = dz * (R / m);

        // Convert boundary point to perimeter distance [0..8R)
        // Origin at (x=+R, z=-R), increasing CCW: East -> North -> West -> South
        double ax = Math.abs(hx);
        double az = Math.abs(hz);

        double u;
        if (ax >= az) {
            if (hx >= 0.0) {
                // East side: x=+R, z:-R..R
                u = hz + R;                 // [0..2R)
            } else {
                // West side: x=-R, z:R..-R
                u = 4.0 * R + (R - hz);     // [4R..6R)
            }
        } else {
            if (hz >= 0.0) {
                // North side: z=+R, x:R..-R
                u = 2.0 * R + (R - hx);     // [2R..4R)
            } else {
                // South side: z=-R, x:-R..R
                u = 6.0 * R + (hx + R);     // [6R..8R)
            }
        }

        double perim = 8.0 * R;
        u = u % perim;
        if (u < 0.0) u += perim;

        return (float) u;
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