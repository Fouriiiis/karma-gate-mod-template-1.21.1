package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
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
import net.minecraft.world.World;

public class KarmaGateBlock extends BlockWithEntity {
    public static final MapCodec<KarmaGateBlock> CODEC = createCodec(KarmaGateBlock::new);
    @Override public MapCodec<KarmaGateBlock> getCodec() { return CODEC; }

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

        // === Crouch + Operator: bind controller ===
        if (player.isSneaking() && player.hasPermissionLevel(2)) { // level 2 == OP in survival
            int bound = gate.configureAsControllerAndBindNearest(15); // radius configurable
            if (player instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.sendMessage(
                    net.minecraft.text.Text.literal("Bound " + bound + " gate(s) as controller."), false
                );
            }
            return ActionResult.SUCCESS;
        }

        // === Normal interaction: toggle ===
        gate.toggle();
        return ActionResult.SUCCESS;
    }
}
