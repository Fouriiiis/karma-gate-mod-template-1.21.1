package dev.fouriis.karmagate.sound;


import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class ModSounds {
    public static final Identifier STEAM_LOOP_2 = Identifier.of("karma-gate-mod:steamloop2");
    public static SoundEvent STEAM_LOOP_2_EVENT = SoundEvent.of(STEAM_LOOP_2);

    public static void registerModSounds() {
        Registry.register(Registries.SOUND_EVENT, STEAM_LOOP_2, STEAM_LOOP_2_EVENT);
        System.out.println("Registering sound event: " + STEAM_LOOP_2);
    }
}
