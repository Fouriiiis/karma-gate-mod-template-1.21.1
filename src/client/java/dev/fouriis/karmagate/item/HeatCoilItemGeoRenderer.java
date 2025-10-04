package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.entity.client.HeatCoilItemModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class HeatCoilItemGeoRenderer extends GeoItemRenderer<HeatCoilItem> {
    public HeatCoilItemGeoRenderer() { super(new HeatCoilItemModel()); }

    @Override
    public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        matrices.push();
        // simple consistent small display; rotate 30° Y for depth
        // Increased size by 100% (0.18 -> 0.36) and lowered slightly to remain centered
        matrices.translate(0.5f, 0.40f, 0.5f);
        matrices.scale(0.36f, 0.36f, 0.36f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(30f));
        matrices.translate(-0.5f, -0.5f, -0.5f);
        super.render(stack, mode, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }
}
