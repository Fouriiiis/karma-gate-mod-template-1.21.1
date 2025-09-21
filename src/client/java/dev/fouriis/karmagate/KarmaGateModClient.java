package dev.fouriis.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.client.GateLightBlockRenderer;
import dev.fouriis.karmagate.entity.client.HeatCoilRenderer;
import dev.fouriis.karmagate.entity.client.KarmaGateBlockRenderer;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.particle.SteamParticle;
import dev.fouriis.karmagate.particle.WaterStreamParticle;
import dev.fouriis.karmagate.sound.SteamAudioController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public class KarmaGateModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		
		// Register block entity renderer
		BlockEntityRendererFactories.register(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, KarmaGateBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.GATE_LIGHT_BLOCK_ENTITY, GateLightBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, HeatCoilRenderer::new);

		ParticleFactoryRegistry.getInstance().register(ModParticles.WATER_STREAM, sprites -> new WaterStreamParticle.Factory(sprites));
		ParticleFactoryRegistry.getInstance().register(ModParticles.STEAM, sprites -> new SteamParticle.Factory(sprites));
		
		ClientTickEvents.END_CLIENT_TICK.register(client -> {SteamAudioController.get().clientTick();
});
	}
}