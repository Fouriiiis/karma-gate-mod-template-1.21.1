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
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class HologramProjectorBlock extends BlockWithEntity {
    public HologramProjectorBlock(Settings settings) { super(settings); }

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

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new HologramProjectorBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient && type == ModBlockEntities.HOLOGRAM_PROJECTOR
                ? (w, p, s, be) -> HologramProjectorBlockEntity.tick(w, p, s, (HologramProjectorBlockEntity) be)
                : null;
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
