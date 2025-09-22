// dev/fouriis/karmagate/sound/SteamAudioController.java
package dev.fouriis.karmagate.sound;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Environment(EnvType.CLIENT)
public final class SteamAudioController {
    private static final SteamAudioController INSTANCE = new SteamAudioController();
    public static SteamAudioController get() { return INSTANCE; }

    private final Map<BlockPos, SteamLoopSound> loops = new HashMap<>();

    private SteamAudioController() {}

    /** Original (defaults to coil loop). */
    public void onSteamBurst(BlockPos pos, float intensity01) {
        onSteamBurst(pos, intensity01, ModSounds.STEAM_LOOP_2_EVENT);
    }

    /** Generic—pass whatever loop you want (e.g., emitter loop). */
    public void onSteamBurst(BlockPos pos, float intensity01, SoundEvent loopEvent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        SoundManager sm = mc.getSoundManager();
        if (sm == null) return;

        SteamLoopSound loop = loops.get(pos);
        // If no loop, loop has completed, or SoundManager isn't playing it (e.g., after world reload), make a new one
        if (loop == null || loop.isDone() || !sm.isPlaying(loop)) {
            loop = new SteamLoopSound(pos, loopEvent);
            loops.put(pos, loop);
            sm.play(loop);
        }
        loop.bumpTarget(intensity01);
    }

    public void clientTick() {
        Iterator<Map.Entry<BlockPos, SteamLoopSound>> it = loops.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isDone()) it.remove();
        }
    }

    /** Clear any cached loop references, e.g., on world disconnect/join. */
    public void clear() {
        loops.clear();
    }
}
