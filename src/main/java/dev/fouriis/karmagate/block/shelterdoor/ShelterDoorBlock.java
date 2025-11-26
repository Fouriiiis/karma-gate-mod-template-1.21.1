package dev.fouriis.karmagate.block.shelterdoor;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.shelterdoor.ShelterDoorBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
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
import org.jetbrains.annotations.Nullable;

/**
 * Shelter Door block with full 6-way facing (N, S, E, W, UP, DOWN).
 * Any previous axis-based state has been removed.
 */
public class ShelterDoorBlock extends BlockWithEntity {
    public static final MapCodec<ShelterDoorBlock> CODEC = createCodec(ShelterDoorBlock::new);
    @Override public MapCodec<ShelterDoorBlock> getCodec() { return CODEC; }

    /** Six-way facing property. */
    public static final DirectionProperty FACING = Properties.FACING;

    public ShelterDoorBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        // Rendered by the block entity (Geo/BER), so use animated type.
        return BlockRenderType.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ShelterDoorBlockEntity(pos, state);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Face TOWARD the player’s eyes (like GateLight), but support vertical placement too.
        // Using player look direction gives proper UP/DOWN when looking steeply.
        Direction face = ctx.getPlayerLookDirection();
        return this.getDefaultState().with(FACING, face);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return validateTicker(type, ModBlockEntities.SHELTER_DOOR_BLOCK_ENTITY,
                (w, pos, s, be) -> be.tick(w, pos, s, be));
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ShelterDoorBlockEntity door) {
            door.toggle();
        }
        return ActionResult.SUCCESS;
    }

    private static boolean isOpen(World world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        return (be instanceof ShelterDoorBlockEntity k) && k.isOpen();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        // Keep original behavior: show a full cube for creative players so it's easy to select,
        // otherwise become empty when open.
        // Always return a non-empty outline to prevent particle bounding box crashes when breaking
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        if (view instanceof World w && isOpen(w, pos)) return VoxelShapes.empty();
        return VoxelShapes.fullCube();
    }
}
