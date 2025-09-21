package dev.fouriis.karmagate.entity.karmagate;

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

    // server-authoritative heat [0..1]
    private float heat = 0f;

    // heater toggle (server)
    private boolean enabled = false;

    // all external/additional contributions for the *current* server tick
    private float pendingDelta = 0f;

    // rates per tick
    private static final float HEAT_RATE_ON     = 0.015f;  // tripled as you asked earlier
    private static final float PASSIVE_COOL_RATE= 0.0025f; // when not enabled
    private static final float EPS              = 0.0001f; // change threshold to sync

    public HeatCoilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, pos, state);
    }

    /* ================= public API (SERVER) ================= */

    /** Enqueue a heat contribution to be applied this tick (positive heats, negative cools). */
    public void addHeat(float delta) {
        if (world != null && world.isClient) return; // server-authoritative
        pendingDelta += delta;
    }

    /** Convenience: remove heat due to steam generation, etc. */
    public void drainHeat(float amount) {
        if (amount <= 0f) return;
        addHeat(-amount);
    }

    /** Toggle the built-in heater on/off. */
    public void setEnabled(boolean on) {
        if (world != null && world.isClient) return; // server only
        this.enabled = on;
        // no immediate sync needed; heat itself will sync when it changes
    }

    public boolean isEnabled() { return enabled; }

    /** Server-authoritative heat (client will read the synced value). */
    public float getHeat() { return heat; }

    /* ================= ticking ================= */

    /** Server tick: accumulate all contributions and apply once. */
    public void tick(World world, BlockPos pos, BlockState state) {
        if (world.isClient) return;

        float delta = pendingDelta;
        pendingDelta = 0f; // consume for this tick

        if (enabled) {
            delta += HEAT_RATE_ON;
        } else {
            // passive cooling only when we have heat to shed
            if (heat > 0f) delta -= PASSIVE_COOL_RATE;
        }

        float newHeat = clamp01(heat + delta);
        if (Math.abs(newHeat - heat) > EPS) {
            heat = newHeat;
            markDirtySync();
        }
    }

    /* ================= GeckoLib ================= */

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar registrar) { /* none */ }
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
        nbt.putBoolean("enabled", enabled);
        // pendingDelta is transient per tick and not persisted
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.heat = nbt.getFloat("heat");
        this.enabled = nbt.getBoolean("enabled");
        this.pendingDelta = 0f;
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    /* ================= util ================= */

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : (v > 1f ? 1f : v);
    }
}
