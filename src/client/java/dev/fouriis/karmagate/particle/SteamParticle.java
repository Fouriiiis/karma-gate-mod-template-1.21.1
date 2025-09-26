package dev.fouriis.karmagate.particle;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;

@Environment(EnvType.CLIENT)
public class SteamParticle extends SpriteBillboardParticle {
    private float intensity; // 0..1
    private final SpriteProvider spriteProvider;

    protected SteamParticle(ClientWorld world, double x, double y, double z,
                            double vx, double vyAsIntensity, double vz,
                            SpriteProvider sprites) {
        super(world, x, y, z, 0, 0, 0);

    // Triple the incoming intensity (clamped), for hotter/brighter/bigger steam
    this.intensity = (float)Math.max(0.0, Math.min(1.0, vyAsIntensity * 3.0)); // encoded in 'vy'
        this.setSprite(sprites);

    // Initial motion: much stronger outward drift and rise
    this.velocityX = (this.random.nextDouble() - 0.5) * 0.10; // wider horizontal spread
    this.velocityY = (float)(0.25 + 0.45 * this.intensity);   // significantly higher rise
    this.velocityZ = (this.random.nextDouble() - 0.5) * 0.10;

    // Stronger negative gravity so steam keeps rising
    this.gravityStrength = -0.03f;

        // Visuals based on intensity
        float base = 0.6f + 0.4f * this.intensity;
        this.red = this.green = this.blue = base; // whitens when hotter
        this.alpha = 0.6f + 0.35f * this.intensity;

    // Triple the visual size
    this.scale = (0.2f + 0.6f * this.intensity) * 3.0f; // bigger when hotter
        this.maxAge = 18 + (int)(18 * this.intensity); // lives a bit longer when hotter
        this.collidesWithWorld = false;

        this.spriteProvider = sprites;
        this.setSpriteForAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();

        // Decay intensity -> shrink & fade over life
        this.intensity *= 0.96f;
        this.alpha *= 0.97f;
        this.scale *= 0.985f;

    // Preserve more upward velocity over time (less damping)
    this.velocityY *= 0.998;

        if (this.alpha < 0.03f) {
            this.markDead();
        } else {
            this.setSpriteForAge(this.spriteProvider);
        }
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    // Factory
    @Environment(EnvType.CLIENT)
    public static class Factory implements ParticleFactory<SimpleParticleType> {
        private final SpriteProvider sprites;

        public Factory(SpriteProvider sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientWorld world,
                                       double x, double y, double z,
                                       double vx, double vyAsIntensity, double vz) {
            return new SteamParticle(world, x, y, z, vx, vyAsIntensity, vz, sprites);
        }
    }
}
