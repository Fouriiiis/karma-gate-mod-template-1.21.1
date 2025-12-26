package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class WaterStreamBlock extends BlockWithEntity {
    public static final MapCodec<WaterStreamBlock> CODEC = createCodec(WaterStreamBlock::new);
    @Override public MapCodec<WaterStreamBlock> getCodec() { return CODEC; }

    public WaterStreamBlock(Settings settings) {
        // Visible hitbox so you can select/break it; no baked model
        super(settings.nonOpaque());
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.fullCube();
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
                ? (w, p, s, be) -> WaterStreamBlockEntity.tick(w, p, s, (WaterStreamBlockEntity) be)
                : null;
    }
}
