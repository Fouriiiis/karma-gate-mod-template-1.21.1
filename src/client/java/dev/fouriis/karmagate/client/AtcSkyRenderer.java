package dev.fouriis.karmagate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.RenderPhase.Texture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.*;
import java.lang.Math;

import static net.minecraft.client.render.RenderPhase.*;

public final class AtcSkyRenderer {
    private static final Identifier DAY   = Identifier.of("karma-gate-mod", "sky/atc_sky.png");
    private static final Identifier NIGHT = Identifier.of("karma-gate-mod", "sky/atc_nightsky.png");
    private static final Identifier DUSK  = Identifier.of("karma-gate-mod", "sky/atc_dusksky.png");

    private static final int GRID = 64;
private static final int FULLBRIGHT = LightmapTextureManager.pack(15, 15);
    private AtcSkyRenderer() {}

    public static void renderSkybox(Matrix4f modelView, Matrix4f projection, float tickDelta, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || camera == null) return;

        // (unchanged) compute weights...
        float angle = mc.world.getSkyAngle(tickDelta);
        float sunHeight = MathHelper.cos(angle * ((float)Math.PI * 2f));
        float dayRamp   = smooth01(remapClamp(sunHeight,  0.00f, 0.35f));
        float nightRamp = smooth01(remapClamp(-sunHeight, 0.00f, 0.35f));
        float duskCore  = smooth01(1f - Math.max(dayRamp, nightRamp));
        float sum = dayRamp + nightRamp + duskCore;
        float wDay = sum > 0f ? dayRamp   / sum : 0f;
        float wNg  = sum > 0f ? nightRamp / sum : 0f;
        float wDk  = sum > 0f ? duskCore  / sum : 0f;

        // IMPORTANT: sky fog can be black—clear it before we draw our full-screen mesh
        BackgroundRenderer.clearFog();

        // Setup identity MVP to emit clip-space directly
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        Matrix4f savedMV = new Matrix4f(mvStack);

        mvStack.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().identity(), VertexSorter.BY_DISTANCE);

        // Precompute transforms for UV lookup
        Matrix4f invProj = new Matrix4f(projection).invert();
        Quaternionf camRot = camera.getRotation();

        if (wDay > 0.004f)  drawSkyGrid(DAY,   wDay, invProj, camRot);
        if (wDk  > 0.004f)  drawSkyGrid(DUSK,  wDk,  invProj, camRot);
        if (wNg  > 0.004f)  drawSkyGrid(NIGHT, wNg,  invProj, camRot);

        // Restore
        mvStack.set(savedMV);
        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    private static void drawSkyGrid(Identifier tex, float alpha, Matrix4f invProj, Quaternionf camRot) {
    MinecraftClient mc = MinecraftClient.getInstance();
    VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
    VertexConsumer vc = immediate.getBuffer(skyLayer(tex));

    // drive layer opacity here (uniform), since POSITION_TEXTURE has no vertex color
    float clampedA = MathHelper.clamp(alpha, 0f, 1f);
    RenderSystem.setShaderColor(1f, 1f, 1f, clampedA);

    for (int iy = 0; iy < GRID; iy++) {
        float y0 = -1f + 2f * (iy    / (float)GRID);
        float y1 = -1f + 2f * ((iy+1)/ (float)GRID);

        for (int ix = 0; ix < GRID; ix++) {
            float x0 = -1f + 2f * (ix    / (float)GRID);
            float x1 = -1f + 2f * ((ix+1)/ (float)GRID);

            Vec2 uva = dirToUV(unprojectRay(x0, y0, invProj, camRot));
            Vec2 uvb = dirToUV(unprojectRay(x1, y0, invProj, camRot));
            Vec2 uvc = dirToUV(unprojectRay(x1, y1, invProj, camRot));
            Vec2 uvd = dirToUV(unprojectRay(x0, y1, invProj, camRot));

            float umax = Math.max(Math.max(uva.x, uvb.x), Math.max(uvc.x, uvd.x));
            float umin = Math.min(Math.min(uva.x, uvb.x), Math.min(uvc.x, uvd.x));
            if (umax - umin > 0.5f) {
                if (uva.x < 0.5f) uva.x += 1f;
                if (uvb.x < 0.5f) uvb.x += 1f;
                if (uvc.x < 0.5f) uvc.x += 1f;
                if (uvd.x < 0.5f) uvd.x += 1f;
            }

            // Tri 1
            vc.vertex(x0, y0, 0f).texture(uva.x, uva.y);
            vc.vertex(x1, y0, 0f).texture(uvb.x, uvb.y);
            vc.vertex(x1, y1, 0f).texture(uvc.x, uvc.y);
            // Tri 2
            vc.vertex(x1, y1, 0f).texture(uvc.x, uvc.y);
            vc.vertex(x0, y1, 0f).texture(uvd.x, uvd.y);
            vc.vertex(x0, y0, 0f).texture(uva.x, uva.y);
        }
    }

    immediate.draw();

    // restore for safety
    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
}


    private static RenderLayer skyLayer(Identifier texture) {
    return RenderLayer.of(
            "atc_sky_screen_mesh",
            VertexFormats.POSITION_TEXTURE,              // no lightmap, no color needed
            VertexFormat.DrawMode.TRIANGLES,
            32768,
            false,
            true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(POSITION_TEXTURE_PROGRAM)   // this one exists on 1.21.1
                    .texture(new RenderPhase.Texture(texture, false, false))
                    .transparency(TRANSLUCENT_TRANSPARENCY)
                    .cull(DISABLE_CULLING)
                    .depthTest(ALWAYS_DEPTH_TEST)        // always behind world
                    .writeMaskState(COLOR_MASK)          // don’t write depth
                    .build(false)
    );

}

    private static Vector3f unprojectRay(float ndcX, float ndcY, Matrix4f invProj, Quaternionf camRot) {
        Vector4f p = new Vector4f(ndcX, ndcY, 1f, 1f).mul(invProj);
        Vector3f dirView = new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w).normalize();
        return camRot.transform(new Vector3f(dirView)).normalize();
    }

    private static Vec2 dirToUV(Vector3f d) {
        float yaw = (float)Math.atan2(d.z, d.x);
        float pitch = (float)Math.asin(MathHelper.clamp(d.y, -1f, 1f));
        float u = (yaw / (2f * (float)Math.PI)) + 0.5f;
        float v = 0.5f - (pitch / (float)Math.PI);
        if (u < 0f) u += 1f; else if (u >= 1f) u -= 1f;
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
        return new Vec2(u, v);
    }

    private static float remapClamp(float v, float lo, float hi) {
        if (hi == lo) return 0f;
        float t = (v - lo) / (hi - lo);
        return MathHelper.clamp(t, 0f, 1f);
    }
    private static float smooth01(float t) { return t * t * (3f - 2f * t); }

    private static final class Vec2 { float x, y; Vec2(float x, float y){this.x=x; this.y=y;} }
}
