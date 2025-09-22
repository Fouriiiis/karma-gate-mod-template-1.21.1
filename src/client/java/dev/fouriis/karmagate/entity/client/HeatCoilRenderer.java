package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity; // adjust import to your BE path
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.RotationAxis;

public class HeatCoilRenderer extends GeoBlockRenderer<HeatCoilBlockEntity> {
    public HeatCoilRenderer(BlockEntityRendererFactory.Context ctx) {
        super(new HeatCoilModel());
        addRenderLayer(new GlowLayer(this));
        addRenderLayer(new DistortionLayer(this));
    }

    private static final class GlowLayer extends GeoRenderLayer<HeatCoilBlockEntity> {
    GlowLayer(HeatCoilRenderer parent) { super(parent); }

    @Override
    public void render(MatrixStack matrices,
                       HeatCoilBlockEntity animatable,
                       BakedGeoModel bakedModel,
                       RenderLayer baseLayer,
                       VertexConsumerProvider bufferSource,
                       VertexConsumer buffer,
                       float partialTick,
                       int packedLight,
                       int packedOverlay) {

    float heat = animatable.getHeat();
    // Clamp heat and apply a small dead zone so zero/near-zero shows only the base texture
    float h = Math.max(0f, Math.min(1f, heat));
    float threshold = 0.05f; // no emissive under 5% heat
    if (h <= threshold) return;

    // Remap remaining range [threshold,1] -> [0,1] so blending starts from the coolest emissive
    float t = (h - threshold) / (1f - threshold);

    // Map to [0, 3] across 4 textures (1..4)
    float p = t * 3f; // 0..3
        int i0 = (int)Math.floor(p);
        int i1 = Math.min(i0 + 1, 3);
        float frac = p - i0; // blend toward i1

    // Base alpha: emulate cooling metal — quicker drop from hot, lingering tail near cool
    // Blend between a fast-drop quadratic and a lingering square-root tail
    float aFast = t * t;                // drops quicker at high heat
    float aTail = (float)Math.sqrt(t);  // lingers near low heat
    float baseAlpha = Math.min(1f, 0.35f * aFast + 0.65f * aTail);

        // Helper to select the right emissive texture
        java.util.function.IntFunction<net.minecraft.util.Identifier> tex = idx -> switch (idx) {
            case 0 -> HeatCoilModel.EMISSIVE_1;
            case 1 -> HeatCoilModel.EMISSIVE_2;
            case 2 -> HeatCoilModel.EMISSIVE_3;
            default -> HeatCoilModel.EMISSIVE_4;
        };

        // Fullbright for glow
        int fullBright = 0xF000F0;

        // Draw the two nearest textures with weighted alpha for a smooth transition
        float w0 = (i0 == i1) ? 1f : (1f - frac);
        float w1 = (i0 == i1) ? 0f : frac;

        if (w0 > 0f) {
            int a0 = (int)(Math.min(1f, baseAlpha * w0) * 255f);
            int argb0 = ColorHelper.Argb.getArgb(a0, 255, 255, 255);
            RenderLayer layer0 = RenderLayer.getEntityTranslucentEmissive(tex.apply(i0));
            VertexConsumer buf0 = bufferSource.getBuffer(layer0);
            getRenderer().reRender(
                bakedModel, matrices, bufferSource, animatable,
                layer0, buf0, partialTick,
                fullBright, packedOverlay, argb0
            );
        }

        if (w1 > 0f) {
            int a1 = (int)(Math.min(1f, baseAlpha * w1) * 255f);
            int argb1 = ColorHelper.Argb.getArgb(a1, 255, 255, 255);
            RenderLayer layer1 = RenderLayer.getEntityTranslucentEmissive(tex.apply(i1));
            VertexConsumer buf1 = bufferSource.getBuffer(layer1);
            getRenderer().reRender(
                bakedModel, matrices, bufferSource, animatable,
                layer1, buf1, partialTick,
                fullBright, packedOverlay, argb1
            );
        }
    }
}

    private static final class DistortionLayer extends GeoRenderLayer<HeatCoilBlockEntity> {
        DistortionLayer(HeatCoilRenderer parent) { super(parent); }

        @Override
        public void render(MatrixStack matrices,
                           HeatCoilBlockEntity animatable,
                           BakedGeoModel bakedModel,
                           RenderLayer baseLayer,
                           VertexConsumerProvider bufferSource,
                           VertexConsumer buffer,
                           float partialTick,
                           int packedLight,
                           int packedOverlay) {

            if (animatable.getWorld() == null) return;
            float heat = animatable.getHeat();
            // Distortion kicks in a bit later than glow so it doesn't look odd at very low heat
            float h = Math.max(0f, Math.min(1f, heat));
            float threshold = 0.5f; // shimmer begins around half heat
            if (h <= threshold) return;
            float t = (h - threshold) / (1f - threshold);

            // Time-based shimmer using world time for determinism
            double time = (animatable.getWorld().getTime() + partialTick) * 0.12;
            int hx = animatable.getPos().getX();
            int hy = animatable.getPos().getY();
            int hz = animatable.getPos().getZ();

            // Small non-uniform wobble and translation scaled by heat
            float amp = 0.0160f * t; // ramp from 0 at threshold to strong at full heat
            float dx = (float)(amp * Math.sin(time + hx * 0.25));
            float dz = (float)(amp * Math.cos(time * 1.13 + hz * 0.21));
            float sx = 1f + (float)(amp * 0.6f * Math.sin(time * 0.73 + hy * 0.17));
            float sy = 1f + (float)(amp * 0.4f * Math.cos(time * 0.59 + hx * 0.11));
            float sz = 1f + (float)(amp * 0.6f * Math.sin(time * 0.67 + hz * 0.13));

            // Add a very slight, heat-scaled yaw wobble
            float yawAmp = 5.0f * t; // starts subtle, peaks at full heat
            float yawDeg = (float)(yawAmp * Math.sin(time * 1.7 + hx * 0.07 + hz * 0.09));

            // Low alpha, with a subtle flicker; use non-linear response for a lingering tail
            float flicker = 0.5f + 0.5f * (float)Math.sin(time * 2.3 + (hx + hz) * 0.05);
            float baseAlpha = Math.min(0.65f, (float)(Math.pow(t, 2.0)) * 0.65f * flicker); // slow start, peaks at full
            if (baseAlpha <= 0.001f) return;

            // Choose a mid/hot emissive for the shimmer overlay depending on heat
            net.minecraft.util.Identifier tex = (t < 0.4f) ? HeatCoilModel.EMISSIVE_2 : (t < 0.8f ? HeatCoilModel.EMISSIVE_3 : HeatCoilModel.EMISSIVE_4);
            RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(tex);
            VertexConsumer vc = bufferSource.getBuffer(layer);

            matrices.push();
            matrices.translate(dx, 0, dz);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yawDeg));
            matrices.scale(sx, sy, sz);

            int argb = ColorHelper.Argb.getArgb((int)(baseAlpha * 255f), 255, 255, 255);

            // Use scene lighting so the shimmer reads as a surface effect rather than light emission
            getRenderer().reRender(
                bakedModel, matrices, bufferSource, animatable,
                layer, vc, partialTick,
                packedLight, packedOverlay, argb
            );

            matrices.pop();
        }
    }
}
