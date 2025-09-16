package dev.fouriis.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.client.KarmaGateBlockRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public class KarmaGateModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		
		// Register block entity renderer
		BlockEntityRendererFactories.register(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, KarmaGateBlockRenderer::new);
	}
}