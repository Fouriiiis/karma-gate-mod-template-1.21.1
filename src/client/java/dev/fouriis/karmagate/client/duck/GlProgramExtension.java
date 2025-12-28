package dev.fouriis.karmagate.client.duck;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;

public interface GlProgramExtension {
    void setTimeUniform(GlUniformFloat u);
    GlUniformFloat getTimeUniform();

    void setWaveAmplitudeUniform(GlUniformFloat u);
    GlUniformFloat getWaveAmplitudeUniform();

    void setWaveFrequencyUniform(GlUniformFloat u);
    GlUniformFloat getWaveFrequencyUniform();

    void setWaveSpeedUniform(GlUniformFloat u);
    GlUniformFloat getWaveSpeedUniform();

    void setWaveDirectionUniform(GlUniformFloat2v u);
    GlUniformFloat2v getWaveDirectionUniform();

    void setCameraPositionUniform(GlUniformFloat2v u);
    GlUniformFloat2v getCameraPositionUniform();
}
