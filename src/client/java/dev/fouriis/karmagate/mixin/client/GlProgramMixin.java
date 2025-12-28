package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.client.duck.GlProgramExtension;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GlProgram.class)
public class GlProgramMixin implements GlProgramExtension {
    @Unique
    private GlUniformFloat timeUniform;
    @Unique
    private GlUniformFloat waveAmplitudeUniform;
    @Unique
    private GlUniformFloat waveFrequencyUniform;
    @Unique
    private GlUniformFloat waveSpeedUniform;
    @Unique
    private GlUniformFloat2v waveDirectionUniform;
    @Unique
    private GlUniformFloat2v cameraPositionUniform;

    @Override
    public void setTimeUniform(GlUniformFloat u) {
        this.timeUniform = u;
    }

    @Override
    public GlUniformFloat getTimeUniform() {
        return this.timeUniform;
    }

    @Override
    public void setWaveAmplitudeUniform(GlUniformFloat u) {
        this.waveAmplitudeUniform = u;
    }

    @Override
    public GlUniformFloat getWaveAmplitudeUniform() {
        return this.waveAmplitudeUniform;
    }

    @Override
    public void setWaveFrequencyUniform(GlUniformFloat u) {
        this.waveFrequencyUniform = u;
    }

    @Override
    public GlUniformFloat getWaveFrequencyUniform() {
        return this.waveFrequencyUniform;
    }

    @Override
    public void setWaveSpeedUniform(GlUniformFloat u) {
        this.waveSpeedUniform = u;
    }

    @Override
    public GlUniformFloat getWaveSpeedUniform() {
        return this.waveSpeedUniform;
    }

    @Override
    public void setWaveDirectionUniform(GlUniformFloat2v u) {
        this.waveDirectionUniform = u;
    }

    @Override
    public GlUniformFloat2v getWaveDirectionUniform() {
        return this.waveDirectionUniform;
    }

    @Override
    public void setCameraPositionUniform(GlUniformFloat2v u) {
        this.cameraPositionUniform = u;
    }

    @Override
    public GlUniformFloat2v getCameraPositionUniform() {
        return this.cameraPositionUniform;
    }
}
