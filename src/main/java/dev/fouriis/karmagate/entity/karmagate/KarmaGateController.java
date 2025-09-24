// dev/fouriis/karmagate/entity/karmagate/KarmaGateController.java
package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.block.karmagate.SteamEmitterBlock;
import dev.fouriis.karmagate.block.karmagate.WaterStreamBlock;
import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Airlock controller simplified into RW-like Mode states.
 * Binds lights + effect blocks (water/heat/steam) and holograms.
 */
public final class KarmaGateController {

    /* ===================== Modes ===================== */
    private enum Mode {
        MiddleClosed,
        ClosingAirLock,
        Waiting,
        OpeningMiddle,
        MiddleOpen,
        ClosingMiddle,
        OpeningSide,
        Closed,
        Broken
    }
    private enum Side { SIDE1, SIDE2 }

    // Enum for karma levels 0-5 + D
    public enum KarmaLevel {
        LEVEL_0(0),
        LEVEL_1(1),
        LEVEL_2(2),
        LEVEL_3(3),
        LEVEL_4(4),
        LEVEL_5(5),
        LEVEL_D(-1);

        private final int index;

        KarmaLevel(int index) { this.index = index; }

        public int getIndex() { return index; }

        /** Representative normalized (0..1) value; LEVEL_D maps to 1.0f for now (treat like special high). */
        public float asFloat() { return index < 0 ? 1.0f : (index / 5.0f); }

        /** Map a normalized float (0..1) to the nearest discrete karma tier (exclusive of LEVEL_D). */
        public static KarmaLevel fromFloat(float v) {
            float c = Math.max(0f, Math.min(1f, v));
            int bucket = (int)Math.floor(c * 6f); // 0..5 (since c==1 gives 6 -> clamp to 5)
            if (bucket > 5) bucket = 5;
            return switch (bucket) {
                case 0 -> LEVEL_0;
                case 1 -> LEVEL_1;
                case 2 -> LEVEL_2;
                case 3 -> LEVEL_3;
                case 4 -> LEVEL_4;
                case 5 -> LEVEL_5;
                default -> LEVEL_0;
            };
        }

        /** Helper for symbol index mapping: 0..5 => levels, 6 => LEVEL_D (clamped otherwise). */
        public static KarmaLevel fromIndex(int idx) {
            // Support both legacy 6 sentinel AND direct -1 input for LEVEL_D so callers can
            // naturally cycle -1..5 without remapping.
            if (idx == -1 || idx == 6) return LEVEL_D;
            int k = Math.max(0, Math.min(5, idx));
            return switch (k) {
                case 0 -> LEVEL_0;
                case 1 -> LEVEL_1;
                case 2 -> LEVEL_2;
                case 3 -> LEVEL_3;
                case 4 -> LEVEL_4;
                case 5 -> LEVEL_5;
                default -> LEVEL_0;
            };
        }
    }

    /* ===================== Geometry ===================== */
    private static final double HALF_SIDE  = 6.5;
    private static final double OFFSET_POS = 5.0;   // Side 2 (+) along controller normal
    private static final double OFFSET_NEG = -4.0;  // Side 1 (−) along controller normal

    /* ===================== Timings (20 TPS) ===================== */
    private static final int PREPARE_TICKS_MC          = 60;
    private static final int WASH_TICKS_MC             = 100;
    private static final int COOLDOWN_TICKS_MC         = 600;

    private static final int GATE_ANIMATION_OPEN_TICKS  = 160;
    private static final int GATE_ANIMATION_CLOSE_TICKS = 160;

    private static final int CIRCULAR_STEP_TICKS = 6; // for wait light chase

    /* ===================== Bound outer gates ===================== */
    private BlockPos gate1 = null; // NEG
    private BlockPos gate2 = null; // POS

    /* ===================== Effect bindings (positions only) ===================== */
    private final List<BlockPos> waterSide1 = new ArrayList<>();
    private final List<BlockPos> waterSide2 = new ArrayList<>();
    private final List<BlockPos> heatSide1  = new ArrayList<>();
    private final List<BlockPos> heatSide2  = new ArrayList<>();
    private final List<BlockPos> steamSide1 = new ArrayList<>();
    private final List<BlockPos> steamSide2 = new ArrayList<>();
    private final List<BlockPos> hologramSide1 = new ArrayList<>();
    private final List<BlockPos> hologramSide2 = new ArrayList<>();

