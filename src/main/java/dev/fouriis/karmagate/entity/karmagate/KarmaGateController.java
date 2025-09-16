package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Owns all airlock timing/state, trigger areas, cooldowns, and short reset delay.
 * No new block: this is embedded by KarmaGateBlockEntity when that BE is a controller.
 */
public final class KarmaGateController {
    // --- geometry ---
    private static final double HALF_SIDE  = 6.5;
    private static final double OFFSET_POS = 5.0;   // Side 2 (+)
    private static final double OFFSET_NEG = -4.0;  // Side 1 (−)

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

    private enum CycleSide { NONE, SIDE1, SIDE2 }
    private CycleSide cycleSide = CycleSide.NONE;

    private CycleSide pendingResetSide = CycleSide.NONE;
    private int resetDelayTicks = 0;

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

    public void resetOnBind() {
        this.prepare1 = 0;
        this.prepare2 = 0;
        this.washTicks = 0;
        this.cooldownTicks = 0;
        this.cycleSide = CycleSide.NONE;
        this.pendingResetSide = CycleSide.NONE;
        this.resetDelayTicks = 0;
    }

    public void tick(World world, BlockPos pos, BlockState state) {
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

        // Global cooldown: while active, block prepares/cycles
        if (cooldownTicks > 0) {
            cooldownTicks--;
            prepare1 = prepare2 = 0;
            washTicks = 0;
            if (controllerBE.isOpen()) controllerBE.setOpen(false);
            return;
        }

        // Pending reset delay (controller is closed; reopen the outer gate after short pause)
        if (pendingResetSide != CycleSide.NONE) {
            if (controllerBE.isOpen()) controllerBE.setOpen(false);
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
                // If both sides occupied, do nothing (RW PlayersInZone => -1)
                if (inArea1 && inArea2) {
                    prepare1 = 0;
                    prepare2 = 0;
                    washTicks = 0;
                    if (controllerBE.isOpen()) controllerBE.setOpen(false);
                    break;
                }

                // Count prepare time per side
                prepare1 = inArea1 ? Math.min(prepare1 + 1, PREPARE_TICKS_MC) : 0;
                prepare2 = inArea2 ? Math.min(prepare2 + 1, PREPARE_TICKS_MC) : 0;

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

                // wash phase before opening controller
                if (!controllerBE.isOpen()) {
                    washTicks++;
                    if (washTicks > WASH_TICKS_MC) {
                        controllerBE.setOpen(true);
                    }
                }

                // end cycle when side 1 area empty
                if (!inArea1) {
                    endCycle(CycleSide.SIDE1);
                }
            }

            case SIDE2 -> {
                prepare1 = 0;

                if (!controllerBE.isOpen()) {
                    washTicks++;
                    if (washTicks > WASH_TICKS_MC) {
                        controllerBE.setOpen(true);
                    }
                }

                if (!inArea2) {
                    endCycle(CycleSide.SIDE2);
                }
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
        if (be instanceof KarmaGateBlockEntity g) {
            g.setOpen(open);
        }
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
        try {
            cycleSide = CycleSide.valueOf(nbt.getString("cycleSide"));
        } catch (IllegalArgumentException e) {
            cycleSide = CycleSide.NONE;
        }
        try {
            pendingResetSide = CycleSide.valueOf(nbt.getString("pendingResetSide"));
        } catch (IllegalArgumentException e) {
            pendingResetSide = CycleSide.NONE;
        }
        resetDelayTicks = nbt.getInt("resetDelayTicks");
    }
}
