package dev.fouriis.karmagate.sound;


import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ModSounds {
    public static final Identifier STEAM_LOOP = Identifier.of("karma-gate-mod:steamloop");
    public static SoundEvent STEAM_LOOP_EVENT = SoundEvent.of(STEAM_LOOP);
    public static final Identifier STEAM_LOOP_2 = Identifier.of("karma-gate-mod:steamloop2");
    public static SoundEvent STEAM_LOOP_2_EVENT = SoundEvent.of(STEAM_LOOP_2);

    public static void registerModSounds() {
        Registry.register(Registries.SOUND_EVENT, STEAM_LOOP, STEAM_LOOP_EVENT);
        Registry.register(Registries.SOUND_EVENT, STEAM_LOOP_2, STEAM_LOOP_2_EVENT);
        System.out.println("Registering sound event: " + STEAM_LOOP_2);
    }

    // ---------------- Inline client audio shim ----------------
    public interface AudioImpl {
        void onSteamBurst(BlockPos pos, float intensity01, SoundEvent loopEvent);
    }

    private static AudioImpl AUDIO = (p, i, e) -> {};

    public static void setAudio(AudioImpl impl) {
        if (impl != null) AUDIO = impl;
    }

    public static void onSteamBurst(BlockPos pos, float intensity01, SoundEvent loopEvent) {
        AUDIO.onSteamBurst(pos, intensity01, loopEvent);
    }
}