    /* ===================== Runtime ===================== */
    private int prepare1 = 0;
    private int prepare2 = 0;
    private int washTicks = 0;
    private int cooldownTicks = 0;
    private int lampBlink = 0;

    // door animation waits
    private int outerAnimWait = 0;  // used in ClosingAirLock (close) and OpeningSide (open)
    private int innerAnimWait = 0;  // used in OpeningMiddle (open) and ClosingMiddle (close)

    private Mode mode = Mode.MiddleClosed;
    private Side entrySide = null;   // which side initiated (NEG=SIDE1 / POS=SIDE2)

    /* ===================== Lights ===================== */
    private final GateLightGroup lightsSide1 = new GateLightGroup(GateLightGroup.Side.SIDE1);
    private final GateLightGroup lightsSide2 = new GateLightGroup(GateLightGroup.Side.SIDE2);

    /* ===================== Shared Karma Levels ===================== */
    private KarmaLevel karmaSide1 = KarmaLevel.LEVEL_0;
    private KarmaLevel karmaSide2 = KarmaLevel.LEVEL_0;

    /* ===================== Parent BE ===================== */
    private final KarmaGateBlockEntity controllerBE;

    public KarmaGateController(KarmaGateBlockEntity controllerBE) {
        this.controllerBE = controllerBE;
    }

    /* ===================== API ===================== */

    public void setGates(BlockPos g1, BlockPos g2) {
        this.gate1 = g1;
        this.gate2 = g2;
    }

    /** Bind just lights (kept for compatibility). */
    public void bindLights(World world, BlockPos pos, BlockState state, int radius) {
        Direction.Axis gateAxis = state.get(KarmaGateBlock.AXIS);
        Direction.Axis rotatedAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;
        lightsSide1.bindLights(world, pos, rotatedAxis, radius);
        lightsSide2.bindLights(world, pos, rotatedAxis, radius);
        lightsSide1.allOff(world);
        lightsSide2.allOff(world);
    }

