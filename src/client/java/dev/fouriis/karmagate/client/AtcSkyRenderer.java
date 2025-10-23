package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.RenderPhase.Texture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.util.math.MatrixStack;

import org.joml.*;
import java.lang.Math;

import static net.minecraft.client.render.RenderPhase.*;

public final class AtcSkyRenderer {
    private static final Identifier DAY   = Identifier.of("karma-gate-mod", "sky/atc_sky.png");
    private static final Identifier NIGHT = Identifier.of("karma-gate-mod", "sky/atc_nightsky.png");
    private static final Identifier DUSK  = Identifier.of("karma-gate-mod", "sky/atc_dusksky.png");

    // Mesh resolution for the screen-space sky (higher -> smoother, seam artifacts reduced)
    private static final int GRID = 64; // try 64; you can push to 96/128 if desired
    private static final int FULLBRIGHT = LightmapTextureManager.pack(15, 15);

    private AtcSkyRenderer() {}

    /**
     * Call from your WorldRenderer mixin for 1.21.1:
     * renderSky(Matrix4f modelView, Matrix4f projection, float tickDelta, Camera camera, boolean thickFog, Runnable clouds)
     */
    public static void renderSkybox(Matrix4f modelView, Matrix4f projection, float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null) return;

        Vec3d camPos = camera.getPos();
        Matrix4f view = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // Add bobbing effect
        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                    .karmaGate$invokeBobView(matrices, tickDelta);
        }
        matrices.peek().getPositionMatrix().mul(view);

        // ---- Time-based weights (noon=+1, midnight=-1) ----
        float angle = mc.world.getSkyAngle(tickDelta);
        float sunHeight = MathHelper.cos(angle * ((float)java.lang.Math.PI * 2f));
        float dayRamp   = smooth01(remapClamp(sunHeight,   0.00f, 0.35f));
        float nightRamp = smooth01(remapClamp(-sunHeight,  0.00f, 0.35f));
        float duskCore  = smooth01(1f - java.lang.Math.max(dayRamp, nightRamp));
        float sum = dayRamp + nightRamp + duskCore;
        float wDay = sum > 0f ? dayRamp   / sum : 0f;
        float wNg  = sum > 0f ? nightRamp / sum : 0f;
        float wDk  = sum > 0f ? duskCore  / sum : 0f;

        // ---- Prepare identity MVP so we can emit clip-space positions directly ----
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedMV = new Matrix4f(mvStack);

        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorter.BY_DISTANCE);

        // Precompute inverse projection and camera rotation for ray->world
        Matrix4f invProj = new Matrix4f(projection).invert();  // from clip to view
        Quaternionf camRot = camera.getRotation();             // camera->world

        if (wDay > 0.004f)  drawSkyGrid(DAY,   wDay, invProj, camRot);
        if (wDk  > 0.004f)  drawSkyGrid(DUSK,  wDk,  invProj, camRot);
        if (wNg  > 0.004f)  drawSkyGrid(NIGHT, wNg,  invProj, camRot);

        // Restore
        mvStack.set(savedMV);
        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    /** Draw a subdivided full-screen grid with UVs from equirect mapping of view rays. */
    private static void drawSkyGrid(Identifier tex, float alpha, Matrix4f invProj, Quaternionf camRot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = immediate.getBuffer(skyLayer(tex));

        int a = (int)(MathHelper.clamp(alpha, 0f, 1f) * 255f);
        int r = 255, g = 255, b = 255;

        // Build GRID×GRID quads across NDC [-1..1]×[-1..1]
        for (int iy = 0; iy < GRID; iy++) {
            float y0 = -1f + 2f * (iy    / (float)GRID);
            float y1 = -1f + 2f * ((iy+1)/ (float)GRID);

            for (int ix = 0; ix < GRID; ix++) {
                float x0 = -1f + 2f * (ix    / (float)GRID);
                float x1 = -1f + 2f * ((ix+1)/ (float)GRID);

                // Compute UVs per-vertex from unprojected/rotated rays
                Vec2 uva = dirToUV(unprojectRay(x0, y0, invProj, camRot));
                Vec2 uvb = dirToUV(unprojectRay(x1, y0, invProj, camRot));
                Vec2 uvc = dirToUV(unprojectRay(x1, y1, invProj, camRot));
                Vec2 uvd = dirToUV(unprojectRay(x0, y1, invProj, camRot));

                // Handle seam near u≈0/1 to avoid interpolation across wrap
                // If the quad crosses the seam, shift smaller side by ±1.0
                float umax = java.lang.Math.max(java.lang.Math.max(uva.x, uvb.x), java.lang.Math.max(uvc.x, uvd.x));
                float umin = Math.min(Math.min(uva.x, uvb.x), Math.min(uvc.x, uvd.x));
                if (umax - umin > 0.5f) {
                    if (uva.x < 0.5f) uva.x += 1f;
                    if (uvb.x < 0.5f) uvb.x += 1f;
                    if (uvc.x < 0.5f) uvc.x += 1f;
                    if (uvd.x < 0.5f) uvd.x += 1f;
                }

                // Quad (clip-space positions, no matrix multiply)
                // Tri 1: A(x0,y0) B(x1,y0) C(x1,y1)
                vc.vertex(x0, y0, 0f).color(r,g,b,a).texture(uva.x, uva.y).light(FULLBRIGHT);
                vc.vertex(x1, y0, 0f).color(r,g,b,a).texture(uvb.x, uvb.y).light(FULLBRIGHT);
                vc.vertex(x1, y1, 0f).color(r,g,b,a).texture(uvc.x, uvc.y).light(FULLBRIGHT);
                // Tri 2: C(x1,y1) D(x0,y1) A(x0,y0)
                vc.vertex(x1, y1, 0f).color(r,g,b,a).texture(uvc.x, uvc.y).light(FULLBRIGHT);
                vc.vertex(x0, y1, 0f).color(r,g,b,a).texture(uvd.x, uvd.y).light(FULLBRIGHT);
                vc.vertex(x0, y0, 0f).color(r,g,b,a).texture(uva.x, uva.y).light(FULLBRIGHT);
            }
        }

        immediate.draw();
    }

    /** Unproject a clip-space point (x,y,1,1) to a normalized view ray, then rotate to world. */
    private static Vector3f unprojectRay(float ndcX, float ndcY, Matrix4f invProj, Quaternionf camRot) {
        // from clip-space to view-space direction
        Vector4f p = new Vector4f(ndcX, ndcY, 1f, 1f).mul(invProj);
        Vector3f dirView = new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w).normalize();

        // camera.getRotation() maps camera->world; apply it to the view-space ray
        return camRot.transform(new Vector3f(dirView)).normalize();
    }

    /** Equirectangular mapping: +X=0°, +Z=90°, -X=180°, -Z=-90° (adjust if your images differ). */
    private static Vec2 dirToUV(Vector3f d) {
        // yaw: [-pi..pi], with 0 at +X, increasing toward +Z
        float yaw = (float)Math.atan2(d.z, d.x);
        // pitch: [-pi/2..pi/2]
        float pitch = (float)Math.asin(MathHelper.clamp(d.y, -1f, 1f));

        // Convert to [0..1] range. Flip V so up is at v=0.
        float u = (yaw / (2f * (float)Math.PI)) + 0.5f;
        float v = 0.5f - (pitch / (float)Math.PI);

        // Wrap u into [0,1]
        if (u < 0f) u += 1f;
        if (u >= 1f) u -= 1f;

        // Clamp v just in case
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;

        return new Vec2(u, v);
    }

    private static RenderLayer skyLayer(Identifier texture) {
        return RenderLayer.of(
                "atc_sky_screen_mesh",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.TRIANGLES,
                32768,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                        .texture(new Texture(texture, false, false))
                        .transparency(TRANSLUCENT_TRANSPARENCY) // blend layers
                        .cull(DISABLE_CULLING)                  // avoid odd seams
                        .depthTest(ALWAYS_DEPTH_TEST)           // always behind world
                        .writeMaskState(COLOR_MASK)             // don’t write depth
                        .build(false)
        );
    }

    // Small helpers
    private static float remapClamp(float v, float lo, float hi) {
        if (hi == lo) return 0f;
        float t = (v - lo) / (hi - lo);
        return MathHelper.clamp(t, 0f, 1f);
    }
    private static float smooth01(float t) {
        return t * t * (3f - 2f * t);
    }

    /** tiny 2D float vector */
    private static final class Vec2 {
        float x, y;
        Vec2(float x, float y) { this.x = x; this.y = y; }
    }
}
