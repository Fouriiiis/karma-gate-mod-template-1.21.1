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

public class HeatCoilRenderer extends GeoBlockRenderer<HeatCoilBlockEntity> {
    public HeatCoilRenderer(BlockEntityRendererFactory.Context ctx) {
        super(new HeatCoilModel());
        addRenderLayer(new GlowLayer(this));
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
        if (heat <= 0f) return;

        // Compute glow colour (tweak to taste)
        float rF = 0.9f * heat + 0.1f;
        float gF = Math.min(1f, heat * 0.9f);
        float bF = Math.max(0f, 0.2f * (1f - heat));
        float aF = Math.min(1f, 0.25f + heat * 0.75f);

        // Pack RGBA -> ARGB int (0–255)
        int a = (int)(aF * 255f);
        int r = (int)(rF * 255f);
        int g = (int)(gF * 255f);
        int b = (int)(bF * 255f);
        int argb = ColorHelper.Argb.getArgb(a, r, g, b);

        // Emissive layer (use your emissive/glow texture)
        RenderLayer emissive = RenderLayer.getEntityTranslucentEmissive(HeatCoilModel.EMISSIVE);
        VertexConsumer emissiveBuf = bufferSource.getBuffer(emissive);

        // Fullbright for glow
        int fullBright = 0xF000F0;

        // Re-render the baked model with emissive layer + packed ARGB
        getRenderer().reRender(
            bakedModel, matrices, bufferSource, animatable,
            emissive, emissiveBuf, partialTick,
            fullBright, packedOverlay, argb
        );
    }
}
}
