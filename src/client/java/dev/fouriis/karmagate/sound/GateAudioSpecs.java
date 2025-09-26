package dev.fouriis.karmagate.sound;

import static dev.fouriis.karmagate.sound.ModSounds.*;
import dev.fouriis.karmagate.sound.MultiSound.*;

/** Ready-to-use grouped audio specs for gate interactions (client-side). */
public final class GateAudioSpecs {

    // --- WATER ---
    public static final Spec WATER_BG = new Spec()
        .loop(true).playAll(false)
        .add(new Clip(DEEP_GLUG_LOOP_EVENT,    0.4f, 1.0f))
        .add(new Clip(DRAIN_GLUG_A_EVENT,      0.5f, 1.0f));

    public static final Spec WATER_SCREW = new Spec()
        .loop(true).add(new Clip(CAABINET_LOOP_EVENT, 0.6f, 1.0f));

    public static final Spec WATERFALL = new Spec()
        .loop(true).add(new Clip(WATER_DRAIN_LOOP_EVENT, 0.5f, 1.0f));

    public static final Spec WATER_STEAM = new Spec()
        .loop(true).add(new Clip(STEAM_LOOP_2_EVENT, 0.5f, 1.0f));

    public static final Spec WATER_STEAM_PUFF = new Spec()
        .loop(false).silentChance(0.9f)
        .add(new Clip(STEAM_BLAST_A_EVENT, 0.4f, 1.0f));

    // --- ELECTRIC ---
    public static final Spec ELEC_BG = new Spec()
        .loop(true).playAll(true)
        .add(new Clip(OMINOUS_MACHINE_A_EVENT, 0.6f, 1.0f))
        .add(new Clip(MACHINE_DOORS_EVENT,     0.5f, 1.0f));

    public static final Spec ELEC_SCREW = new Spec()
        .loop(true).add(new Clip(CAABINET_LOOP_EVENT, 0.6f, 1.0f));

    public static final Spec ELEC_STEAM = new Spec()
        .loop(true).add(new Clip(STEAM_LOOP_2_EVENT, 0.5f, 1.0f));

    public static final Spec ELEC_STEAM_PUFF = new Spec()
        .loop(false).silentChance(0.9f)
        .add(new Clip(STEAM_BLAST_A_EVENT, 0.4f, 1.0f));

    // --- MECHANICS / DOORS ---
    public static final Spec POLES_AND_RAILS_IN = new Spec()
        .add(new Clip(HYDRAULICS_A_EVENT, 0.5f, 1.0f));

    public static final Spec POLES_OUT = new Spec()
        .add(new Clip(METAL_SCRAPE_A_EVENT, 0.3f, 1.0f));

    public static final Spec RAILS_COLLIDE = new Spec()
        .add(new Clip(HUGE_IMPACT_EVENT, 0.4f, 1.0f));

    public static final Spec CLAMP_BACK_DEFAULT = new Spec()
        .add(new Clip(LARGE_LATCH_EVENT, 0.5f, 1.0f));

    public static final Spec CLAMPS_MOVING_LOOP = new Spec()
        .loop(true).add(new Clip(CAABINET_LOOP_EVENT, 0.5f, 1.0f));

    public static final Spec CLAMP_IN_POSITION = new Spec()
        .add(new Clip(CAABINET_DOOR_EVENT, 0.4f, 1.0f));

    public static final Spec CLAMP_COLLISION = new Spec()
        .add(new Clip(METAL_PLING_EVENT, 0.3f, 1.0f));

    public static final Spec CLAMP_LOCK = new Spec()
        .add(new Clip(LARGE_LATCH_EVENT, 0.5f, 1.0f));

    public static final Spec PILLOWS_MOVE_IN = new Spec()
        .add(new Clip(HYDRAULICS_A_EVENT, 0.4f, 1.0f));

    public static final Spec PILLOWS_IN_PLACE = new Spec()
        .add(new Clip(HYDRA_IMPACT_EVENT, 0.4f, 1.0f));

    public static final Spec PILLOWS_MOVE_OUT = new Spec()
        .add(new Clip(HYDRAULICS_A_EVENT, 0.4f, 1.0f));

    public static final Spec SECURE_RAIL_DOWN = new Spec()
        .add(new Clip(CHAIN_DOOR_LOOP_EVENT, 0.4f, 1.0f));

    public static final Spec SECURE_RAIL_UP = new Spec()
        .add(new Clip(CHAIN_DOOR_LOOP_EVENT, 0.4f, 1.0f));

    public static final Spec BOLT = new Spec()
        .add(new Clip(MED_IMPACT_2_EVENT, 0.4f, 1.0f));

    public static final Spec PANSER_ON  = new Spec().add(new Clip(HYDRAULICS_A_EVENT, 0.4f, 1.0f));
    public static final Spec PANSER_OFF = new Spec().add(new Clip(HYDRAULICS_A_EVENT, 0.4f, 1.0f));

    private GateAudioSpecs() {}
}
