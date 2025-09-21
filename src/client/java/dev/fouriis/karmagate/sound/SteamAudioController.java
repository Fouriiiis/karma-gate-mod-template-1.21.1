// dev/fouriis/karmagate/sound/SteamAudioController.java
package dev.fouriis.karmagate.sound;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
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

    public void onSteamBurst(BlockPos pos, float intensity01) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        SoundManager sm = mc.getSoundManager();
        if (sm == null) return;

        SteamLoopSound loop = loops.get(pos);
        if (loop == null || loop.isDone()) {
            loop = new SteamLoopSound(pos);
            loops.put(pos, loop);
            sm.play(loop);
            // quick debug
            // System.out.println("Steam loop START at " + pos);
        }
        loop.bumpTarget(intensity01);
    }

    public void clientTick() {
        Iterator<Map.Entry<BlockPos, SteamLoopSound>> it = loops.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isDone()) it.remove();
        }
    }
}
