// dev/fouriis/karmagate/sound/SteamLoopSound.java
package dev.fouriis.karmagate.sound;

import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

public final class SteamLoopSound extends PositionedSoundInstance implements TickableSoundInstance {
    private static final float FADE_SPEED  = 0.12f;
    private static final float MIN_AUDIBLE = 0.01f;
    private static final float GAIN        = 0.9f;
    private static final float FLOOR       = 0.15f;
    private static final int   QUIET_TICKS = 15;

    private final BlockPos pos;
    private float targetVolume = 0f;
    private int silentTicks = 0;
    private boolean done;

    public SteamLoopSound(BlockPos pos, SoundEvent event) {
        super(event, SoundCategory.BLOCKS, 0f, 0f, SoundInstance.createRandom(), pos);
        this.pos = pos.toImmutable();

        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;

        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.05f;
        this.pitch  = 1.0f;

        this.relative = false;
        this.attenuationType = AttenuationType.LINEAR;
    }

    public void bumpTarget(float intensity01) {
        float v = Math.max(0f, Math.min(1f, intensity01));
        float mapped = FLOOR + v * (1f - FLOOR);
        targetVolume = Math.max(targetVolume, mapped * GAIN);
        silentTicks = 0;
    }

    @Override
    public void tick() {
        volume += (targetVolume - volume) * FADE_SPEED;
        targetVolume *= 0.90f;

        float targetPitch = 0.9f + targetVolume * 0.3f;
        pitch += (targetPitch - pitch) * 0.05f;

        if (targetVolume < MIN_AUDIBLE && volume < MIN_AUDIBLE) {
            if (++silentTicks > QUIET_TICKS) {
                done = true;
                repeat = false;
            }
        } else {
            silentTicks = 0;
        }
    }

    public BlockPos getPos() { return pos; }
    @Override public boolean isDone() { return done; }
}
