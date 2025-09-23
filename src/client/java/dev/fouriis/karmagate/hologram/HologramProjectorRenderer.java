// dev/fouriis/karmagate/client/hologram/HologramProjectorRenderer.java
package dev.fouriis.karmagate.hologram;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.util.math.ColorHelper;

public class HologramProjectorRenderer implements BlockEntityRenderer<HologramProjectorBlockEntity> {
    private static final Identifier SHEET = Identifier.of("karma-gate-mod", "textures/hologram/gate_sheet.png");
    private static final String FRAMES_JSON_PATH = "karma-gate-mod:hologram/gate_sheet_frames.json";
    private static final Identifier NOISE = Identifier.of("karma-gate-mod", "textures/hologram/noise.png");

    private static HoloFrameIndex FRAMES;

    public HologramProjectorRenderer(BlockEntityRendererFactory.Context ctx) {
        if (FRAMES == null) {
            FRAMES = HoloFrameIndex.load(SHEET.toString(), FRAMES_JSON_PATH);
        }
    }

    @Override
    public void render(HologramProjectorBlockEntity be, float tickDelta, MatrixStack ms, VertexConsumerProvider buf, int light, int overlay) {
        var frame = FRAMES.get(be.getSymbolKey());
        if (frame == null) return;

    float time = (MinecraftClient.getInstance().world.getTime() + tickDelta);
        float flicker = be.getFlicker();
        float glow = be.getGlow();
        float alpha = 0.85f * (0.9f - 0.6f * flicker);

        // subtle horizontal scanline jitter (CPU-side UV wobble)
        float wobble = (float)Math.sin(time * 0.35) * 0.0025f * (0.5f + 0.5f * flicker);

        // Place the quad just above block center; rotate to face player if you want
        ms.push();
    // Center at block pivot (x,z = center), and at y=3.0 (since the model plane spans 0..6 blocks high)
    ms.translate(0.5, 3.0, 0.5);
    // Nudge slightly along +Z to avoid potential z-fighting with the model's zero-thickness plane
    ms.translate(0.0, 0.0, 0.001);
    ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(0)); // rotate as needed
        // Maximize size within a bounding box while preserving aspect ratio
    // Project onto the model's plane size: width=48, height=96 (BlockBench units) => 3x6 blocks
    float maxWidth = 3.0f;  // in blocks
    float maxHeight = 6.0f; // in blocks
        float aspect = (float) frame.w / (float) frame.h; // w/h
        float width;
        float height;
        // Fit logic: choose the limiting dimension to avoid exceeding either bound
        if (maxWidth / aspect < maxHeight) {
            width = maxWidth;
            height = width / aspect;
        } else {
            height = maxHeight;
            width = aspect * height;
        }
        float hw = width * 0.5f;
        float hh = height * 0.5f;

        RenderLayer layer = HoloRenderLayer.get(SHEET, NOISE);
        VertexConsumer vc = buf.getBuffer(layer);
        // Bind noise to texture unit 1 so Sampler1 (Noise) samples it, leaving Sampler0 as the sheet.
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(1, NOISE);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        // Update uniforms on the currently bound shader program
        var shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (shader != null) {
            var uTime    = shader.getUniform("uTime");
            var uStatic  = shader.getUniform("uStatic");
            var uThresh  = shader.getUniform("uThresh");
            var uNS      = shader.getUniform("uNoiseScale");
            var uWobble  = shader.getUniform("uWobbleAmp");
            var uScr     = shader.getUniform("uScreenSize");

            if (uTime   != null) uTime.set(time);
            if (uStatic != null) uStatic.set(be.getStaticLevel());
            if (uThresh != null) uThresh.set(0.45f);
            if (uNS     != null) uNS.set(1.0f);
            if (uWobble != null) uWobble.set(1.0f);
            if (uScr    != null) {
                var win = MinecraftClient.getInstance().getWindow();
                uScr.set((float)win.getFramebufferWidth(), (float)win.getFramebufferHeight());
            }
        }
    MatrixStack.Entry e = ms.peek();

        // color tint from block entity color, scaled by glow
    int rgb = be.getColorRGB();
    float rf = ((rgb >> 16) & 0xFF) / 255f * glow;
    float gf = ((rgb >> 8) & 0xFF) / 255f * glow;
    float bf = (rgb & 0xFF) / 255f * glow;
    int aI = (int)(alpha * 255f);
    int rI = (int)(rf * 255f);
    int gI = (int)(gf * 255f);
    int bI = (int)(bf * 255f);
    int argb = ColorHelper.Argb.getArgb(aI, rI, gI, bI);

        // UVs with wobble
        float u0 = frame.u0 + wobble;
        float v0 = frame.v0;
        float u1 = frame.u1 + wobble;
        float v1 = frame.v1;

    // Pre-transform positions/normals using the current matrices, then emit vertices
    Matrix4f pm = e.getPositionMatrix();
    Matrix3f nm = e.getNormalMatrix();

    Vector4f p1 = new Vector4f(-hw,  hh,  0f, 1f).mul(pm);
    Vector4f p2 = new Vector4f( hw,  hh,  0f, 1f).mul(pm);
    Vector4f p3 = new Vector4f( hw, -hh,  0f, 1f).mul(pm);
    Vector4f p4 = new Vector4f(-hw, -hh,  0f, 1f).mul(pm);

    Vector3f n = new Vector3f(0f, 0f, 1f).mul(nm).normalize();

    vc.vertex(p1.x, p1.y, p1.z, argb, u0, v0, overlay, light, n.x, n.y, n.z);
    vc.vertex(p2.x, p2.y, p2.z, argb, u1, v0, overlay, light, n.x, n.y, n.z);
    vc.vertex(p3.x, p3.y, p3.z, argb, u1, v1, overlay, light, n.x, n.y, n.z);
    vc.vertex(p4.x, p4.y, p4.z, argb, u0, v1, overlay, light, n.x, n.y, n.z);

        ms.pop();
    }

    // static level is now directly read from the BE

    @Override public boolean rendersOutsideBoundingBox(HologramProjectorBlockEntity be) { return true; }
}
