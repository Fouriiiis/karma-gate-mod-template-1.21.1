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
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;

public final class GridProjectRenderer {
    private GridProjectRenderer() {}

    // Projection range (roughly a sphere around the camera)
    private static final int RADIUS_BLOCKS = 50;
    private static final int MAX_QUADS = 60_000;
    private static final float SURFACE_NUDGE = 0.00125f;
    private static final float MIN_FADE_BRIGHTNESS = 32.0f; // 0..255

    // Focus point visuals
    private static final float FOCUS_RADIUS_WORLD = 0.75f; // blocks
    private static final float FOCUS_BORDER_PX = 2.0f;
    private static final float FOCUS_LINE_PX = 1.5f;

    // --- Angle integration state (render thread only) ---
    private static boolean hasAngles = false;
    private static float lastYawRad = 0f;
    private static float lastPitchRad = 0f;
    private static boolean hasPos = false;
    private static double lastCamX = 0.0;
    private static double lastCamY = 0.0;
    private static double lastCamZ = 0.0;
    private static float scrollU = 0f;
    private static float scrollV = 0f;

    public static void renderLate(float tickDelta, Camera camera) {
        if (GridProjectShaders.PROGRAM == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;
        if (world == null || camera == null) return;

        // Avoid doing work when the framebuffer is not ready.
        if (mc.getWindow() == null || mc.getWindow().getFramebufferWidth() <= 0 || mc.getWindow().getFramebufferHeight() <= 0) return;
        int fbw = mc.getWindow().getFramebufferWidth();
        int fbh = mc.getWindow().getFramebufferHeight();

        Vec3d camPos = camera.getPos();
        BlockPos center = BlockPos.ofFloored(camPos);

        // Bind our shader so uniform updates go to the right program.
        ShaderProgram program = GridProjectShaders.PROGRAM;
        RenderSystem.setShader(() -> program);

        // Update uniforms that must change every frame.
        float invW = 1.0f / (float) fbw;
        float invH = 1.0f / (float) fbh;
        setUniform2f(program, "uInvScreenSize", invW, invH);

        // Derive yaw/pitch directly from camera to avoid singularities at zenith/nadir.
        // Minecraft yaw: 0 = South (+Z), 90 = West (-X), 180 = North (-Z), 270 = East (+X)
        // We convert to standard math angles (0 = East, CCW) or just pass as is and handle in shader.
        // Let's pass radians.
        // Note: Camera.getYaw() returns degrees.
        float yawRad = (float) Math.toRadians(camera.getYaw());
        float pitchRad = (float) Math.toRadians(camera.getPitch());

        // Scale delta angles -> UV so grid scroll rate matches what the camera does on screen.
        // We use dynamic FOV to stay consistent with zoom/speed/FOV effects.
        double vfovDeg = ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeGetFov(camera, tickDelta, true);
        float vfovRad = (float) Math.toRadians(vfovDeg);
        float aspect = (float) fbw / (float) Math.max(1, fbh);
        
        // Pass FOV scale to shader for correct ray reconstruction
        float tanHalfFov = (float) Math.tan(vfovRad * 0.5f);
        setUniform1f(program, "uFovScale", tanHalfFov);

        // Pass raw angles to shader for infinity tunnel effect.
        // uScrollUV.x = Yaw (radians)
        // uScrollUV.y = Pitch (radians)
        scrollU = yawRad;
        scrollV = pitchRad;

        setUniform2f(program, "uScrollUV", scrollU, scrollV);
        setUniform1f(program, "uCamY", (float) camPos.y);

        // Provide a reliable animation time source for the shader.
        // Some modded shader pipelines (e.g., Iris) may not always populate GameTime for custom programs.
        setUniform1f(program, "uTime", (float) (world.getTime() + tickDelta));

        // Build a bobbed view matrix like the other late renderers in this mod.
        Matrix4f view = new Matrix4f()
            .rotation(camera.getRotation())
            .transpose()
            .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((GameRendererAccessor) mc.gameRenderer).karmaGate$invokeBobView(matrices, tickDelta);
        }
        matrices.peek().getPositionMatrix().mul(view);

        Matrix4f m = matrices.peek().getPositionMatrix();

        // Focus points (project world -> screen in Java so it matches the CPU-transformed vertex positions)
        setUniform1f(program, "uFocusBorderPx", FOCUS_BORDER_PX);
        setUniform1f(program, "uFocusLinePx", FOCUS_LINE_PX);
        int fpCount = FocusPoints.count();
        setUniform1f(program, "uFocusCount", (float) fpCount);

        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        float projY = proj.m11();

        for (int i = 0; i < FocusPoints.MAX_POINTS; i++) {
            var p = FocusPoints.get(i);
            if (p == null) {
                setUniform4f(program, "uFocusScreen" + i, 0.0f, 0.0f, 0.0f, 0.0f);
                continue;
            }

            Vector4f v = new Vector4f((float) p.x, (float) p.y, (float) p.z, 1.0f);
            m.transform(v);

            // In front of the camera is typically -Z in view space.
            float vz = v.z;
            if (vz >= -1e-3f) {
                setUniform4f(program, "uFocusScreen" + i, 0.0f, 0.0f, 0.0f, 0.0f);
                continue;
            }

            Vector4f clip = new Vector4f(v);
            proj.transform(clip);
            if (clip.w <= 1e-6f) {
                setUniform4f(program, "uFocusScreen" + i, 0.0f, 0.0f, 0.0f, 0.0f);
                continue;
            }

            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;

            float px = (ndcX * 0.5f + 0.5f) * fbw;
            float py = (ndcY * 0.5f + 0.5f) * fbh;

            float radiusPx = (FOCUS_RADIUS_WORLD * projY / (-vz)) * 0.5f * fbh;
            if (!(radiusPx > 0.5f)) {
                setUniform4f(program, "uFocusScreen" + i, 0.0f, 0.0f, 0.0f, 0.0f);
                continue;
            }

            setUniform4f(program, "uFocusScreen" + i, px, py, radiusPx, 1.0f);
        }

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = immediate.getBuffer(GridProjectRenderLayer.get());

        // Light + overlay (we treat this like an emissive projection)
        final int fullBright = LightmapTextureManager.pack(15, 15);
        final int overlay = OverlayTexture.DEFAULT_UV;

        int quadsEmitted = 0;

        int r = RADIUS_BLOCKS;
        double maxDistSq = (RADIUS_BLOCKS + 0.5) * (RADIUS_BLOCKS + 0.5);

        // Hot-path allocations: avoid per-block BlockPos creation and repeated biome lookups.
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighborPos = new BlockPos.Mutable();
        Long2BooleanOpenHashMap biomeIsPlainsCache = new Long2BooleanOpenHashMap();

        // Keep GL state consistent: we don’t touch projection/model-view here.
        RenderSystem.disableCull();
        try {
            // Iterate a true sphere to avoid scanning the cube corners (helps a lot at larger radii).
            for (int dz = -r; dz <= r; dz++) {
                int z = center.getZ() + dz;
                int dz2 = dz * dz;
                for (int dx = -r; dx <= r; dx++) {
                    int x = center.getX() + dx;
                    int dx2 = dx * dx;

                    int dxy2 = dx2 + dz2;
                    if (dxy2 > r * r) continue;

                    int maxDy = (int) Math.floor(Math.sqrt((double) (r * r - dxy2)));
                    for (int dy = -maxDy; dy <= maxDy; dy++) {
                        int y = center.getY() + dy;

                    double cx = x + 0.5;
                    double cy = y + 0.5;
                    double cz = z + 0.5;
                    double distSq = (cx - camPos.x) * (cx - camPos.x)
                                  + (cy - camPos.y) * (cy - camPos.y)
                                  + (cz - camPos.z) * (cz - camPos.z);
                    if (distSq > maxDistSq) continue;

                    pos.set(x, y, z);

                    // Biome gate: only project within Plains.
                    // This is a hard cutoff per block, which creates a clean border.
                    // Cache biome checks at 4x4x4 "biome cell" resolution (huge win at larger radii).
                    long biomeCellKey = BlockPos.asLong(x >> 2, y >> 2, z >> 2);
                    boolean isPlains;
                    if (biomeIsPlainsCache.containsKey(biomeCellKey)) {
                        isPlains = biomeIsPlainsCache.get(biomeCellKey);
                    } else {
                        isPlains = world.getBiome(pos).matchesKey(BiomeKeys.PLAINS);
                        biomeIsPlainsCache.put(biomeCellKey, isPlains);
                    }
                    if (!isPlains) continue;

                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    // Fade with distance (make it obvious at larger radii).
                    // t: 1 near camera -> 0 at edge.
                    float dist = (float) Math.sqrt(distSq);
                    float t = 1.0f - (dist / (RADIUS_BLOCKS + 0.5f));
                    t = MathHelper.clamp(t, 0.0f, 1.0f);

                    // Quadratic falloff: far surfaces fade out faster.
                    float fade = t * t;
                    if (fade <= 0.01f) continue;

                    int a = (int) (fade * 255.0f);
                    int c = (int) MathHelper.clamp(MathHelper.lerp(fade, MIN_FADE_BRIGHTNESS, 255.0f), 0.0f, 255.0f);

                    // Render only exposed faces to keep polycount down.
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

                    // Fast-path: most blocks are full cubes; avoid VoxelShape iteration/lambdas.
                    if (state.isOpaqueFullCube(world, pos)) {
                        double x0 = wx;
                        double y0 = wy;
                        double z0 = wz;
                        double x1 = wx + 1.0;
                        double y1 = wy + 1.0;
                        double z1 = wz + 1.0;

                        // UP (y1)
                        if (showUp) {
                            emitQuad(vc, m,
                                x0, y1 + SURFACE_NUDGE, z0,
                                x1, y1 + SURFACE_NUDGE, z0,
                                x1, y1 + SURFACE_NUDGE, z1,
                                x0, y1 + SURFACE_NUDGE, z1,
                                0f, 1f, 0f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }

                        // DOWN (y0)
                        if (showDown) {
                            emitQuad(vc, m,
                                x0, y0 - SURFACE_NUDGE, z1,
                                x1, y0 - SURFACE_NUDGE, z1,
                                x1, y0 - SURFACE_NUDGE, z0,
                                x0, y0 - SURFACE_NUDGE, z0,
                                0f, -1f, 0f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }

                        // NORTH (z0)
                        if (showNorth) {
                            emitQuad(vc, m,
                                x1, y0, z0 - SURFACE_NUDGE,
                                x0, y0, z0 - SURFACE_NUDGE,
                                x0, y1, z0 - SURFACE_NUDGE,
                                x1, y1, z0 - SURFACE_NUDGE,
                                0f, 0f, -1f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }

                        // SOUTH (z1)
                        if (showSouth) {
                            emitQuad(vc, m,
                                x0, y0, z1 + SURFACE_NUDGE,
                                x1, y0, z1 + SURFACE_NUDGE,
                                x1, y1, z1 + SURFACE_NUDGE,
                                x0, y1, z1 + SURFACE_NUDGE,
                                0f, 0f, 1f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }

                        // WEST (x0)
                        if (showWest) {
                            emitQuad(vc, m,
                                x0 - SURFACE_NUDGE, y0, z0,
                                x0 - SURFACE_NUDGE, y0, z1,
                                x0 - SURFACE_NUDGE, y1, z1,
                                x0 - SURFACE_NUDGE, y1, z0,
                                -1f, 0f, 0f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }

                        // EAST (x1)
                        if (showEast) {
                            emitQuad(vc, m,
                                x1 + SURFACE_NUDGE, y0, z1,
                                x1 + SURFACE_NUDGE, y0, z0,
                                x1 + SURFACE_NUDGE, y1, z0,
                                x1 + SURFACE_NUDGE, y1, z1,
                                1f, 0f, 0f,
                                c, c, c,
                                a, overlay, fullBright
                            );
                        }
                    } else {
                        VoxelShape shape = state.getOutlineShape(world, pos);
                        if (shape.isEmpty()) continue;

                        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                            // NOTE: Each face emitted here is a QUAD.
                            // We intentionally nudge it slightly outward to avoid z-fighting.

                            double x0 = wx + minX;
                            double y0 = wy + minY;
                            double z0 = wz + minZ;
                            double x1 = wx + maxX;
                            double y1 = wy + maxY;
                            double z1 = wz + maxZ;

                            // UP (y1)
                            if (showUp) {
                                emitQuad(vc, m,
                                    x0, y1 + SURFACE_NUDGE, z0,
                                    x1, y1 + SURFACE_NUDGE, z0,
                                    x1, y1 + SURFACE_NUDGE, z1,
                                    x0, y1 + SURFACE_NUDGE, z1,
                                    0f, 1f, 0f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }

                            // DOWN (y0)
                            if (showDown) {
                                emitQuad(vc, m,
                                    x0, y0 - SURFACE_NUDGE, z1,
                                    x1, y0 - SURFACE_NUDGE, z1,
                                    x1, y0 - SURFACE_NUDGE, z0,
                                    x0, y0 - SURFACE_NUDGE, z0,
                                    0f, -1f, 0f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }

                            // NORTH (z0)
                            if (showNorth) {
                                emitQuad(vc, m,
                                    x1, y0, z0 - SURFACE_NUDGE,
                                    x0, y0, z0 - SURFACE_NUDGE,
                                    x0, y1, z0 - SURFACE_NUDGE,
                                    x1, y1, z0 - SURFACE_NUDGE,
                                    0f, 0f, -1f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }

                            // SOUTH (z1)
                            if (showSouth) {
                                emitQuad(vc, m,
                                    x0, y0, z1 + SURFACE_NUDGE,
                                    x1, y0, z1 + SURFACE_NUDGE,
                                    x1, y1, z1 + SURFACE_NUDGE,
                                    x0, y1, z1 + SURFACE_NUDGE,
                                    0f, 0f, 1f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }

                            // WEST (x0)
                            if (showWest) {
                                emitQuad(vc, m,
                                    x0 - SURFACE_NUDGE, y0, z0,
                                    x0 - SURFACE_NUDGE, y0, z1,
                                    x0 - SURFACE_NUDGE, y1, z1,
                                    x0 - SURFACE_NUDGE, y1, z0,
                                    -1f, 0f, 0f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }

                            // EAST (x1)
                            if (showEast) {
                                emitQuad(vc, m,
                                    x1 + SURFACE_NUDGE, y0, z1,
                                    x1 + SURFACE_NUDGE, y0, z0,
                                    x1 + SURFACE_NUDGE, y1, z0,
                                    x1 + SURFACE_NUDGE, y1, z1,
                                    1f, 0f, 0f,
                                    c, c, c,
                                    a, overlay, fullBright
                                );
                            }
                        });
                    }

                        // Rough poly cap (we can’t count inside forEachBox cheaply; this is a conservative limiter)
                        quadsEmitted += 6;
                        if (quadsEmitted >= MAX_QUADS) {
                            immediate.draw();
                            return;
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

        // Treat air as exposed.
        if (neighbor.isAir()) return true;

        // If the neighbor is not a full opaque cube, we still want the projection visible.
        // This keeps the effect showing on surfaces adjacent to e.g. leaves, glass, etc.
        return !neighbor.isOpaqueFullCube(world, neighborPos);
    }

    private static void setUniform2f(ShaderProgram program, String name, float x, float y) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(x, y);
    }

    private static void setUniform1f(ShaderProgram program, String name, float x) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(x);
    }

    private static void setUniform4f(ShaderProgram program, String name, float x, float y, float z, float w) {
        if (program == null) return;
        GlUniform u = program.getUniform(name);
        if (u != null) u.set(x, y, z, w);
    }

    private static float wrapRad(float a) {
        final float pi = (float) Math.PI;
        final float twoPi = (float) (Math.PI * 2.0);
        while (a <= -pi) a += twoPi;
        while (a > pi) a -= twoPi;
        return a;
    }
    private static void emitQuad(
        VertexConsumer vc,
        Matrix4f m,
        double x0, double y0, double z0,
        double x1, double y1, double z1,
        double x2, double y2, double z2,
        double x3, double y3, double z3,
        float nx, float ny, float nz,
        int r,
        int g,
        int b,
        int a,
        int overlay,
        int light
    ) {
        // Color is white; shader turns it cyan. Vertex alpha controls fade.
        vc.vertex(m, (float) x0, (float) y0, (float) z0)
            .color(r, g, b, a)
            .texture(0f, 0f)
            .overlay(overlay)
            .light(light)
            .normal(nx, ny, nz);

        vc.vertex(m, (float) x1, (float) y1, (float) z1)
            .color(r, g, b, a)
            .texture(1f, 0f)
            .overlay(overlay)
            .light(light)
            .normal(nx, ny, nz);

        vc.vertex(m, (float) x2, (float) y2, (float) z2)
            .color(r, g, b, a)
            .texture(1f, 1f)
            .overlay(overlay)
            .light(light)
            .normal(nx, ny, nz);

        vc.vertex(m, (float) x3, (float) y3, (float) z3)
            .color(r, g, b, a)
            .texture(0f, 1f)
            .overlay(overlay)
            .light(light)
            .normal(nx, ny, nz);
    }
}
