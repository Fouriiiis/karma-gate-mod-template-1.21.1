package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.block.shelterdoor.ShelterDoorBlock;
import dev.fouriis.karmagate.entity.shelterdoor.ShelterDoorBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class ShelterDoorRenderer extends GeoBlockRenderer<ShelterDoorBlockEntity> {
    public ShelterDoorRenderer(BlockEntityRendererFactory.Context ctx) {
        super(new ShelterDoorModel());
    }

    /* -----------------------
       Disable Geo's auto-rotate
       ----------------------- */

    protected Direction getFacing(ShelterDoorBlockEntity be, BlockState state) {
        // We will rotate manually in render(); return SOUTH so Geo does nothing surprising.
        return Direction.SOUTH;
    }

    @Override
    protected void rotateBlock(Direction ignored, MatrixStack matrices) {
        // No-op — prevent GeoBlockRenderer from applying its default rotation
    }

    /* -----------------------
       Our explicit rotation
       ----------------------- */

    @Override
    public void render(ShelterDoorBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        BlockState state = entity.getCachedState();

        // Reset angles every frame
        float rotX = 0f; // pitch
        float rotY = 0f; // yaw
        float rotZ = 0f; // roll

        if (state != null && state.contains(ShelterDoorBlock.FACING)) {
            Direction f = state.get(ShelterDoorBlock.FACING);

            // MODEL FORWARD = -X (WEST)
            // Map WEST->0°, EAST->180°, SOUTH->-90°, NORTH->+90°.
            switch (f) {
                case WEST:  // -X (model default)
                    rotY = 0f;     rotX = 0f;   rotZ = 0f;   break;
                case EAST:  // +X
                    rotY = 180f;   rotX = 0f;   rotZ = 0f;   break;
                case SOUTH: // +Z
                    rotY = 90f;   rotX = 0f;   rotZ = 0f;   break;
                case NORTH: // -Z
                    rotY = -90f;    rotX = 0f;   rotZ = 0f;   break;
                case UP:    // +Y (tip -X upward)
                    rotY = 0f;     rotX = 0f;   rotZ = -90f; break;
                case DOWN:  // -Y (tip -X downward)
                    rotY = 0f;     rotX = 0f;   rotZ = 90f;  break;
            }
        }

        matrices.push();
        matrices.translate(0.5, 0.5, 0.5);
        if (rotY != 0f) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rotY));
        if (rotX != 0f) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rotX));
        if (rotZ != 0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotZ));
        matrices.translate(-0.5, -0.5, -0.5);

        super.render(entity, tickDelta, matrices, vertexConsumers, light, overlay);
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ShelterDoorBlockEntity be) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 1024;
    }
}
