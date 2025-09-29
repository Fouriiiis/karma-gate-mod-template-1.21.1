package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.block.BlockState;

public class KarmaGateBlockRenderer extends GeoBlockRenderer<KarmaGateBlockEntity> {
    public KarmaGateBlockRenderer(BlockEntityRendererFactory.Context context) {
        super(new KarmaGateBlockModel());
    }

    @Override
    public void render(KarmaGateBlockEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        BlockState state = entity.getCachedState();
        Direction.Axis axis = Direction.Axis.Z;
        if (state.contains(KarmaGateBlock.AXIS)) {
            axis = state.get(KarmaGateBlock.AXIS);
        }
        matrices.push();
        matrices.translate(0.5, 0, 0.5);
        float rotation = axis == Direction.Axis.X ? 90f : 0f;
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(rotation));
        matrices.translate(-0.5, 0, -0.5);
        super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }

    // Never cull due to small AABB; our model and effects extend beyond the block space.
    @Override
    public boolean rendersOutsideBoundingBox(KarmaGateBlockEntity be) {
        return true;
    }

    // Push culling distance far out so walking away doesn't stop rendering/animation.
    @Override
    public int getRenderDistance() {
        return 1024; // blocks
    }
}
