// dev/fouriis/karmagate/entity/hologram/HologramProjectorBlockEntity.java
package dev.fouriis.karmagate.entity.hologram;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateController;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateController.KarmaLevel;
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
    // server-authoritative selected symbol (0..5 plus D mapped to 6)
    // 0..5 => gateSymbol0.png..gateSymbol5.png, 6 => gateSymbolD.png
    private int symbolIdx = 0;
    // derived key used by the client renderer (computed from karma/symbol)
    private String symbolKey = keyFor(KarmaLevel.LEVEL_0);

    private float glow = 1f;       // 0..1
    private float flicker = 0f;    // 0..1
    private float staticLevel = 0f;// 0..1
    private float targetLevel = 0.5f;// 0..1

    // Authoritative enum (no raw float/int for karma kept as state)
    private KarmaLevel karmaLevel = KarmaLevel.LEVEL_0;

    // base RGB tint for the hologram (default bluish 0x59CCFF), alpha driven by renderer logic
    private int colorRGB = 0x59CCFF;
    // red for low power mode
    private int lowPowerRGB = 0xFF0000;
    private boolean lowPower = false;
    private KarmaGateController controller = null;
    private BlockPos pendingControllerPos = null; // stored controller position to resolve after world load

    public HologramProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOLOGRAM_PROJECTOR, pos, state);
    }

    private static String keyFor(KarmaLevel k) {
        if (k == KarmaLevel.LEVEL_D) return "gateSymbolD.png";
        return "gateSymbol" + Math.max(0, Math.min(5, k.getIndex())) + ".png";
    }

    private void setSymbolFromKarma(KarmaLevel lvl) {
        int idx = (lvl == KarmaLevel.LEVEL_D) ? 6 : Math.max(0, Math.min(5, lvl.getIndex()));
        this.symbolIdx = idx;
        this.symbolKey = keyFor(lvl);
    }

    // server-side: advance 1->5 and wrap (D mapped from 6)
    public void cycleSymbol() {
        this.symbolIdx = (this.symbolIdx + 1) % 7;
        KarmaLevel lvl = KarmaLevel.fromIndex(this.symbolIdx);
        this.symbolKey = keyFor(lvl);
        if (controller != null) {
            controller.setKarma(this.pos, lvl); // will mirror to both sides
        } else {
            // No controller: update local karmaLevel and visuals anyway
            this.karmaLevel = lvl;
            setSymbolFromKarma(lvl);
        }
        markDirtySync();
        System.out.println("HologramProjectorBlockEntity: cycleSymbol to " + this.symbolIdx + " (" + this.symbolKey + ")");
    }

    // client: used by renderer
    public void setSymbolKey(String key) { this.symbolKey = key; }
    public String getSymbolKey() { return symbolKey; }
    public int getSymbolIndex() { return symbolIdx; }

    public float getGlow() { return glow; }
    public float getFlicker() { return flicker; }

    public float getStaticLevel() { return staticLevel; }
    public void setStaticLevel(float v) {
        this.staticLevel = Math.max(0f, Math.min(1f, v));
        // Intentionally NOT syncing: staticLevel is now a client-only visual interpolation value.
        // Avoid markDirtySync here to prevent server from sending stale 0 back and snapping animation.
        markDirtySync();
    }

    /** Authoritative enum accessor. */
    public KarmaGateController.KarmaLevel getKarmaLevelEnum() { return karmaLevel; }

    /** Enum setter also updates symbolIdx/symbolKey to keep visuals in sync. */
    public void setKarmaLevelEnum(KarmaGateController.KarmaLevel lvl) {
        if (lvl != null && lvl != this.karmaLevel) {
            this.karmaLevel = lvl;
            setSymbolFromKarma(lvl);
            markDirtySync();
        }
    }

    /** Convenience alias (kept for readability at call sites). */
    public void setKarmaLevel(KarmaLevel lvl) { setKarmaLevelEnum(lvl); }

    /** Derived numeric view (if shaders/UI still need it). */
    @Deprecated
    public float getKarmaLevelValue() { return karmaLevel.asFloat(); }

    // either return colorRGB or colorRGB depending on low power mode
    public int getColorRGB() {
        return lowPower ? lowPowerRGB : colorRGB;
    }

    /**
     * Visual display color (client-only). If not in low power mode, returns the current normal color.
     * In low power mode, smoothly alternates between the normal color and low-power color using a
     * triangle wave over a 2-second period (1 second fade each direction).
     * @param tickDelta partial ticks for smooth interpolation
     */
    public int getDisplayColor(float tickDelta) {
        if (!lowPower || world == null) return getColorRGB();

    // time in seconds (bounded). We only need the phase within a 2-second cycle because
    // alpha uses cos(pi * t) which itself has a 2s period at 20 TPS (pi * (t+2) = pi*t + 2pi).
    // Using modulo keeps the cosine argument small and avoids precision issues when
    // world.getTime() reaches very large values (hundreds of millions of ticks on long uptime servers).
    long cycleTicks = world.getTime() % 40L; // 40 ticks = 2 seconds at 20 TPS
    double tSec = (cycleTicks + tickDelta) / 20.0; // 0.0 <= tSec < 2.0

        // Cosine-based smooth triangle (0 -> 1 -> 0) with a 2s period:
        // alpha = 0.5 * (1 - cos(pi * t))  => period 2 because cos(pi*(t+2)) = cos(pi*t)
    float alpha = 0.5f * (1.0f - (float)Math.cos(Math.PI * tSec));

        int r1 = (colorRGB >> 16) & 0xFF;
        int g1 = (colorRGB >>  8) & 0xFF;
        int b1 =  colorRGB        & 0xFF;

        int r2 = (lowPowerRGB >> 16) & 0xFF;
        int g2 = (lowPowerRGB >>  8) & 0xFF;
        int b2 =  lowPowerRGB        & 0xFF;

        int r = (int)(r1 * (1 - alpha) + r2 * alpha);
        int g = (int)(g1 * (1 - alpha) + g2 * alpha);
        int b = (int)(b1 * (1 - alpha) + b2 * alpha);

        return (r << 16) | (g << 8) | b;
    }

    /** Set hologram color as 0xRRGGBB; server-side authoritative; syncs to clients. */
    public void setColorRGB(int rgb) {
        if (world != null && world.isClient) return;
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

            // Adjust staticLevel towards targetLevel by 0.01 per tick,
            // BUT if the next value would leave [0,1], clamp to the nearest endpoint (0 or 1)
            float step = 0.01f;
            float sLvl = be.staticLevel;
            float tLvl = be.targetLevel;
            if (tLvl > sLvl) {
                float next = sLvl + step;
                if (next < 0f || next > 1f) {
                    be.staticLevel = (next <= 0.5f) ? 0f : 1f; // closest bound
                } else {
                    be.staticLevel = Math.min(next, tLvl);     // avoid overshoot past target
                }
            } else if (tLvl < sLvl) {
                float next = sLvl - step;
                if (next < 0f || next > 1f) {
                    be.staticLevel = (next <= 0.5f) ? 0f : 1f; // closest bound
                } else {
                    be.staticLevel = Math.max(next, tLvl);     // avoid overshoot past target
                }
            }
        }
        // Server-side lazy controller resolution
        if (!w.isClient && be.controller == null && be.pendingControllerPos != null) {
            BlockEntity maybe = w.getBlockEntity(be.pendingControllerPos);
            if (maybe instanceof KarmaGateBlockEntity kbe) {
                be.controller = kbe.getController();
                if (be.controller != null) {
                    be.pendingControllerPos = null; // resolved
                }
            }
        }
    }

    public void setTargetLevel(float v) {
        this.targetLevel = Math.max(0f, Math.min(1f, v));
        //System.out.println("HologramProjectorBlockEntity: setTargetLevel " + this.targetLevel);
        markDirtySync();
    }

    public void setLowpower(boolean lowPower) {
        // Do NOT overwrite base color; we blend between existing base (colorRGB) and lowPowerRGB in getDisplayColor.
        boolean changed = (this.lowPower != lowPower);
        this.lowPower = lowPower;
        if (changed) {
            System.out.println("HologramProjectorBlockEntity: setLowpower " + this.lowPower);
            markDirtySync(); // sync the flag so clients update pulsing
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
        // staticLevel no longer persisted; it is purely a client interpolated value derived from targetLevel.
        // Persist targetLevel so server syncs don't reset client interpolation target.
        nbt.putFloat("targetLevel", targetLevel);
        nbt.putString("karmaLevel", karmaLevel.name());
        nbt.putInt("colorRGB", colorRGB);
        nbt.putBoolean("lowPower", lowPower); // ensure client knows to pulse
        nbt.putInt("lowPowerRGB", lowPowerRGB); // in case this is customized later
        // put controller position
        if (controller != null) {
            BlockPos ctrlPos = controller.getPos();
            nbt.putInt("controllerX", ctrlPos.getX());
            nbt.putInt("controllerY", ctrlPos.getY());
            nbt.putInt("controllerZ", ctrlPos.getZ());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        // Load saved symbol (kept for back-compat), then override from karma (authoritative)
        this.symbolIdx = Math.max(0, Math.min(6, nbt.getInt("symbolIdx")));
        this.symbolKey = keyFor(KarmaLevel.fromIndex(this.symbolIdx));

        // staticLevel intentionally not read (client will rebuild towards targetLevel each session)
        if (nbt.contains("targetLevel")) {
            this.targetLevel = Math.max(0f, Math.min(1f, nbt.getFloat("targetLevel")));
        }

        if (nbt.contains("karmaLevel")) {
            if (nbt.get("karmaLevel") instanceof net.minecraft.nbt.NbtString) {
                try {
                    this.karmaLevel = KarmaGateController.KarmaLevel.valueOf(nbt.getString("karmaLevel"));
                } catch (IllegalArgumentException e) {
                    this.karmaLevel = KarmaGateController.KarmaLevel.LEVEL_0;
                }
            } else {
                // Back-compat: if some old world stored a float, map it
                this.karmaLevel = KarmaGateController.KarmaLevel.fromFloat(nbt.getFloat("karmaLevel"));
            }
        }

        // Ensure visuals match authoritative karma
        setSymbolFromKarma(this.karmaLevel);

        if (nbt.contains("colorRGB")) this.colorRGB = nbt.getInt("colorRGB") & 0xFFFFFF;
        if (nbt.contains("lowPower")) this.lowPower = nbt.getBoolean("lowPower");
        if (nbt.contains("lowPowerRGB")) this.lowPowerRGB = nbt.getInt("lowPowerRGB") & 0xFFFFFF;
        // read controller position and link (if possible)
        if (nbt.contains("controllerX") && nbt.contains("controllerY") && nbt.contains("controllerZ")) {
            BlockPos ctrlPos = new BlockPos(nbt.getInt("controllerX"), nbt.getInt("controllerY"), nbt.getInt("controllerZ"));
            if (!ctrlPos.equals(BlockPos.ORIGIN)) {
                // Try immediate resolution if world is present, otherwise defer
                if (world != null) {
                    BlockEntity cbe = world.getBlockEntity(ctrlPos);
                    if (cbe instanceof KarmaGateBlockEntity kbe) {
                        this.controller = kbe.getController();
                        if (this.controller == null) this.pendingControllerPos = ctrlPos; // controller not ready yet
                    } else {
                        this.pendingControllerPos = ctrlPos; // will retry later
                    }
                } else {
                    this.pendingControllerPos = ctrlPos;
                }
            }
        }
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) { return createNbt(lookup); }
    @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }

    public void bindController(KarmaGateController karmaGateController) {
        this.controller = karmaGateController;
        if (karmaGateController != null) this.pendingControllerPos = null;
    }
}
