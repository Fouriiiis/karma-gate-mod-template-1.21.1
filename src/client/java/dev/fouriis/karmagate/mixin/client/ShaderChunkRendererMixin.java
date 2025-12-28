package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.client.duck.GlProgramExtension;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("KarmaGate-Waves");

    // Wave Configuration
    private static final float WAVE_AMPLITUDE = 0.8f;
    private static final float WAVE_WAVELENGTH = 12.0f; // Frequency = 2*PI / Wavelength
    private static final float WAVE_SPEED = 0.2f;
    private static final float WAVE_ANGLE_DEGREES = 45.0f; // 0 = North, 90 = East

    @Shadow(remap = false)
    protected GlProgram<ChunkShaderInterface> activeProgram;

    @org.spongepowered.asm.mixin.Unique
    private static boolean karmaGate$loggedUniformPresence = false;

    @Inject(method = "compileProgram", at = @At("RETURN"), remap = false)
    private void onCompileProgram(ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> cir) {
        GlProgram<ChunkShaderInterface> program = cir.getReturnValue();
        if (program == null) return;

        // Use Optional binding so we don't spam logs when running under a shaderpack
        // that doesn't include our custom uniforms.
        GlProgramExtension ext = (GlProgramExtension) program;

        ext.setTimeUniform(program.bindUniformOptional("u_Time", GlUniformFloat::new));
        ext.setWaveAmplitudeUniform(program.bindUniformOptional("u_WaveAmplitude", GlUniformFloat::new));
        ext.setWaveFrequencyUniform(program.bindUniformOptional("u_WaveFrequency", GlUniformFloat::new));
        ext.setWaveSpeedUniform(program.bindUniformOptional("u_WaveSpeed", GlUniformFloat::new));
        ext.setWaveDirectionUniform(program.bindUniformOptional("u_WaveDirection", GlUniformFloat2v::new));
        ext.setCameraPositionUniform(program.bindUniformOptional("u_CameraPosition", GlUniformFloat2v::new));

        if (!karmaGate$loggedUniformPresence) {
            karmaGate$loggedUniformPresence = true;
            boolean any = ext.getTimeUniform() != null
                    || ext.getWaveAmplitudeUniform() != null
                    || ext.getWaveFrequencyUniform() != null
                    || ext.getWaveSpeedUniform() != null
                    || ext.getWaveDirectionUniform() != null
                    || ext.getCameraPositionUniform() != null;

            // If this prints false, your shader override is not being picked up.
            LOGGER.info("Wave shader uniforms present in compiled terrain program: {}", any);
        }
    }

    @Inject(method = "begin", at = @At("TAIL"), remap = false)
    private void onBegin(TerrainRenderPass pass, CallbackInfo ci) {
        GlProgram<ChunkShaderInterface> program = this.activeProgram;
        if (program == null) return;

        GlProgramExtension ext = (GlProgramExtension) program;

        // Lazy initialization: If uniforms are missing (e.g. cached shader or Iris override), try to bind them now.
        if (ext.getTimeUniform() == null) {
            ext.setTimeUniform(program.bindUniformOptional("u_Time", GlUniformFloat::new));
            ext.setWaveAmplitudeUniform(program.bindUniformOptional("u_WaveAmplitude", GlUniformFloat::new));
            ext.setWaveFrequencyUniform(program.bindUniformOptional("u_WaveFrequency", GlUniformFloat::new));
            ext.setWaveSpeedUniform(program.bindUniformOptional("u_WaveSpeed", GlUniformFloat::new));
            ext.setWaveDirectionUniform(program.bindUniformOptional("u_WaveDirection", GlUniformFloat2v::new));
            ext.setCameraPositionUniform(program.bindUniformOptional("u_CameraPosition", GlUniformFloat2v::new));
            
            // Only log if we actually found something, to avoid spamming if the shader simply doesn't support waves
            if (ext.getTimeUniform() != null) {
                LOGGER.info("Lazily bound wave uniforms for program {}", program);
            }
        }

        // If the shaderpack doesn't include our uniforms, all of these will be null.
        GlUniformFloat uTime = ext.getTimeUniform();
        GlUniformFloat uAmp = ext.getWaveAmplitudeUniform();
        GlUniformFloat uFreq = ext.getWaveFrequencyUniform();
        GlUniformFloat uSpeed = ext.getWaveSpeedUniform();
        GlUniformFloat2v uDir = ext.getWaveDirectionUniform();
        GlUniformFloat2v uCam = ext.getCameraPositionUniform();

        if (uTime == null && uAmp == null && uFreq == null && uSpeed == null && uDir == null && uCam == null) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        float tickDelta = 0.0f;
        RenderTickCounter counter = mc.getRenderTickCounter();
        if (counter != null) {
            tickDelta = counter.getTickDelta(true);
        }

        float timeSeconds = 0.0f;
        if (mc.world != null) {
            timeSeconds = (mc.world.getTime() + tickDelta) / 20.0f;
        }

        // frequency is 2*PI / wavelength
        float waveFrequency = (float) ((Math.PI * 2.0) / WAVE_WAVELENGTH);

        // Angle convention: 0 = North (-Z), 90 = East (+X)
        float ang = (float) Math.toRadians(WAVE_ANGLE_DEGREES);
        float dirX = MathHelper.sin(ang);
        float dirZ = -MathHelper.cos(ang);

        Vec3d camPos = mc.gameRenderer.getCamera().getPos();

        if (uTime != null) uTime.setFloat(timeSeconds);
        if (uAmp != null) uAmp.setFloat(WAVE_AMPLITUDE);
        if (uFreq != null) uFreq.setFloat(waveFrequency);
        if (uSpeed != null) uSpeed.setFloat(WAVE_SPEED);
        if (uDir != null) uDir.set(dirX, dirZ);
        if (uCam != null) uCam.set((float) camPos.x, (float) camPos.z);
    }
}
