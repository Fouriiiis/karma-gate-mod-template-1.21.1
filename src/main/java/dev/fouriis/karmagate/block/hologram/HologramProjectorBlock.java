// dev/fouriis/karmagate/block/hologram/HologramProjectorBlock.java
package dev.fouriis.karmagate.block.hologram;

import com.mojang.serialization.MapCodec;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class HologramProjectorBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public HologramProjectorBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.SOUTH));
    }

    public static final MapCodec<HologramProjectorBlock> CODEC = createCodec(HologramProjectorBlock::new);

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        // Render exclusively via BER
        return BlockRenderType.INVISIBLE;
    }

    // Placement & rotation -------------------------------------------------
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Face the player (front toward player): use horizontal player facing
        Direction dir;
        try {
            // 1.21 method name (if present)
            dir = (Direction) ItemPlacementContext.class.getMethod("getHorizontalPlayerFacing").invoke(ctx);
        } catch (Exception reflectionIgnored) {
            // Fallback to player's horizontal facing
            dir = ctx.getPlayer() != null ? ctx.getPlayer().getHorizontalFacing() : Direction.SOUTH;
        }
        return this.getDefaultState().with(FACING, dir.getOpposite());
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new HologramProjectorBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.HOLOGRAM_PROJECTOR) {
            // Need ticker on BOTH sides: client (animation) + server (lazy controller resolution)
            return (w, p, s, be) -> HologramProjectorBlockEntity.tick(w, p, s, (HologramProjectorBlockEntity) be);
        }
        return null;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof HologramProjectorBlockEntity hp) {
            if (player.isInSneakingPose()) {
                float cur = hp.getStaticLevel();
                float next = (cur >= 0.995f) ? 0f : Math.min(1f, cur + 0.05f);
                hp.setStaticLevel(next);
            } else {
                hp.cycleSymbol();
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
}
