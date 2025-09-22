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

        this.intensity = (float)Math.max(0.0, Math.min(1.0, vyAsIntensity)); // encoded in 'vy'
        this.setSprite(sprites);

        // Initial motion: gentle upward drift
        this.velocityX = (this.random.nextDouble() - 0.5) * 0.02;
        this.velocityY = 2 * (0.03 + 0.04 * this.intensity); // rise faster when hotter
        this.velocityZ = (this.random.nextDouble() - 0.5) * 0.02;

        // Reverse gravity (rises)
        this.gravityStrength = -0.01f;

        // Visuals based on intensity
        float base = 0.6f + 0.4f * this.intensity;
        this.red = this.green = this.blue = base; // whitens when hotter
        this.alpha = 0.6f + 0.35f * this.intensity;

        this.scale = 0.2f + 0.6f * this.intensity; // bigger when hotter
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

        // Slow upward drift gets weaker over time
        this.velocityY *= 0.995;

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
