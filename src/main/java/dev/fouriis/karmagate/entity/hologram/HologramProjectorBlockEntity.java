// dev/fouriis/karmagate/entity/hologram/HologramProjectorBlockEntity.java
package dev.fouriis.karmagate.entity.hologram;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class HologramProjectorBlockEntity extends BlockEntity {
    // server-authoritative selected symbol (1..5)
    private int symbolIdx = 1;
    // derived key used by the client renderer (computed from idx)
    private String symbolKey = keyFor(1);
    private float glow = 1f;     // 0..1
    private float flicker = 0f;  // 0..1
    private float staticLevel = 0f; // 0..1
    // base RGB tint for the hologram (default bluish 0x59CCFF), alpha driven by renderer logic
    private int colorRGB = 0x59CCFF;

    public HologramProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOLOGRAM_PROJECTOR, pos, state);
    }

    private static String keyFor(int idx) { return "gateSymbol" + idx + ".png"; }

    // server-side: set the index and sync
    public void setSymbolIndex(int idx) {
        if (world != null && world.isClient) return;
        int clamped = Math.max(1, Math.min(5, idx));
        if (clamped != this.symbolIdx) {
            this.symbolIdx = clamped;
            this.symbolKey = keyFor(clamped);
            markDirtySync();
        }
    }

    // server-side: advance 1->5 and wrap
    public void cycleSymbol() { setSymbolIndex(this.symbolIdx % 5 + 1); }

    // client: used by renderer
    public void setSymbolKey(String key) { this.symbolKey = key; }
    public String getSymbolKey() { return symbolKey; }
    public int getSymbolIndex() { return symbolIdx; }

    public float getGlow() { return glow; }
    public float getFlicker() { return flicker; }
    public float getStaticLevel() { return staticLevel; }
    public void setStaticLevel(float v) { this.staticLevel = Math.max(0f, Math.min(1f, v)); markDirtySync(); }
    public int getColorRGB() { return colorRGB; }
    /** Set hologram color as 0xRRGGBB; server-side authoritative; syncs to clients. */
    public void setColorRGB(int rgb) {
        if (world != null && world.isClient) return;
        // mask to 24-bit
        int val = rgb & 0xFFFFFF;
        if (val != this.colorRGB) {
            this.colorRGB = val;
            markDirtySync();
        }
    }

    // simple flicker / scanline driver
    public static void tick(net.minecraft.world.World w, BlockPos p, BlockState s, HologramProjectorBlockEntity be) {
        if (w.isClient) {
            be.flicker *= 0.9f;
            if (w.getRandom().nextFloat() < 1f / 120f) {
                be.flicker = Math.max(be.flicker, w.getRandom().nextFloat()); // occasional pop
            }
            be.glow = 0.8f + 0.2f * (float)Math.sin(w.getTime() * 0.12);
        }
    }

    /* ================= sync & NBT ================= */
    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putInt("symbolIdx", symbolIdx);
        nbt.putFloat("staticLevel", staticLevel);
        nbt.putInt("colorRGB", colorRGB);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.symbolIdx = Math.max(1, Math.min(5, nbt.getInt("symbolIdx")));
        this.symbolKey = keyFor(this.symbolIdx);
        this.staticLevel = Math.max(0f, Math.min(1f, nbt.getFloat("staticLevel")));
        if (nbt.contains("colorRGB")) this.colorRGB = nbt.getInt("colorRGB") & 0xFFFFFF;
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
}
