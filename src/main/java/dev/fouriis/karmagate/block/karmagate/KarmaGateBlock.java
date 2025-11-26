package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.BlockRenderType;
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

    // ===== Shared dimensions (keep in sync with KarmaGatePartBlock) =====
    public static final int GATE_WIDTH  = KarmaGatePartBlock.GATE_WIDTH;   // perpendicular span
    public static final int GATE_HEIGHT = KarmaGatePartBlock.GATE_HEIGHT;  // vertical span
    public static final int GATE_DEPTH  = KarmaGatePartBlock.GATE_DEPTH;   // along axis (0 = base slice)

    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;

    public KarmaGateBlock(Settings settings) {
        super(settings.nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(AXIS, Direction.Axis.Z));
    }

    // Use the BlockEntityRenderer (GeoBlockRenderer) instead of the baked model
    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new KarmaGateBlockEntity(pos, state);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState s = getDefaultState().with(AXIS, ctx.getHorizontalPlayerFacing().getAxis());
        World w = ctx.getWorld();
        BlockPos base = ctx.getBlockPos();
        Direction.Axis axis = (s.get(AXIS) == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        int halfW = (GATE_WIDTH - 1) / 2;

        for (int h = 0; h < GATE_HEIGHT; h++) {
            for (int d = 0; d < GATE_DEPTH; d++) {
                for (int a = -halfW; a <= halfW; a++) {
                    // NOTE: depth goes FORWARD along the gate axis -> place at -d from base so resolveBase uses +d
                    BlockPos p = (axis == Direction.Axis.X)
                            ? base.add(-d, h,  a)   // X axis: depth = -d on X, width = a on Z
                            : base.add( a, h, -d);  // Z axis: width = a on X, depth = -d on Z
                    if (!w.getBlockState(p).canReplace(ctx)) return null;
                }
            }
        }
        return s;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient) spawnParts(world, pos, state);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90) {
            return state.with(AXIS, state.get(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
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
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!world.isClient && state.getBlock() != newState.getBlock()) {
            clearPartsFromBase(world, pos, state);
        }
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        super.onBreak(world, pos, state, player);
        if (!world.isClient) clearPartsFromBase(world, pos, state);
        return state;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof KarmaGateBlockEntity gate)) return ActionResult.PASS;

        if (player.isSneaking() && player.hasPermissionLevel(2)) {
            int bound = gate.configureAsControllerAndBindNearest(25);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.sendMessage(
                        net.minecraft.text.Text.literal("Bound " + bound + " gate(s) as controller."), false
                );
            }
            return ActionResult.SUCCESS;
        }

        if (player.isCreative()) {
            gate.toggle();
        }
        return ActionResult.SUCCESS;
    }

    /* ---------- Shapes: full cube when closed; empty when open ---------- */

    private static boolean isOpen(World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        return (be instanceof KarmaGateBlockEntity k) && k.isOpen();
    }

    @Override
public VoxelShape getOutlineShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
    // Keep outline non-empty to avoid client crash in ParticleManager when breaking
    // Return full cube for selection; collision is handled separately below.
    return VoxelShapes.fullCube();
}

@Override
public VoxelShape getCollisionShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
    if (view instanceof World w && isOpen(w, pos)) {
        return VoxelShapes.empty();
    }
    return VoxelShapes.fullCube();
}

    /* ---------- Spawn & clear parts ---------- */

    private void spawnParts(World w, BlockPos basePos, BlockState baseState) {
        Direction.Axis axis = baseState.get(AXIS);
        int halfW = (GATE_WIDTH - 1) / 2;

        for (int h = 0; h < GATE_HEIGHT; h++) {
            for (int d = 0; d < GATE_DEPTH; d++) {
                for (int a = -halfW; a <= halfW; a++) {
                    if (h == 0 && d == 0 && a == 0) continue;

                    // depth goes FORWARD => place at -d along axis
                    BlockPos p = (axis == Direction.Axis.X)
                            ? basePos.add(-d, h,  a)
                            : basePos.add( a, h, -d);

                    if (!w.getBlockState(p).isAir()) continue;

                    BlockState partState = ModBlocks.KARMA_GATE_PART.getDefaultState()
                            .with(KarmaGatePartBlock.AXIS,   axis)
                            .with(KarmaGatePartBlock.HEIGHT, h)
                            .with(KarmaGatePartBlock.AOFF,   a + halfW) // [-halfW..halfW] -> [0..W-1]
                            .with(KarmaGatePartBlock.DOFF,   d);        // 0..DEPTH-1

                    w.setBlockState(p, partState, Block.NOTIFY_ALL);

                    BlockEntity be = w.getBlockEntity(p);
                    if (be instanceof KarmaGatePartBlock.PartBE partBe) {
                        partBe.setBasePos(basePos);
                    }
                }
            }
        }
    }

    private void clearPartsFromBase(World w, BlockPos basePos, BlockState baseState) {
        Direction.Axis axis = baseState.get(AXIS);
        int halfW = (GATE_WIDTH - 1) / 2;

        for (int h = 0; h < GATE_HEIGHT; h++) {
            for (int d = 0; d < GATE_DEPTH; d++) {
                for (int a = -halfW; a <= halfW; a++) {
                    if (h == 0 && d == 0 && a == 0) continue;

                    // mirror spawn positions for cleanup
                    BlockPos p = (axis == Direction.Axis.X)
                            ? basePos.add(-d, h,  a)
                            : basePos.add( a, h, -d);

                    if (w.getBlockState(p).getBlock() == ModBlocks.KARMA_GATE_PART) {
                        w.breakBlock(p, false);
                    }
                }
            }
        }
    }
}
