package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.entity.client.GateLightItemModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Geo renderer for the Gate Light block item. Mirrors the inline transforms used in the
 * client initializer, but consolidated here for clarity and reuse.
 */
public class GateLightItemGeoRenderer extends GeoItemRenderer<GateLightItem> {
    public GateLightItemGeoRenderer() { super(new GateLightItemModel()); }

    @Override
    public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        matrices.push();
        // Center the model, scale to fit an inventory slot, and give a 45° Y rotation for readability
        // Increased size by 100% (0.22 -> 0.44) and lowered slightly to fit view
        matrices.translate(0.5f, 0.43f, 0.5f);
        matrices.scale(0.44f, 0.44f, 0.44f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
        matrices.translate(-0.5f, -0.5f, -0.5f);
        super.render(stack, mode, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }
}
