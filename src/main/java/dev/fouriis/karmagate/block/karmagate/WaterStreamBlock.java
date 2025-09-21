package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

public class WaterStreamBlock extends BlockWithEntity {
    public static final MapCodec<WaterStreamBlock> CODEC = createCodec(WaterStreamBlock::new);
    @Override public MapCodec<WaterStreamBlock> getCodec() { return CODEC; }
    public static final BooleanProperty ENABLED = BooleanProperty.of("enabled");

    public WaterStreamBlock(Settings settings) {
        // Visible hitbox so you can select/break it; no baked model
        super(settings.nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(ENABLED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(ENABLED);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, net.minecraft.world.BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, net.minecraft.world.BlockView world, BlockPos pos, net.minecraft.block.ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(ENABLED, false);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        boolean now = !state.get(ENABLED);
        world.setBlockState(pos, state.with(ENABLED, now), Block.NOTIFY_ALL);
        return ActionResult.SUCCESS;
    }

    /* -------- Block Entity wiring -------- */

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return type == ModBlockEntities.WATER_STREAM_BLOCK_ENTITY
                ? (w, p, s, be) -> dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity.tick(w, p, s, (dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity) be)
                : null;
    }
}
