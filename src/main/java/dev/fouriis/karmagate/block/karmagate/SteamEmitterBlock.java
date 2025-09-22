// dev/fouriis/karmagate/block/SteamEmitterBlock.java
package dev.fouriis.karmagate.block.karmagate;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
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
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import dev.fouriis.karmagate.entity.karmagate.SteamEmitterBlockEntity;
public class SteamEmitterBlock extends Block implements BlockEntityProvider {
    public static final BooleanProperty ENABLED = BooleanProperty.of("enabled");

    public SteamEmitterBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(ENABLED, false));
    }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(ENABLED);
    }

    @Nullable @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SteamEmitterBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(ENABLED, false);
    }

    // simple toggle on right-click
    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        boolean newEnabled = !state.get(ENABLED);
        world.setBlockState(pos, state.with(ENABLED, newEnabled), Block.NOTIFY_ALL);

        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SteamEmitterBlockEntity emitter) {
            System.out.println("SteamEmitterBlock: toggled to " + newEnabled);
            emitter.setEnabled(newEnabled);
        }
        return ActionResult.CONSUME;
    }

    // hook BE tick
    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block block,
                               BlockPos fromPos, boolean notify) {
        // if you want redstone control, mirror redstone power into ENABLED here
    }

    // tick BE
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return type == dev.fouriis.karmagate.entity.ModBlockEntities.STEAM_EMITTER_BLOCK_ENTITY
                ? (w, p, s, be) -> SteamEmitterBlockEntity.tick(w, p, s, (SteamEmitterBlockEntity) be)
                : null;
    }
}
