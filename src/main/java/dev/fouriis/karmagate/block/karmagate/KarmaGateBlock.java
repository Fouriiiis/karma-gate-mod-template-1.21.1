package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class KarmaGateBlock extends BlockWithEntity {
    public static final MapCodec<KarmaGateBlock> CODEC = createCodec(KarmaGateBlock::new);
    @Override public MapCodec<KarmaGateBlock> getCodec() { return CODEC; }

    // Gate uses 2 possible axes (east–west or north–south)
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    public KarmaGateBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(AXIS, Direction.Axis.Z));
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new KarmaGateBlockEntity(pos, state);
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Place along the player’s facing axis (like logs)
        return getDefaultState().with(AXIS, ctx.getHorizontalPlayerFacing().getAxis());
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90) {
            Direction.Axis current = state.get(AXIS);
            return state.with(AXIS, current == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        return state;
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return rotate(state, mirror.getRotation(Direction.NORTH));
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntities.KARMA_GATE_BLOCK_ENTITY
                ? (w, p, s, be) -> ((KarmaGateBlockEntity) be).tick(w, p, s, (KarmaGateBlockEntity) be)
                : null;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof KarmaGateBlockEntity gate)) return ActionResult.PASS;

        // Crouch + OP: configure this block as controller and bind nearby gates
        if (player.isSneaking() && player.hasPermissionLevel(2)) {
            int bound = gate.configureAsControllerAndBindNearest(15);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.sendMessage(
                        net.minecraft.text.Text.literal("Bound " + bound + " gate(s) as controller."), false
                );
            }
            return ActionResult.SUCCESS;
        }

        // Normal use: toggle open/closed
        gate.toggle();
        return ActionResult.SUCCESS;
    }

    /* -------------------- Shapes (collision & outline) -------------------- */

    /** Returns true if the gate BE at this pos is currently open. */
    private static boolean isOpen(World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        return (be instanceof KarmaGateBlockEntity k) && k.isOpen();
    }

    /**
     * 3 blocks wide × 5 blocks tall × 1 block thick slab centered on this block.
     * Width is along the gate's axis (X or Z), thickness is the perpendicular axis.
     */
    private static VoxelShape makeClosedShape(BlockState state) {
        Direction.Axis axis = state.get(AXIS);

        // We build a shape that extends one block to each side along the "wide" axis,
        // and stays within this block on the "thin" axis. Height is 5 blocks.
        double minWide = -1.0; // one block to the negative side
        double maxWide =  2.0; // one block to the positive side
        double minThin =  0.0;
        double maxThin =  1.0;
        double minY    =  0.0;
        double maxY    =  5.0;

        if (axis == Direction.Axis.X) {
            // 3 wide along X, 1 thick along Z
            return VoxelShapes.cuboid(minWide, minY, minThin, maxWide, maxY, maxThin);
        } else {
            // 3 wide along Z, 1 thick along X
            return VoxelShapes.cuboid(minThin, minY, minWide, maxThin, maxY, maxWide);
        }
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world instanceof World w && isOpen(w, pos)) {
            return VoxelShapes.empty();
        }
        return makeClosedShape(state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world instanceof World w && isOpen(w, pos)) {
            return VoxelShapes.empty();
        }
        return makeClosedShape(state);
    }
}
