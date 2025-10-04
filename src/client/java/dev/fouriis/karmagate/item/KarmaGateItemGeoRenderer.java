package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.entity.client.KarmaGateItemModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.RotationAxis;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Custom Geo item renderer that scales the Karma Gate for inventory/ground display
 * and applies an isometric style rotation in GUI (inventory) to improve readability.
 */
public class KarmaGateItemGeoRenderer extends GeoItemRenderer<KarmaGateItem> {
    public KarmaGateItemGeoRenderer() {
        super(new KarmaGateItemModel());
    }

    @Override
    public void render(ItemStack stack,
                        ModelTransformationMode transformMode,
                        MatrixStack matrices,
                        VertexConsumerProvider vertexConsumers,
                        int light,
                        int overlay) {
        matrices.push();

        switch (transformMode) {
            case GUI -> { // Inventory slot & recipe displays
                // Center, scale down, rotate 45° around Y to show a side without tilting vertically
                matrices.translate(0.5f, 0.05f, 0.5f);
                matrices.scale(0.09f, 0.09f, 0.09f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
                matrices.translate(-0.50f, -0.50f, -0.50f);
            }
            case GROUND -> { // Dropped item (same rotation, a bit lower)
                matrices.translate(0.5f, 0.0f, 0.5f);
                matrices.scale(0.09f, 0.09f, 0.09f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
                matrices.translate(-0.50f, -0.50f, -0.50f);
            }
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> { // Hand view
                matrices.translate(0.6f, 0.55f, 0.6f);
                matrices.scale(0.09f, 0.09f, 0.09f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
                matrices.translate(-0.50f, -0.50f, -0.50f);
            }
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> { // Third person hand
                matrices.translate(0.55f, 0.55f, 0.55f);
                matrices.scale(0.09f, 0.09f, 0.09f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
                matrices.translate(-0.50f, -0.50f, -0.50f);
            }
            case FIXED -> { // Item frames / pedestal displays
                matrices.translate(0.5f, 0.5f, 0.5f);
                matrices.scale(0.09f, 0.09f, 0.09f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45f));
                matrices.translate(-0.50f, -0.50f, -0.50f);
            }
            default -> { /* leave other modes as-is */ }
        }

        super.render(stack, transformMode, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }
}
