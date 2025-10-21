package dev.fouriis.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.client.GateLightBlockRenderer;
import dev.fouriis.karmagate.entity.client.HeatCoilRenderer;
import dev.fouriis.karmagate.entity.client.KarmaGateBlockRenderer;
import dev.fouriis.karmagate.item.KarmaGateItemGeoRenderer;
import dev.fouriis.karmagate.entity.client.HeatCoilItemModel;
import dev.fouriis.karmagate.entity.client.GateLightItemModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import dev.fouriis.karmagate.hologram.HologramProjectorRenderer;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.particle.SteamParticle;
import dev.fouriis.karmagate.particle.WaterStreamParticle;
import dev.fouriis.karmagate.sound.SteamAudioController;
import dev.fouriis.karmagate.sound.ModSounds;
import dev.fouriis.karmagate.sound.MultiSound;
import dev.fouriis.karmagate.sound.GateAudioSpecs;
import dev.fouriis.karmagate.sound.MultiSound.Spec;
import net.minecraft.registry.Registries;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import java.util.Map;
import java.util.HashMap;
import dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity;

public class KarmaGateModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.

		// Register distant structure billboards
		//dev.fouriis.karmagate.client.DistantStructuresRenderer.init();
		
		// Register block entity renderer
		BlockEntityRendererFactories.register(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, KarmaGateBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.GATE_LIGHT_BLOCK_ENTITY, GateLightBlockRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.HEAT_COIL_BLOCK_ENTITY, HeatCoilRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.HOLOGRAM_PROJECTOR, HologramProjectorRenderer::new);

		ParticleFactoryRegistry.getInstance().register(ModParticles.WATER_STREAM, sprites -> new WaterStreamParticle.Factory(sprites));
		ParticleFactoryRegistry.getInstance().register(ModParticles.STEAM, sprites -> new SteamParticle.Factory(sprites));

		// Register Karma Gate item renderer with custom transforms
		var gateItemRenderer = new KarmaGateItemGeoRenderer();
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.KARMA_GATE.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> gateItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay)
		);

		// Heat Coil item renderer (simple small centered)
		var heatCoilItemRenderer = new GeoItemRenderer<>(new HeatCoilItemModel());
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.HEAT_COIL.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> {
				matrices.push();
				// Increased size by 100% (0.18 -> 0.36). Drop slightly to keep centered visually.
				matrices.translate(0.5f, 0.40f, 0.5f);
				matrices.scale(0.36f, 0.36f, 0.36f);
				matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(35f));
				matrices.translate(-0.5f, -0.5f, -0.5f);
				heatCoilItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay);
				matrices.pop();
			}
		);

		// Gate Light item renderer
		var gateLightItemRenderer = new GeoItemRenderer<>(new GateLightItemModel());
		BuiltinItemRendererRegistry.INSTANCE.register(
			dev.fouriis.karmagate.block.ModBlocks.GATE_LIGHT.asItem(),
			(stack, mode, matrices, vertexConsumers, light, overlay) -> {
				matrices.push();
				// Increased size by 100% (0.22 -> 0.44). Lower slightly to keep within slot.
				matrices.translate(0.5f, 0.43f, 0.5f);
				matrices.scale(0.44f, 0.44f, 0.44f);
				matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(45f));
				matrices.translate(-0.5f, -0.5f, -0.5f);
				gateLightItemRenderer.render(stack, mode, matrices, vertexConsumers, light, overlay);
				matrices.pop();
			}
		);



		// Install client implementation for audio shim
		final Map<BlockPos, MultiSound.Handle> clampLoops = new HashMap<>();
		final Map<BlockPos, MultiSound.Handle> screwLoops = new HashMap<>();

		ModSounds.setAudio(new ModSounds.AudioImpl() {
			@Override
			public void onSteamBurst(net.minecraft.util.math.BlockPos pos, float intensity01, net.minecraft.sound.SoundEvent loopEvent) {
				SteamAudioController.get().onSteamBurst(pos, intensity01, loopEvent);
			}

			@Override
			public void onTimelineEvent(BlockPos pos, String token) {
				KarmaGateMod.LOGGER.info("[AudioClient] token '{}' at {}", token, pos);
				Spec spec = switch (token) {
					case "Gate_Poles_And_Rails_In" -> GateAudioSpecs.POLES_AND_RAILS_IN;
					case "Gate_Pillows_Move_In" -> GateAudioSpecs.PILLOWS_MOVE_IN;
					case "Gate_Pillows_In_Place" -> GateAudioSpecs.PILLOWS_IN_PLACE;
					case "Gate_Panser_On" -> GateAudioSpecs.PANSER_ON;
					case "Gate_Rails_Collide" -> GateAudioSpecs.RAILS_COLLIDE;
					case "Gate_Secure_Rail_Down" -> GateAudioSpecs.SECURE_RAIL_DOWN;
					case "Gate_Secure_Rail_Slam" -> GateAudioSpecs.CLAMP_COLLISION;
					case "Gate_Bolt" -> GateAudioSpecs.BOLT;
					// Opening tokens
					case "Gate_Secure_Rail_Up" -> GateAudioSpecs.SECURE_RAIL_UP;
					case "Gate_Panser_Off" -> GateAudioSpecs.PANSER_OFF;
					case "Gate_Pillows_Move_Out" -> GateAudioSpecs.PILLOWS_MOVE_OUT;
					case "Gate_Poles_Out" -> GateAudioSpecs.POLES_OUT;
					default -> null;
				};
				if (spec != null) {
					KarmaGateMod.LOGGER.info("[AudioClient] mapped '{}' -> spec with {} clip(s)", token, spec.clips.size());
					MultiSound.playAt(pos, spec);
				} else {
					// Loop token handling
					switch (token) {
						case "ClampLoopStart" -> {
							var key = pos.toImmutable();
							var h = clampLoops.get(key);
							if (h == null || !h.isPlaying()) {
								var nh = MultiSound.playAt(key, GateAudioSpecs.CLAMPS_MOVING_LOOP);
								clampLoops.put(key, nh);
								KarmaGateMod.LOGGER.info("[AudioClient] Clamp loop started @{}", key);
							}
						}
						case "ClampLoopStop" -> {
							var key = pos.toImmutable();
							var h = clampLoops.remove(key);
							if (h != null) h.stop();
							KarmaGateMod.LOGGER.info("[AudioClient] Clamp loop stopped @{}", key);
						}
						case "ScrewLoopStart" -> {
							var key = pos.toImmutable();
							var h = screwLoops.get(key);
							if (h == null || !h.isPlaying()) {
								Spec loopSpec = chooseScrewLoopSpec(key);
								var nh = MultiSound.playAt(key, loopSpec);
								screwLoops.put(key, nh);
								KarmaGateMod.LOGGER.info("[AudioClient] Screw loop started @{}", key);
							}
						}
						case "ScrewLoopStop" -> {
							var key = pos.toImmutable();
							var h = screwLoops.remove(key);
							if (h != null) h.stop();
							KarmaGateMod.LOGGER.info("[AudioClient] Screw loop stopped @{}", key);
						}
						default -> KarmaGateMod.LOGGER.warn("[AudioClient] unmapped token '{}'", token);
					}
				}
			}

			@Override
			public void onSoundKeyframe(net.minecraft.util.math.BlockPos pos, Identifier soundId, float volume, float pitch) {
				var event = Registries.SOUND_EVENT.get(soundId);
				if (event == null) {
					KarmaGateMod.LOGGER.warn("[AudioClient] Unknown sound id from keyframe: {}", soundId);
					return;
				}
				KarmaGateMod.LOGGER.info("[AudioClient] keyframe -> play {} v={} p={} at {}", soundId, volume, pitch, pos);
				var spec = new Spec().add(new MultiSound.Clip(event, volume, pitch));
				MultiSound.playAt(pos, spec);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SteamAudioController.get().clientTick();
		});

		// Clear cached loop references on disconnect or new join to avoid stale sound state after rejoin
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SteamAudioController.get().clear();
			clampLoops.values().forEach(MultiSound.Handle::stop);
			screwLoops.values().forEach(MultiSound.Handle::stop);
			clampLoops.clear();
			screwLoops.clear();
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SteamAudioController.get().clear();
			clampLoops.values().forEach(MultiSound.Handle::stop);
			screwLoops.values().forEach(MultiSound.Handle::stop);
			clampLoops.clear();
			screwLoops.clear();
		});
	}

	private static Spec chooseScrewLoopSpec(BlockPos pos) {
		var world = MinecraftClient.getInstance().world;
		if (world == null) return GateAudioSpecs.ELEC_SCREW;
		int r = 8;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					var be = world.getBlockEntity(pos.add(dx, dy, dz));
					if (be instanceof WaterStreamBlockEntity) {
						return GateAudioSpecs.WATER_SCREW;
					}
				}
			}
		}
		return GateAudioSpecs.ELEC_SCREW;
	}
}