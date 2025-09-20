package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public class HeatCoilBlockEntity extends BlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private float heat = 0f; // 0..1

    public HeatCoilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, pos, state);
    }

    /* ================= public API ================= */

    public void addHeat(float delta) {
        setHeat(this.heat + delta);
    }

    public float getHeat() {
        return heat;
    }

    public void setHeat(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        if (clamped == this.heat) return;
        this.heat = clamped;
        markDirtySync();
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) return;

        // Optional: slow passive cooling
        if (heat > 0f) {
            float cooled = Math.max(0f, heat - 0.0025f);
            if (cooled != heat) setHeat(cooled);
        }
    }

    /* ================= GeckoLib ================= */

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar registrar) { /* none needed */ }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    /* ================= sync & NBT ================= */

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("heat", heat);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.heat = nbt.getFloat("heat");
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
