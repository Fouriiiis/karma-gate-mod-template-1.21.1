package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns all airlock timing/state, trigger areas, cooldowns, short reset delay,
 * and nearby gate lights (optional).
 * No new block: this is embedded by KarmaGateBlockEntity when that BE is a controller.
 */
public final class KarmaGateController {
    // --- geometry ---
    private static final double HALF_SIDE  = 6.5;
    private static final double OFFSET_POS = 5.0;   // Side 2 (+)  along controller normal axis
    private static final double OFFSET_NEG = -4.0;  // Side 1 (−)  along controller normal axis

    // --- timings (20 TPS) ---
    private static final int PREPARE_TICKS_MC     = 60;   // time standing in side area before cycle begins
    private static final int WASH_TICKS_MC        = 300;  // waiting period before opening controller/middle
    private static final int RESET_DELAY_TICKS_MC = 160;  // small delay between closing controller and opening outer gate
    private static final int COOLDOWN_TICKS_MC    = 500;  // lockout after a cycle completes

    // --- bound outer gates ---
    private BlockPos gate1 = null; // side 1 (NEG offset)
    private BlockPos gate2 = null; // side 2 (POS offset)

    // --- runtime state ---
    private int prepare1 = 0;
    private int prepare2 = 0;
    private int washTicks = 0;
    private int cooldownTicks = 0;
    private int lampBlink = 0; // drives light step timing

    private enum CycleSide { NONE, SIDE1, SIDE2 }
    private CycleSide cycleSide = CycleSide.NONE;

    private CycleSide pendingResetSide = CycleSide.NONE;
    private int resetDelayTicks = 0;

    // --- lights (optional): SIDE1 = NEG; SIDE2 = POS ---
    private final GateLightGroup lightsSide1 = new GateLightGroup(GateLightGroup.Side.SIDE1);
    private final GateLightGroup lightsSide2 = new GateLightGroup(GateLightGroup.Side.SIDE2);

    // parent
    private final KarmaGateBlockEntity controllerBE;

    public KarmaGateController(KarmaGateBlockEntity controllerBE) {
        this.controllerBE = controllerBE;
    }

    /* ===================== External API ===================== */

    public void setGates(BlockPos g1, BlockPos g2) {
        this.gate1 = g1;
        this.gate2 = g2;
    }

    /** Bind (or rebind) nearby lights based on controller orientation and position. */
    public void bindLights(World world, BlockPos pos, BlockState state, int radius) {
        // Rotate axis 90 degrees: swap X <-> Z
        Direction.Axis gateAxis = state.get(KarmaGateBlock.AXIS);
        Direction.Axis rotatedAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;
        lightsSide1.bindLights(world, pos, rotatedAxis, radius); // NEG side
        lightsSide2.bindLights(world, pos, rotatedAxis, radius); // POS side
        // Ensure both sides are off after a rebind
        lightsSide1.allOff(world);
        lightsSide2.allOff(world);
    }

    public void resetOnBind() {
        this.prepare1 = 0;
        this.prepare2 = 0;
        this.washTicks = 0;
        this.cooldownTicks = 0;
        this.cycleSide = CycleSide.NONE;
        this.pendingResetSide = CycleSide.NONE;
        this.resetDelayTicks = 0;
        this.lampBlink = 0;
        // lights default off; they’ll get re-bound by caller soon after
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world == null || world.isClient) return;

        lampBlink++; // advance light timing

        // Orientation -> normal axis based on the controller block state
        Direction.Axis gateAxis   = state.get(KarmaGateBlock.AXIS);
        Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        // Controller center
        double gx = pos.getX() + 0.5;
        double gz = pos.getZ() + 0.5;

        // Side 1 (NEG)
        double a1cx = gx, a1cz = gz;
        if (normalAxis == Direction.Axis.Z) a1cx = gx + OFFSET_NEG; else a1cz = gz + OFFSET_NEG;

        // Side 2 (POS)
        double a2cx = gx, a2cz = gz;
        if (normalAxis == Direction.Axis.Z) a2cx = gx + OFFSET_POS; else a2cz = gz + OFFSET_POS;

        boolean inArea1 = KarmaGateBlockEntity.anyPlayerInSquare(world, a1cx, a1cz, HALF_SIDE);
        boolean inArea2 = KarmaGateBlockEntity.anyPlayerInSquare(world, a2cx, a2cz, HALF_SIDE);

        // Global cooldown: while active, block prepares/cycles; lights off
        if (cooldownTicks > 0) {
            cooldownTicks--;
            prepare1 = prepare2 = 0;
            washTicks = 0;
            if (controllerBE.isOpen()) controllerBE.setOpen(false);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
            return;
        }

        // Pending reset delay (controller is closed; reopen the outer gate after short pause). Lights off.
        if (pendingResetSide != CycleSide.NONE) {
            if (controllerBE.isOpen()) controllerBE.setOpen(false);
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);

