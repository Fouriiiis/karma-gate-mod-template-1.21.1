// dev/fouriis/karmagate/client/hologram/HologramProjectorRenderer.java
package dev.fouriis.karmagate.hologram;

import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import dev.fouriis.karmagate.block.hologram.HologramProjectorBlock;
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

    // Target “panel” the art should fit inside (preserving aspect): 4×8 blocks
    private static final float PANEL_W_BLOCKS = 4.0f;
    private static final float PANEL_H_BLOCKS = 8.0f;

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
        float brightness = 0.85f * (0.9f - 0.6f * flicker);

        // subtle horizontal scanline jitter (CPU-side UV wobble)
        float wobble = (float)Math.sin(time * 0.35) * 0.0025f * (0.5f + 0.5f * flicker);

        ms.push();
        // Center at block pivot (x,z), and at y = PANEL_H/2 so the 8-block-tall panel is centered vertically
        ms.translate(0.5, PANEL_H_BLOCKS * 0.5f, 0.5);

        int rotDeg = 0; // SOUTH baseline
        try {
            var state = be.getCachedState();
            if (state != null && state.contains(HologramProjectorBlock.FACING)) {
                switch (state.get(HologramProjectorBlock.FACING)) {
                    case SOUTH -> rotDeg = 0;
                    case NORTH -> rotDeg = 180;
                    case WEST  -> rotDeg = 90;
                    case EAST  -> rotDeg = 270;
                    default -> rotDeg = 0;
                }
            }
        } catch (Exception ignored) { /* defensive */ }

        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rotDeg));
        // Nudge along local +Z to avoid z-fighting with any backing plane
        ms.translate(0.0, 0.0, 0.001);

        // === Fit untrimmed source canvas into 4×8 (preserve aspect), then place the trimmed rect inside it ===
        // Per-frame source canvas size (pixels) and trimmed offset (pixels)
        int srcWpx = frame.sourceW;
        int srcHpx = frame.sourceH;

        // Pixels-to-world scale that fits the source canvas fully inside PANEL_W×PANEL_H
        float scaleX = PANEL_W_BLOCKS / (float)srcWpx;
        float scaleY = PANEL_H_BLOCKS / (float)srcHpx;
        float pxToWorld = Math.min(scaleX, scaleY);

        // Trimmed rect size in world units (what we actually draw)
        float trimW = frame.w * pxToWorld;
        float trimH = frame.h * pxToWorld;
        float halfTrimW = trimW * 0.5f;
        float halfTrimH = trimH * 0.5f;

        // Position the trimmed rect relative to the *center* of its untrimmed canvas.
        // spriteSourceSize.x/y are the top-left of the trimmed rect inside the original canvas (pixels).
        float cx_px = (frame.offX + frame.w * 0.5f) - (srcWpx * 0.5f);
        float cy_px = (srcHpx * 0.5f) - (frame.offY + frame.h * 0.5f); // flip Y (atlas down, world up)
        float cx = cx_px * pxToWorld;
        float cy = cy_px * pxToWorld;

        RenderLayer layer = HoloRenderLayer.get(SHEET, NOISE);
        VertexConsumer vc = buf.getBuffer(layer);

        // Bind noise to texture unit 1 (Sampler1)
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(1, NOISE);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);

        // Update uniforms on the currently bound shader program
        var shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (shader != null) {
            var uTime   = shader.getUniform("uTime");
            var uThresh = shader.getUniform("uThresh");
            var uWobble = shader.getUniform("uWobbleAmp");
            var uRect   = shader.getUniform("uSpriteRect");

            if (uTime   != null) {
                float ticks = (MinecraftClient.getInstance().world.getTime() + tickDelta);
                uTime.set(ticks * 0.05f);
            }
            if (uThresh != null) uThresh.set(0.45f);
            if (uWobble != null) uWobble.set(1.0f);
            if (uRect   != null) uRect.set(frame.u0, frame.v0, frame.u1, frame.v1);
        }

        MatrixStack.Entry e = ms.peek();

        // color tint from block entity color, scaled by glow
        int rgb = be.getDisplayColor(tickDelta);
        float rf = ((rgb >> 16) & 0xFF) / 255f * glow * brightness;
        float gf = ((rgb >> 8) & 0xFF) / 255f * glow * brightness;
        float bf = (rgb & 0xFF) / 255f * glow * brightness;

        // Encode per-entity static into vertex alpha (1 - static)
        float aRW = 1.0f - be.getStaticLevel();
        int aI = (int)(Math.max(0f, Math.min(1f, aRW)) * 255f);
        int rI = (int)(rf * 255f);
        int gI = (int)(gf * 255f);
        int bI = (int)(bf * 255f);
        int argb = ColorHelper.Argb.getArgb(aI, rI, gI, bI);

        // UVs with wobble
        float u0 = frame.u0 + wobble;
        float v0 = frame.v0;
        float u1 = frame.u1 + wobble;
        float v1 = frame.v1;

        // Emit the trimmed quad positioned within the 4×8 panel
        Matrix4f pm = e.getPositionMatrix();
        Matrix3f nm = e.getNormalMatrix();
        Vector3f n = new Vector3f(0f, 0f, 1f).mul(nm).normalize();

        Vector4f p1 = new Vector4f(cx - halfTrimW, cy + halfTrimH, 0f, 1f).mul(pm);
        Vector4f p2 = new Vector4f(cx + halfTrimW, cy + halfTrimH, 0f, 1f).mul(pm);
        Vector4f p3 = new Vector4f(cx + halfTrimW, cy - halfTrimH, 0f, 1f).mul(pm);
        Vector4f p4 = new Vector4f(cx - halfTrimW, cy - halfTrimH, 0f, 1f).mul(pm);

        vc.vertex(p1.x, p1.y, p1.z, argb, u0, v0, overlay, light, n.x, n.y, n.z);
        vc.vertex(p2.x, p2.y, p2.z, argb, u1, v0, overlay, light, n.x, n.y, n.z);
        vc.vertex(p3.x, p3.y, p3.z, argb, u1, v1, overlay, light, n.x, n.y, n.z);
        vc.vertex(p4.x, p4.y, p4.z, argb, u0, v1, overlay, light, n.x, n.y, n.z);

        ms.pop();
    }

    @Override public boolean rendersOutsideBoundingBox(HologramProjectorBlockEntity be) { return true; }
}
