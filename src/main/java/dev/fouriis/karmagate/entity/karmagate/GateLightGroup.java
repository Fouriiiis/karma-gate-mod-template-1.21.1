package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds, orders, and animates nearby GateLightBlockEntity lamps for a single side of a gate.
 * Side mapping:
 *  - SIDE1 = controller normal-axis NEGATIVE (x/z < center)
 *  - SIDE2 = controller normal-axis POSITIVE (x/z > center)
 * Ordering: within a side, lights are sorted by vertical position (bottom -> top).
 */
public class GateLightGroup {

    public enum Side { SIDE1, SIDE2 }

    /** Immutable reference to a light with ordering info. */
    public static final class LightRef {
        public final BlockPos pos;
        public final double y;
        public LightRef(BlockPos pos) {
            this.pos = pos.toImmutable();
            this.y = pos.getY();
        }
    }

    private final Side side;
    private final List<LightRef> lights = new ArrayList<>();

    public GateLightGroup(Side side) { this.side = side; }
    public Side side() { return side; }

    /** Clears and (re)binds this side's lights relative to a controller/gate. */
    public void bindLights(World world, BlockPos gateCenter, Direction.Axis gateAxis, int radius) {
        lights.clear();
        if (world == null) return;

        // Normal axis is perpendicular to the gate axis
        Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        BlockPos min = gateCenter.add(-radius, -radius, -radius);
        BlockPos max = gateCenter.add( radius,  radius,  radius);
        BlockPos.Mutable m = new BlockPos.Mutable();

        final double cx = gateCenter.getX() + 0.5;
        final double cz = gateCenter.getZ() + 0.5;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    m.set(x, y, z);
                    BlockEntity be = world.getBlockEntity(m);
                    if (!(be instanceof GateLightBlockEntity)) continue;

                    // sign along controller's normal axis
                    double delta = (normalAxis == Direction.Axis.X) ? (x - cx) : (z - cz);

                    // Assign to SIDE1 (NEG) or SIDE2 (POS)
                    Side computed = (delta < 0) ? Side.SIDE1 : Side.SIDE2;
                    if (computed != this.side) continue;

                    lights.add(new LightRef(m.toImmutable()));
                }
            }
        }

        // Order by vertical (bottom -> top)
        lights.sort(Comparator.comparingDouble(l -> l.y));
        KarmaGateMod.LOGGER.info("GateLightGroup {} bound {} lights near {}", side, lights.size(), gateCenter);
    }

    /** Unmodifiable ordered refs (bottom -> top). */
    public List<LightRef> getRefs() {
        return Collections.unmodifiableList(lights);
    }

    /** Bottom lamp (or null). */
    public LightRef bottom() {
        return lights.isEmpty() ? null : lights.get(0);
    }

    /** Top lamp (or null). */
    public LightRef top() {
        return lights.isEmpty() ? null : lights.get(lights.size() - 1);
    }

    /** Force all lights off (e.g., on cooldown/end of cycle). */
    public void allOff(World world) {
        setAll(world, false);
    }

    /** Blink all lights on this side together at 10-tick cadence. */
    public void blinkAll(World world, int tick) {
        boolean on = (tick % 20) < 10; // 10 ticks on, 10 off
        for (LightRef r : lights) setOne(world, r.pos, on);
    }

    /** Alternate bottom vs top every 10 ticks while preparing in MiddleClosed. */
    public void blinkBottomTopAlternate(World world, int tick) {
        if (lights.isEmpty()) return;

        boolean bottomOn = (tick % 20) < 10;

        for (int i = 0; i < lights.size(); i++) {
            boolean on;
            if (lights.size() == 1) {
                on = bottomOn;
            } else if (i == 0) {
                on = bottomOn;               // bottom
            } else if (i == lights.size() - 1) {
                on = !bottomOn;              // top
            } else {
                on = false;                  // middle lamps off
            }
            setOne(world, lights.get(i).pos, on);
        }
    }

    // ---------------- internals ----------------

    private void setAll(World world, boolean lit) {
        if (!(world instanceof ServerWorld sw)) return;
        for (LightRef ref : lights) {
            BlockEntity be = sw.getBlockEntity(ref.pos);
            if (be instanceof GateLightBlockEntity lamp) lamp.setLit(lit);
        }
    }

    private void setOne(World world, BlockPos pos, boolean lit) {
        if (!(world instanceof ServerWorld sw)) return;
        BlockEntity be = sw.getBlockEntity(pos);
        if (be instanceof GateLightBlockEntity lamp) lamp.setLit(lit);
    }
}