            if (resetDelayTicks > 0) {
                resetDelayTicks--;
                return;
            }
            reopenOuterGate(world, pendingResetSide);
            pendingResetSide = CycleSide.NONE;
            cooldownTicks = COOLDOWN_TICKS_MC;
            return;
        }

        switch (cycleSide) {
            case NONE -> {
                // If both sides occupied, do nothing and lights off
                if (inArea1 && inArea2) {
                    prepare1 = 0;
                    prepare2 = 0;
                    washTicks = 0;
                    if (controllerBE.isOpen()) controllerBE.setOpen(false);
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                    break;
                }

                // Count prepare time per side
                prepare1 = inArea1 ? Math.min(prepare1 + 1, PREPARE_TICKS_MC) : 0;
                prepare2 = inArea2 ? Math.min(prepare2 + 1, PREPARE_TICKS_MC) : 0;

                // Visual prep feedback: alternate blink bottom/top on the active side
                if (prepare1 > 0 && prepare2 == 0) {
                    lightsSide1.blinkBottomTopAlternate(world, lampBlink); // SIDE1 preparing
                    lightsSide2.allOff(world);
                } else if (prepare2 > 0 && prepare1 == 0) {
                    lightsSide2.blinkBottomTopAlternate(world, lampBlink); // SIDE2 preparing
                    lightsSide1.allOff(world);
                } else {
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                }

                if (prepare1 >= PREPARE_TICKS_MC) {
                    startClosingSideGate(world, CycleSide.SIDE1);
                } else if (prepare2 >= PREPARE_TICKS_MC) {
                    startClosingSideGate(world, CycleSide.SIDE2);
                } else {
                    if (controllerBE.isOpen()) controllerBE.setOpen(false);
                }
            }

            case SIDE1 -> {
                // lock out opposite side
                prepare2 = 0;

                // wash phase before opening controller – lights off
                if (!controllerBE.isOpen()) {
                    washTicks++;
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                    if (washTicks > WASH_TICKS_MC) {
                        controllerBE.setOpen(true);
                    }
                } else {
                    // MiddleOpen analogue: clockwise circular sequence S1B → S2B → S2T → S1T
                    chaseCircularWaitSequence(world);
                }

                // end cycle when side 1 area empty
                if (!inArea1) endCycle(CycleSide.SIDE1);
            }

            case SIDE2 -> {
                prepare1 = 0;

                if (!controllerBE.isOpen()) {
                    washTicks++;
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                    if (washTicks > WASH_TICKS_MC) {
                        controllerBE.setOpen(true);
                    }
                } else {
                    // MiddleOpen analogue: clockwise circular sequence S1B → S2B → S2T → S1T
                    chaseCircularWaitSequence(world);
                }

                if (!inArea2) endCycle(CycleSide.SIDE2);
            }
        }
    }

    /* ===================== Internals ===================== */

    private void startClosingSideGate(World world, CycleSide side) {
        if (side == CycleSide.SIDE1) {
            setGateOpen(world, gate1, false);
            prepare1 = 0;
            prepare2 = 0;
            cycleSide = CycleSide.SIDE1;
            washTicks = 0;
            controllerBE.setOpen(false);
            KarmaGateMod.LOGGER.info("Controller @{}: start SIDE1 cycle (close gate1, wash {} ticks)", controllerBE.getPos(), WASH_TICKS_MC);
        } else {
            setGateOpen(world, gate2, false);
            prepare1 = 0;
            prepare2 = 0;
            cycleSide = CycleSide.SIDE2;
            washTicks = 0;
            controllerBE.setOpen(false);
            KarmaGateMod.LOGGER.info("Controller @{}: start SIDE2 cycle (close gate2, wash {} ticks)", controllerBE.getPos(), WASH_TICKS_MC);
        }
    }

    private void endCycle(CycleSide side) {
        // Close controller, schedule outer reopen after reset delay
        controllerBE.setOpen(false);

        cycleSide = CycleSide.NONE;
        washTicks = 0;
        prepare1 = 0;
        prepare2 = 0;

        // Lights off as we enter reset delay
        World w = controllerBE.getWorld();
        if (w != null) {
            lightsSide1.allOff(w);
            lightsSide2.allOff(w);
        }

        pendingResetSide = side;
        resetDelayTicks = RESET_DELAY_TICKS_MC;

        if (side == CycleSide.SIDE1) {
            KarmaGateMod.LOGGER.info("Controller @{}: SIDE1 clear -> closing middle; wait {} ticks then open gate1",
                    controllerBE.getPos(), RESET_DELAY_TICKS_MC);
        } else {
            KarmaGateMod.LOGGER.info("Controller @{}: SIDE2 clear -> closing middle; wait {} ticks then open gate2",
                    controllerBE.getPos(), RESET_DELAY_TICKS_MC);
        }
        // cooldown starts in tick() after delay
    }

    private void reopenOuterGate(World world, CycleSide side) {
        if (side == CycleSide.SIDE1) {
            setGateOpen(world, gate1, true);
            KarmaGateMod.LOGGER.info("Controller @{}: reset delay done -> gate1 opened; cooldown {} ticks",
                    controllerBE.getPos(), COOLDOWN_TICKS_MC);
        } else if (side == CycleSide.SIDE2) {
            setGateOpen(world, gate2, true);
            KarmaGateMod.LOGGER.info("Controller @{}: reset delay done -> gate2 opened; cooldown {} ticks",
                    controllerBE.getPos(), COOLDOWN_TICKS_MC);
        }
    }

    private void setGateOpen(World world, BlockPos pos, boolean open) {
        if (world == null || pos == null) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof KarmaGateBlockEntity g) g.setOpen(open);
    }

    // ---------------- lights: clockwise circular sequence while middle is open ----------------

    /**
     * Runs the S1B → S2B → S2T → S1T chase, advancing every 10 ticks.
     * If not all positions exist, gracefully degrades to whatever is present.
     * If fewer than 2 lamps exist total, both sides blinkAll as a fallback.
     */
    private void chaseCircularWaitSequence(World world) {
        // Build circular order from the two groups:
        // S1 bottom, S2 bottom, S2 top, S1 top
        List<GateLightGroup.LightRef> order = new ArrayList<>(4);
        GateLightGroup.LightRef s1b = lightsSide1.bottom();
        GateLightGroup.LightRef s1t = lightsSide1.top();
        GateLightGroup.LightRef s2b = lightsSide2.bottom();
        GateLightGroup.LightRef s2t = lightsSide2.top();

        if (s1b != null) order.add(s1b);
        if (s2b != null) order.add(s2b);
        if (s2t != null) order.add(s2t);
        if (s1t != null) order.add(s1t);

        if (order.size() < 2) {
            // Not enough distinct lamps for a chase — just blink everything
            lightsSide1.blinkAll(world, lampBlink);
            lightsSide2.blinkAll(world, lampBlink);
            return;
        }

        // Four 10-tick windows per cycle: 0–9, 10–19, 20–29, 30–39
        int step = (lampBlink / 10) % order.size();

        // Turn all off first
        lightsSide1.allOff(world);
        lightsSide2.allOff(world);

        // Light just the current one
        BlockPos pos = order.get(step).pos;
        setLamp(world, pos, true);
    }

    private void setLamp(World world, BlockPos pos, boolean on) {
        if (!(world instanceof net.minecraft.server.world.ServerWorld sw)) return;
        BlockEntity be = sw.getBlockEntity(pos);
        if (be instanceof GateLightBlockEntity lamp) lamp.setLit(on);
    }

    /* ===================== NBT ===================== */

    public void writeNbt(NbtCompound nbt) {
        // gates
        if (gate1 != null) {
            NbtCompound g1 = new NbtCompound();
            g1.putInt("x", gate1.getX());
            g1.putInt("y", gate1.getY());
            g1.putInt("z", gate1.getZ());
            nbt.put("gate1", g1);
        }
        if (gate2 != null) {
            NbtCompound g2 = new NbtCompound();
            g2.putInt("x", gate2.getX());
            g2.putInt("y", gate2.getY());
            g2.putInt("z", gate2.getZ());
            nbt.put("gate2", g2);
        }

        // timers/state
        nbt.putInt("prepare1", prepare1);
        nbt.putInt("prepare2", prepare2);
        nbt.putInt("washTicks", washTicks);
        nbt.putInt("cooldownTicks", cooldownTicks);
        nbt.putInt("lampBlink", lampBlink);
        nbt.putString("cycleSide", cycleSide.name());
        nbt.putString("pendingResetSide", pendingResetSide.name());
        nbt.putInt("resetDelayTicks", resetDelayTicks);
    }

    public void readNbt(NbtCompound nbt) {
        gate1 = nbt.contains("gate1") ? new BlockPos(
                nbt.getCompound("gate1").getInt("x"),
                nbt.getCompound("gate1").getInt("y"),
                nbt.getCompound("gate1").getInt("z")) : null;

        gate2 = nbt.contains("gate2") ? new BlockPos(
                nbt.getCompound("gate2").getInt("x"),
                nbt.getCompound("gate2").getInt("y"),
                nbt.getCompound("gate2").getInt("z")) : null;

        prepare1 = nbt.getInt("prepare1");
        prepare2 = nbt.getInt("prepare2");
        washTicks = nbt.getInt("washTicks");
        cooldownTicks = nbt.getInt("cooldownTicks");
        lampBlink = nbt.getInt("lampBlink");
        try { cycleSide = CycleSide.valueOf(nbt.getString("cycleSide")); }
        catch (IllegalArgumentException e) { cycleSide = CycleSide.NONE; }
        try { pendingResetSide = CycleSide.valueOf(nbt.getString("pendingResetSide")); }
        catch (IllegalArgumentException e) { pendingResetSide = CycleSide.NONE; }
        resetDelayTicks = nbt.getInt("resetDelayTicks");
    }
}
