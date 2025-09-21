// dev/fouriis/karmagate/sound/SteamLoopSound.java
package dev.fouriis.karmagate.sound;

import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

public final class SteamLoopSound extends PositionedSoundInstance implements TickableSoundInstance {
    private static final float FADE_SPEED  = 0.12f;  // volume -> target
    private static final float MIN_AUDIBLE = 0.01f;  // stop threshold
    private static final float GAIN        = 0.9f;   // master gain
    private static final float FLOOR       = 0.15f;  // floor while active
    private static final int   QUIET_TICKS = 15;     // grace before stop

    private final BlockPos pos;
    private float targetVolume = 0f;
    private int silentTicks = 0;
    private boolean done;
    private boolean loggedFirstTick = false;

    public SteamLoopSound(BlockPos pos) {
        // (event, category, baseVolume, basePitch, random, pos)
        super(ModSounds.STEAM_LOOP_2_EVENT, SoundCategory.BLOCKS, 0f, 0f, SoundInstance.createRandom(), pos);
        this.pos = pos.toImmutable();

        // world position
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;

        // make sure a source is created
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.05f;     // small non-zero so the engine spawns it
        this.pitch  = 1.0f;

        // CRITICAL: positional audio settings the engine actually reads
        this.relative = false;                           // NOT listener-relative
        this.attenuationType = AttenuationType.LINEAR;       // use distance falloff
        // (You can switch to NONE temporarily for debugging if you want.)
    }

    /** Called when a steam particle burst happens at this coil. */
    public void bumpTarget(float intensity01) {
        float v = Math.max(0f, Math.min(1f, intensity01));
        float mapped = FLOOR + v * (1f - FLOOR); // 0.15..1
        targetVolume = Math.max(targetVolume, mapped * GAIN);
        silentTicks = 0;
    }

    @Override
    public void tick() {
        if (!loggedFirstTick) {
            System.out.println("[SteamLoopSound] tick started at " + pos + " (vol=" + volume + ")");
            loggedFirstTick = true;
        }

        // fade volume toward target
        volume += (targetVolume - volume) * FADE_SPEED;

        // natural decay of the target unless we get bumped again
        targetVolume *= 0.90f;

        // tiny pitch hint
        float targetPitch = 0.9f + targetVolume * 0.3f;
        pitch += (targetPitch - pitch) * 0.05f;

        // stop after quiet grace period
        if (targetVolume < MIN_AUDIBLE && volume < MIN_AUDIBLE) {
            if (++silentTicks > QUIET_TICKS) setDone();
        } else {
            silentTicks = 0;
        }
    }

    public BlockPos getPos() { return pos; }

    private void setDone() {
        this.done = true;
        this.repeat = false;
        System.out.println("[SteamLoopSound] stopping at " + pos);
    }

    @Override public boolean isDone() { return done; }
}
