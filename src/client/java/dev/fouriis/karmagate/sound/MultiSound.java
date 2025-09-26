package dev.fouriis.karmagate.sound;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Small runtime that plays PLAYALL (mix) or random with loop, silentChance, per-clip volume/pitch, and optional doppler.
 * Client-side only.
 */
public final class MultiSound {

    public record Clip(SoundEvent event, float volume, float pitch) {}

    public static final class Spec {
        public final List<Clip> clips = new ArrayList<>();
        public boolean playAll = false;   // PLAYALL -> mix; else pick random
        public boolean loop    = false;   // LOOP
        public float   silentChance = 0f; // 0..1
        public float   dopplerFac  = 0f;  // 0 disables pseudo-doppler

        public Spec add(Clip c) { clips.add(c); return this; }
        public Spec playAll(boolean b) { this.playAll = b; return this; }
        public Spec loop(boolean b)    { this.loop = b;    return this; }
        public Spec silentChance(float f){this.silentChance = java.lang.Math.max(0, java.lang.Math.min(1,f)); return this;}
        public Spec doppler(float f){ this.dopplerFac = java.lang.Math.max(0, f); return this; }
    }

    public static final class Handle {
        private final List<Tick> ticks = new ArrayList<>();
        private boolean stopped = false;

        public void stop() {
            if (stopped) return;
            stopped = true;
            for (var t : ticks) t.stop();
            ticks.clear();
        }
        public boolean isPlaying() { return !stopped && ticks.stream().anyMatch(t -> !t.done); }
    }

    /** Client-only: play a Spec at a block position */
    public static Handle playAt(BlockPos pos, Spec spec) {
        Objects.requireNonNull(MinecraftClient.getInstance().world, "client world");
        if (spec.clips.isEmpty()) {
            KarmaGateMod.LOGGER.info("[MultiSound] no clips in spec at {}", pos);
            return new Handle();
        }
        if (spec.silentChance > 0f && java.lang.Math.random() < spec.silentChance) {
            KarmaGateMod.LOGGER.info("[MultiSound] silentChance prevented play at {}", pos);
            return new Handle();
        }

        Handle h = new Handle();
        if (spec.playAll) {
            KarmaGateMod.LOGGER.info("[MultiSound] PLAYALL {} clip(s) at {} (loop={}, doppler={})",
                    spec.clips.size(), pos, spec.loop, spec.dopplerFac);
            for (var c : spec.clips) h.ticks.add(new Tick(pos, c, spec.loop, spec.dopplerFac));
        } else {
            var c = spec.clips.get((int)(java.lang.Math.random() * spec.clips.size()));
            KarmaGateMod.LOGGER.info("[MultiSound] RANDOM pick {} at {} (loop={}, doppler={})",
                    c.event().getId(), pos, spec.loop, spec.dopplerFac);
            h.ticks.add(new Tick(pos, c, spec.loop, spec.dopplerFac));
        }
        var sm = MinecraftClient.getInstance().getSoundManager();
        for (var t : h.ticks) {
            WeightedSoundSet set = sm.get(t.getId());
            if (set == null) {
                KarmaGateMod.LOGGER.warn("[MultiSound] No WeightedSoundSet for {} (id)", t.getId());
            } else {
                KarmaGateMod.LOGGER.info("[MultiSound] Playing {} at {}", t.getId(), pos);
            }
            sm.play(t);
        }
        return h;
    }

    // Per-clip positional sound
    private static final class Tick implements TickableSoundInstance {
        private final Clip clip;
        private final BlockPos pos;
        private final boolean looping;
        private final float dopplerFac;
        private boolean done = false;
        private float vol, pit;
        private Sound resolved; // cache the chosen weighted sound so getSound() is non-null

        Tick(BlockPos pos, Clip clip, boolean loop, float dopplerFac) {
            this.pos = pos;
            this.clip = clip;
            this.looping = loop;
            this.dopplerFac = dopplerFac;
            this.vol = clip.volume();
            this.pit = clip.pitch();
        }

        void stop() { done = true; }

        @Override public void tick() {
            if (done) return;
            if (dopplerFac > 0f) {
                var mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    Vec3d src = new Vec3d(getX(), getY(), getZ());
                    Vec3d cam = mc.player.getCameraPosVec(1f);
                    Vec3d rel = src.subtract(cam);
                    double dist = rel.length();
                    if (dist > 1e-4) {
                        rel = rel.normalize();
                        Vec3d v = mc.player.getVelocity(); // blocks/tick
                        double radial = v.dotProduct(rel);
                        float scale = (float) (radial / 0.7f); // pseudo speed-of-sound ~0.7 b/tick
                        float newPit = clamp(0.5f, 2.0f, clip.pitch() * (1.0f + dopplerFac * scale));
                        if (java.lang.Math.abs(newPit - pit) > 1e-3) {
                            KarmaGateMod.LOGGER.trace("[MultiSound] doppler pitch {} -> {}", pit, newPit);
                        }
                        pit = newPit;
                    }
                }
            }
        }

        @Override public boolean isDone() { return done; }
        @Override public Identifier getId() { return clip.event().getId(); }
        @Override public WeightedSoundSet getSoundSet(SoundManager manager) {
            WeightedSoundSet set = manager.get(getId());
            if (resolved == null && set != null) {
                try {
                    resolved = set.getSound(net.minecraft.util.math.random.Random.create());
                } catch (Throwable t) {
                    KarmaGateMod.LOGGER.warn("[MultiSound] Failed to choose weighted sound for {}: {}", getId(), t.toString());
                }
            }
            return set;
        }
        @Override public Sound getSound() {
            if (resolved == null) {
                // Late resolve in case engine queried getSound() before getSoundSet()
                try {
                    SoundManager sm = MinecraftClient.getInstance().getSoundManager();
                    WeightedSoundSet set = sm.get(getId());
                    if (set != null) resolved = set.getSound(net.minecraft.util.math.random.Random.create());
                } catch (Throwable ignored) {}
            }
            return resolved;
        }
        @Override public SoundCategory getCategory() { return SoundCategory.BLOCKS; }
        @Override public boolean isRepeatable() { return looping; }
        @Override public int getRepeatDelay() { return 0; }
        @Override public float getVolume() { return vol; }
        @Override public float getPitch() { return pit; }
        @Override public double getX() { return pos.getX() + 0.5; }
        @Override public double getY() { return pos.getY() + 0.5; }
        @Override public double getZ() { return pos.getZ() + 0.5; }
        @Override public AttenuationType getAttenuationType() { return AttenuationType.LINEAR; }
        @Override public boolean shouldAlwaysPlay() { return false; }
        @Override public boolean isRelative() { return false; }
    }

    private static float clamp(float a, float b, float v){ return v < a ? a : (v > b ? b : v); }
}