    /** Bind lights and scan + bind nearby WaterStream/HeatCoil/Steam/Hologram BEs split by side. */
    public void bindLightsAndEffects(World world, BlockPos pos, BlockState state, int radius) {
        bindLights(world, pos, state, radius);

        Direction.Axis gateAxis   = state.get(KarmaGateBlock.AXIS);
        Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        waterSide1.clear(); waterSide2.clear();
        heatSide1.clear();  heatSide2.clear();
        steamSide1.clear(); steamSide2.clear();
        hologramSide1.clear(); hologramSide2.clear();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = pos.add(dx, dy, dz);
                    BlockEntity be = world.getBlockEntity(p);
                    boolean isNeg = (normalAxis == Direction.Axis.Z) ? (dx < 0) : (dz < 0);

                    if (be instanceof WaterStreamBlockEntity) {
                        if (isNeg) waterSide1.add(p); else waterSide2.add(p);
                    } else if (be instanceof HeatCoilBlockEntity) {
                        if (isNeg) heatSide1.add(p); else heatSide2.add(p);
                    } else if (be instanceof SteamEmitterBlockEntity) {
                        if (isNeg) steamSide1.add(p); else steamSide2.add(p);
                    } else if (be instanceof HologramProjectorBlockEntity) {
                        if (isNeg) hologramSide1.add(p); else hologramSide2.add(p);
                        ((HologramProjectorBlockEntity)be).bindController(this);
                    }
                }
            }
        }

        // Apply current shared karma levels to all found holograms
        applyKarmaToList(world, hologramSide1, karmaSide1);
        applyKarmaToList(world, hologramSide2, karmaSide2);

        KarmaGateMod.LOGGER.info("[GateCtrl @{}] bound effects: water(S1={}, S2={}), heat(S1={}, S2={}), steam(S1={}, S2={}), holo(S1={}, S2={})",
                controllerBE.getPos(), waterSide1.size(), waterSide2.size(), heatSide1.size(), heatSide2.size(), steamSide1.size(), steamSide2.size(), hologramSide1.size(), hologramSide2.size());
    }

    public void resetOnBind() {
        prepare1 = prepare2 = 0;
        washTicks = 0;
        cooldownTicks = 0;
        lampBlink = 0;
        outerAnimWait = innerAnimWait = 0;
        entrySide = null;
        mode = Mode.MiddleClosed;
        lightsSide1.allOff(controllerBE.getWorld());
        lightsSide2.allOff(controllerBE.getWorld());
        controllerBE.setOpen(false);
        stopAllWater(controllerBE.getWorld());
        stopAllHeat(controllerBE.getWorld());
        stopAllSteam(controllerBE.getWorld());
        //reset holograms
        setHologramLowPower(controllerBE.getWorld(), false, false);
        setHologramTargetLevels(controllerBE.getWorld(), 0.0f, 0.0f);
    }

    /* ===================== Tick ===================== */

    public void tick(World world, BlockPos pos, BlockState state) {
        if (world == null || world.isClient) return;
        lampBlink++;

        // orientation
        Direction.Axis gateAxis   = state.get(KarmaGateBlock.AXIS);
        Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;

        // centers
        double gx = pos.getX() + 0.5, gz = pos.getZ() + 0.5;

        // side squares
        double a1cx = gx, a1cz = gz;
        if (normalAxis == Direction.Axis.Z) a1cx = gx + OFFSET_NEG; else a1cz = gz + OFFSET_NEG;
        double a2cx = gx, a2cz = gz;
        if (normalAxis == Direction.Axis.Z) a2cx = gx + OFFSET_POS; else a2cz = gz + OFFSET_POS;

        boolean inSide1  = KarmaGateBlockEntity.anyPlayerInSquare(world, a1cx, a1cz, HALF_SIDE);
        boolean inSide2  = KarmaGateBlockEntity.anyPlayerInSquare(world, a2cx, a2cz, HALF_SIDE);
        boolean inCenter = KarmaGateBlockEntity.anyPlayerInSquare(world, gx, gz, HALF_SIDE - 2.0);

        /* cooldown gates all */
        if (cooldownTicks > 0) {
            cooldownTicks--;
            lightsSide1.allOff(world);
            lightsSide2.allOff(world);
            controllerBE.setOpen(false);
            if (cooldownTicks == 0 && mode == Mode.Closed) {
                mode = Mode.MiddleClosed;
                setHologramTargetLevels(world, 0.0f, 0.0f);
                setHologramLowPower(world, false, false);
                KarmaGateMod.LOGGER.info("[GateCtrl @{}] cooldown done → MiddleClosed", controllerBE.getPos());
            }
            return;
        }

        switch (mode) {
            case MiddleClosed -> {
                // ignore if both sides occupied or someone idling in center
                if ((inSide1 && inSide2) || inCenter) {
                    prepare1 = prepare2 = 0;
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                    controllerBE.setOpen(false);
                    break;
                }

                // prepare gating
                prepare1 = inSide1 && !inSide2 ? Math.min(prepare1 + 1, PREPARE_TICKS_MC) : 0;
                prepare2 = inSide2 && !inSide1 ? Math.min(prepare2 + 1, PREPARE_TICKS_MC) : 0;

                if (prepare1 > 0 && prepare2 == 0) {
                    lightsSide1.blinkBottomTopAlternate(world, lampBlink);
                    lightsSide2.allOff(world);
                    setHologramTargetLevelSide2(world, 1.0f);
                    setWaterFlowForSide(world, opposite(Side.SIDE1), 1.0f);
                } else if (prepare2 > 0 && prepare1 == 0) {
                    lightsSide2.blinkBottomTopAlternate(world, lampBlink);
                    lightsSide1.allOff(world);
                    setHologramTargetLevelSide1(world, 1.0f);
                    setWaterFlowForSide(world, opposite(Side.SIDE2), 1.0f);
                } else {
                    lightsSide1.allOff(world);
                    lightsSide2.allOff(world);
                    // stop all water
                    stopAllWater(world);
                    // reset hologram targets
                    setHologramTargetLevels(world, 0.0f, 0.0f);
                    setHologramLowPower(world, false, false);
                }

                if (prepare1 >= PREPARE_TICKS_MC) {
                    entrySide = Side.SIDE1;
                    setOuterOpen(world, entrySide, false);
                    outerAnimWait = GATE_ANIMATION_CLOSE_TICKS;
                    controllerBE.setOpen(false);
                    washTicks = 0;
                    lightsSide1.allOff(world); lightsSide2.allOff(world);
                    mode = Mode.ClosingAirLock;
                    setWaterFlowForSide(world, opposite(entrySide), 1.0f);
                    setHeatEnabledForSide(world, entrySide, true);
                    setHologramTargetLevelSide1(world, 1.0f);
                    KarmaGateMod.LOGGER.info("[GateCtrl @{}] PREP S1 → ClosingAirLock", controllerBE.getPos());
                } else if (prepare2 >= PREPARE_TICKS_MC) {
                    entrySide = Side.SIDE2;
                    setOuterOpen(world, entrySide, false);
                    outerAnimWait = GATE_ANIMATION_CLOSE_TICKS;
                    controllerBE.setOpen(false);
                    washTicks = 0;
                    lightsSide1.allOff(world); lightsSide2.allOff(world);
                    mode = Mode.ClosingAirLock;
                    setWaterFlowForSide(world, opposite(entrySide), 1.0f);
                    setHeatEnabledForSide(world, entrySide, true);
                    setHologramTargetLevelSide2(world, 1.0f);
                    KarmaGateMod.LOGGER.info("[GateCtrl @{}] PREP S2 → ClosingAirLock", controllerBE.getPos());
                }
            }

            case ClosingAirLock -> {
                if (outerAnimWait > 0) { outerAnimWait--; break; }

                mode = Mode.Waiting;
                setWaterFlowForSide(world, entrySide, 1.0f);
                setWaterFlowForSide(world, opposite(entrySide), 0.0f);
                setSteamEnabledForSide(world, entrySide, true);
                KarmaGateMod.LOGGER.info("[GateCtrl @{}] outer closed → Waiting", controllerBE.getPos());
            }

            case Waiting -> {
                lightsSide1.allOff(world); lightsSide2.allOff(world);

                washTicks++;
                if (washTicks >= WASH_TICKS_MC) {
                    controllerBE.setOpen(true);                 // open middle
                    innerAnimWait = GATE_ANIMATION_OPEN_TICKS;  // wait for anim
                    mode = Mode.OpeningMiddle;

                    // Turn off opposite water, turn off entry heat
                    setWaterFlowForSide(world, entrySide, 0.0f);
                    setWaterFlowForSide(world, opposite(entrySide), 1.0f);
                    setHeatEnabledForSide(world, entrySide, false);
                    setSteamEnabledForSide(world, entrySide, false);

                    KarmaGateMod.LOGGER.info("[GateCtrl @{}] Waiting done → OpeningMiddle", controllerBE.getPos());
                }
            }

            case OpeningMiddle -> {
                if (innerAnimWait > 0) { innerAnimWait--; break; }
                mode = Mode.MiddleOpen;
                setWaterFlowForSide(world, opposite(entrySide), 0.0f);
                KarmaGateMod.LOGGER.info("[GateCtrl @{}] inner open → MiddleOpen", controllerBE.getPos());
            }

            case MiddleOpen -> {
                // idle lights chase while inner is open
                chaseCircularWaitSequence(world);

                // leave when center is empty (and the player progressed to the opposite side)
                boolean progressedAcross =
                        (entrySide == Side.SIDE1 && inSide2) ||
                        (entrySide == Side.SIDE2 && inSide1);

                if (!inCenter && progressedAcross) {
                    controllerBE.setOpen(false);                // close middle
                    innerAnimWait = GATE_ANIMATION_CLOSE_TICKS; // wait for anim
                    mode = Mode.ClosingMiddle;

                    // Water ON on entry side while closing middle
                    setWaterFlowForSide(world, entrySide, 1.0f);

                    KarmaGateMod.LOGGER.info("[GateCtrl @{}] center clear → ClosingMiddle", controllerBE.getPos());
                }
            }

            case ClosingMiddle -> {
                lightsSide1.blinkAll(world, lampBlink);
                lightsSide2.blinkAll(world, lampBlink);

                if (innerAnimWait > 0) { innerAnimWait--; break; }

                // Open outer on entry side
                setOuterOpen(world, entrySide, true);
                outerAnimWait = GATE_ANIMATION_OPEN_TICKS;
                mode = Mode.OpeningSide;

                // Stop water on entry side; stop heat on opposite side
                setWaterFlowForSide(world, entrySide, 0.0f);
                setHeatEnabledForSide(world, opposite(entrySide), false);

                KarmaGateMod.LOGGER.info("[GateCtrl @{}] inner closed → OpeningSide ({})", controllerBE.getPos(), entrySide);
            }

            case OpeningSide -> {
                lightsSide1.blinkAll(world, lampBlink);
                lightsSide2.blinkAll(world, lampBlink);
                if (outerAnimWait > 0) { outerAnimWait--; break; }
                // once outer is open, enter cooldown
                cooldownTicks = COOLDOWN_TICKS_MC;
                prepare1 = prepare2 = 0;
                washTicks = 0;
                lightsSide1.allOff(world); lightsSide2.allOff(world);
                mode = Mode.Closed;
                setWaterFlowForSide(world, entrySide, 0.0f);
                setHologramTargetLevels(world, 0.20f, 0.20f);
                setHologramLowPower(world, true, true);
                KarmaGateMod.LOGGER.info("[GateCtrl @{}] outer open → Closed (cooldown={})", controllerBE.getPos(), COOLDOWN_TICKS_MC);
            }

            case Closed -> {
                // cooldown handled at top
            }

            case Broken -> {
                // intentionally inert
            }
        }
    }

    /* ===================== DRY Helpers (water/heat/steam) ===================== */

    private List<BlockPos> getWaterList(Side side) {
        return side == Side.SIDE1 ? waterSide1 : waterSide2;
    }
    private List<BlockPos> getHeatList(Side side) {
        return side == Side.SIDE1 ? heatSide1 : heatSide2;
    }
    private List<BlockPos> getSteamList(Side side) {
        return side == Side.SIDE1 ? steamSide1 : steamSide2;
    }
    private Side opposite(Side s) { return s == Side.SIDE1 ? Side.SIDE2 : Side.SIDE1; }

    /** Set water flow for all streams on a side; also sync the ENABLED state based on flow. */
    private void setWaterFlowForSide(World world, Side side, float flow) {
        setWaterFlow(world, getWaterList(side), flow);
    }
    private void setWaterFlow(World world, List<BlockPos> list, float flow) {
        boolean enable = flow > 0.02f;
        for (BlockPos p : list) {
            BlockState s = world.getBlockState(p);
            if (s.getBlock() instanceof WaterStreamBlock) {
                boolean cur = s.get(WaterStreamBlock.ENABLED);
                if (cur != enable) {
                    world.setBlockState(p, s.with(WaterStreamBlock.ENABLED, enable), 3);
                }
            }
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof WaterStreamBlockEntity ws) {
                ws.setFlow(flow);
            }
        }
    }

    /** Enable/disable all heat coils on a side. */
    private void setHeatEnabledForSide(World world, Side side, boolean enabled) {
        enableHeat(world, getHeatList(side), enabled);
    }

    /** Enable/disable all steam emitters on a side. */
    private void setSteamEnabledForSide(World world, Side side, boolean enabled) {
        enableSteam(world, getSteamList(side), enabled);
    }

    private void enableHeat(World world, List<BlockPos> list, boolean enabled) {
        for (BlockPos p : list) {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof HeatCoilBlockEntity coil) {
                coil.setEnabled(enabled);
            }
        }
    }

    private void enableSteam(World world, List<BlockPos> list, boolean enabled) {
        for (BlockPos p : list) {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof SteamEmitterBlockEntity emitter) {
                emitter.setEnabled(enabled);
                // Also mirror ENABLED into blockstate so client-side ticks run particles/sound
                BlockState s = world.getBlockState(p);
                if (s.getBlock() instanceof SteamEmitterBlock) {
                    boolean cur = s.get(SteamEmitterBlock.ENABLED);
                    if (cur != enabled) {
                        world.setBlockState(p, s.with(SteamEmitterBlock.ENABLED, enabled), 3);
                    }
                }
            }
        }
    }

    private void stopAllWater(World world) {
        setWaterFlow(world, waterSide1, 0.0f);
        setWaterFlow(world, waterSide2, 0.0f);
    }
    private void stopAllHeat(World world) {
        enableHeat(world, heatSide1, false);
        enableHeat(world, heatSide2, false);
    }
    private void stopAllSteam(World world) {
        enableSteam(world, steamSide1, false);
        enableSteam(world, steamSide2, false);
    }

    /* ===================== Gate helpers ===================== */

    private void setOuterOpen(World world, Side side, boolean open) {
        BlockPos pos = (side == Side.SIDE1) ? gate1 : gate2;
        if (world == null || pos == null) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof KarmaGateBlockEntity g) g.setOpen(open);
    }

    private void chaseCircularWaitSequence(World world) {
        int total = lightsSide1.getRefs().size() + lightsSide2.getRefs().size();
        if (total < 2) {
            lightsSide1.blinkAll(world, lampBlink);
            lightsSide2.blinkAll(world, lampBlink);
            return;
        }
        int step = (lampBlink / CIRCULAR_STEP_TICKS) % 4;
        lightsSide1.allOff(world);
        lightsSide2.allOff(world);

        boolean clockwise = (entrySide == Side.SIDE1);
        if (clockwise) {
            switch (step) {
                case 0 -> lightsSide1.lightBottomPairOnly(world);
                case 1 -> lightsSide2.lightBottomPairOnly(world);
                case 2 -> lightsSide2.lightTopPairOnly(world);
                case 3 -> lightsSide1.lightTopPairOnly(world);
            }
        } else if (entrySide == Side.SIDE2) {
            switch (step) {
                case 0 -> lightsSide2.lightBottomPairOnly(world);
                case 1 -> lightsSide1.lightBottomPairOnly(world);
                case 2 -> lightsSide1.lightTopPairOnly(world);
                case 3 -> lightsSide2.lightTopPairOnly(world);
            }
        }
    }

    /* ===================== Accessors for your effect logic ===================== */
    public List<BlockPos> getWaterSide1() { return waterSide1; }
    public List<BlockPos> getWaterSide2() { return waterSide2; }
    public List<BlockPos> getHeatSide1()  { return heatSide1;  }
    public List<BlockPos> getHeatSide2()  { return heatSide2;  }
    public List<BlockPos> getHologramSide1() { return hologramSide1; }
    public List<BlockPos> getHologramSide2() { return hologramSide2; }

    public KarmaLevel getKarmaSide1() { return karmaSide1; }
    public KarmaLevel getKarmaSide2() { return karmaSide2; }

    // Only enum-based setters exposed now
    public void setKarmaSide1(World world, KarmaLevel lvl) { setKarmaForSide(world, Side.SIDE1, lvl); }
    public void setKarmaSide2(World world, KarmaLevel lvl) { setKarmaForSide(world, Side.SIDE2, lvl); }

    /** Re-apply current stored karma levels to all currently bound holograms. */
    public void reapplyKarma(World world) {
        if (world == null) return;
        applyKarmaToList(world, hologramSide1, karmaSide1);
        applyKarmaToList(world, hologramSide2, karmaSide2);
    }

    /* ===================== Hologram visual helpers (targetLevel / lowPower) ===================== */

    // ---- targetLevel (0..1 float) ----

    /** Set target static level for all holograms on Side 1. */
    public void setHologramTargetLevelSide1(World world, float level) { setHologramTargetLevelForSide(world, Side.SIDE1, level); }
    /** Set target static level for all holograms on Side 2. */
    public void setHologramTargetLevelSide2(World world, float level) { setHologramTargetLevelForSide(world, Side.SIDE2, level); }
    /** Set target static level for both sides (null to skip a side). */
    public void setHologramTargetLevels(World world, Float side1Level, Float side2Level) {
        if (world == null) return;
        if (side1Level != null) setHologramTargetLevelForSide(world, Side.SIDE1, side1Level);
        if (side2Level != null) setHologramTargetLevelForSide(world, Side.SIDE2, side2Level);
    }

    private void setHologramTargetLevelForSide(World world, Side side, float level) {
        if (world == null) return;
        float clamped = Math.max(0f, Math.min(1f, level));
        List<BlockPos> list = (side == Side.SIDE1) ? hologramSide1 : hologramSide2;
        for (BlockPos p : list) {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof HologramProjectorBlockEntity holo) {
                holo.setTargetLevel(clamped);
            }
        }
    }

    // ---- lowPower toggle ----

    /** Toggle lowPower mode for holograms on Side 1. */
    public void setHologramLowPowerSide1(World world, boolean lowPower) { setHologramLowPowerForSide(world, Side.SIDE1, lowPower); }
    /** Toggle lowPower mode for holograms on Side 2. */
    public void setHologramLowPowerSide2(World world, boolean lowPower) { setHologramLowPowerForSide(world, Side.SIDE2, lowPower); }
    /** Toggle lowPower mode for both sides (null to skip a side). */
    public void setHologramLowPower(World world, Boolean side1Low, Boolean side2Low) {
        if (world == null) return;
        if (side1Low != null) setHologramLowPowerForSide(world, Side.SIDE1, side1Low);
        if (side2Low != null) setHologramLowPowerForSide(world, Side.SIDE2, side2Low);
    }

    private void setHologramLowPowerForSide(World world, Side side, boolean lowPower) {
        if (world == null) return;
        List<BlockPos> list = (side == Side.SIDE1) ? hologramSide1 : hologramSide2;
        for (BlockPos p : list) {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof HologramProjectorBlockEntity holo) {
                holo.setLowpower(lowPower);
            }
        }
    }

    /**
     * Called by a hologram when its symbol changes.
     * Update the required karma for its own side AND mirror to the other side so both holograms match.
     */
    public void setKarma(BlockPos pos, KarmaLevel lvl) {
        World world = controllerBE.getWorld();
        if (world == null || lvl == null) return;
        Side side = null;
        if (hologramSide1.contains(pos)) side = Side.SIDE1; else if (hologramSide2.contains(pos)) side = Side.SIDE2;

        // Fallback classification if lists are stale (e.g., hologram placed after initial bind)
        if (side == null) {
            side = classifySide(pos, world);
            // Optionally add to list so future updates are instant
            if (side == Side.SIDE1 && !hologramSide1.contains(pos)) hologramSide1.add(pos);
            else if (side == Side.SIDE2 && !hologramSide2.contains(pos)) hologramSide2.add(pos);
        }

        if (side != null) {
            setKarmaForSide(world, side, lvl); // ONLY update that side now (no mirroring)
            KarmaGateMod.LOGGER.info("[GateCtrl @{}] setKarma from hologram {} → {} (side={})", controllerBE.getPos(), pos, lvl, side);
        } else {
            KarmaGateMod.LOGGER.warn("[GateCtrl @{}] setKarma could not classify hologram {}", controllerBE.getPos(), pos);
        }
    }

    /** Determine side of a hologram relative to controller using geometry if not already bound. */
    private Side classifySide(BlockPos holoPos, World world) {
        try {
            BlockPos cPos = controllerBE.getPos();
            BlockState state = world.getBlockState(cPos);
            if (!(state.getBlock() instanceof KarmaGateBlock)) return null;
            Direction.Axis gateAxis = state.get(KarmaGateBlock.AXIS);
            Direction.Axis normalAxis = (gateAxis == Direction.Axis.X) ? Direction.Axis.Z : Direction.Axis.X;
            int diff = (normalAxis == Direction.Axis.Z)
                    ? (holoPos.getX() - cPos.getX())
                    : (holoPos.getZ() - cPos.getZ());
            return diff < 0 ? Side.SIDE1 : Side.SIDE2;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets the karma level for the specified side and updates all holograms on that side.
     */
    private void setKarmaForSide(World world, Side side, KarmaLevel lvl) {
        if (lvl == null) return;
        if (side == Side.SIDE1) {
            if (lvl == karmaSide1) return;
            karmaSide1 = lvl;
            applyKarmaToList(world, hologramSide1, karmaSide1);
        } else {
            if (lvl == karmaSide2) return;
            karmaSide2 = lvl;
            applyKarmaToList(world, hologramSide2, karmaSide2);
        }
        controllerBE.markDirty();
    }

    private void applyKarmaToList(World world, List<BlockPos> list, KarmaLevel lvl) {
        if (world == null) return;
        for (BlockPos p : list) {
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof HologramProjectorBlockEntity holo) {
                // This now also updates symbolIdx/symbolKey for visuals
                holo.setKarmaLevelEnum(lvl);
            }
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
        nbt.putInt("lampBlink", lampBlink);
        nbt.putInt("outerAnimWait", outerAnimWait);
        nbt.putInt("innerAnimWait", innerAnimWait);

        nbt.putString("mode", mode.name());
        nbt.putString("entrySide", entrySide == null ? "null" : entrySide.name());

        // lights
        lightsSide1.writeNbt(nbt, "lightsSide1");
        lightsSide2.writeNbt(nbt, "lightsSide2");

        // effect lists
        writePosList(nbt, "waterSide1", waterSide1);
        writePosList(nbt, "waterSide2", waterSide2);
        writePosList(nbt, "heatSide1",  heatSide1);
        writePosList(nbt, "heatSide2",  heatSide2);
        writePosList(nbt, "steamSide1", steamSide1);
        writePosList(nbt, "steamSide2", steamSide2);
        writePosList(nbt, "holoSide1", hologramSide1);
        writePosList(nbt, "holoSide2", hologramSide2);

        // enum names
        nbt.putString("karmaSide1", karmaSide1.name());
        nbt.putString("karmaSide2", karmaSide2.name());
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
        outerAnimWait = nbt.getInt("outerAnimWait");
        innerAnimWait = nbt.getInt("innerAnimWait");

        try { mode = Mode.valueOf(nbt.getString("mode")); }
        catch (IllegalArgumentException e) { mode = Mode.MiddleClosed; }

        String es = nbt.getString("entrySide");
        if (es == null || es.isEmpty() || "null".equals(es)) entrySide = null;
        else {
            try { entrySide = Side.valueOf(es); } catch (IllegalArgumentException e) { entrySide = null; }
        }

        lightsSide1.readNbt(nbt, "lightsSide1");
        lightsSide2.readNbt(nbt, "lightsSide2");

        waterSide1.clear(); waterSide2.clear();
        heatSide1.clear();  heatSide2.clear();
        readPosList(nbt, "waterSide1", waterSide1);
        readPosList(nbt, "waterSide2", waterSide2);
        readPosList(nbt, "heatSide1",  heatSide1);
        readPosList(nbt, "heatSide2",  heatSide2);
        readPosList(nbt, "steamSide1", steamSide1);
        readPosList(nbt, "steamSide2", steamSide2);
        readPosList(nbt, "holoSide1", hologramSide1);
        readPosList(nbt, "holoSide2", hologramSide2);

        // enum-or-float back-compat
        if (nbt.contains("karmaSide1")) {
            if (nbt.get("karmaSide1") instanceof net.minecraft.nbt.NbtString) {
                try { karmaSide1 = KarmaLevel.valueOf(nbt.getString("karmaSide1")); }
                catch (IllegalArgumentException e) { karmaSide1 = KarmaLevel.LEVEL_0; }
            } else {
                karmaSide1 = KarmaLevel.fromFloat(nbt.getFloat("karmaSide1"));
            }
        }
        if (nbt.contains("karmaSide2")) {
            if (nbt.get("karmaSide2") instanceof net.minecraft.nbt.NbtString) {
                try { karmaSide2 = KarmaLevel.valueOf(nbt.getString("karmaSide2")); }
                catch (IllegalArgumentException e) { karmaSide2 = KarmaLevel.LEVEL_0; }
            } else {
                karmaSide2 = KarmaLevel.fromFloat(nbt.getFloat("karmaSide2"));
            }
        }

        KarmaGateMod.LOGGER.info("[GateCtrl @{}] readNbt: mode={}, entrySide={}, effects w(S1={},S2={}) h(S1={},S2={}) s(S1={},S2={})",
                controllerBE.getPos(), mode, entrySide, waterSide1.size(), waterSide2.size(), heatSide1.size(), heatSide2.size(), steamSide1.size(), steamSide2.size());
    }

    /* ===================== Small NBT helpers ===================== */
    private static void writePosList(NbtCompound root, String key, List<BlockPos> list) {
        NbtCompound bag = new NbtCompound();
        bag.putInt("n", list.size());
        for (int i = 0; i < list.size(); i++) {
            BlockPos p = list.get(i);
            NbtCompound e = new NbtCompound();
            e.putInt("x", p.getX());
            e.putInt("y", p.getY());
            e.putInt("z", p.getZ());
            bag.put("p" + i, e);
        }
        root.put(key, bag);
    }
    private static void readPosList(NbtCompound root, String key, List<BlockPos> out) {
        if (!root.contains(key)) return;
        NbtCompound bag = root.getCompound(key);
        int n = bag.getInt("n");
        for (int i = 0; i < n; i++) {
            NbtCompound e = bag.getCompound("p" + i);
            out.add(new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z")));
        }
    }

    public BlockPos getPos() {
        //return controllerBE.getPos();
        return controllerBE == null ? null : controllerBE.getPos();
    }
}
