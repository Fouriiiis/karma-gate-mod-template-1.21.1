package dev.fouriis.karmagate;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.particle.ModParticles;
import dev.fouriis.karmagate.sound.ModSounds;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KarmaGateMod implements ModInitializer {
    public static final String MOD_ID = "karma-gate-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Register mod content
        ModBlocks.registerModBlocks();
        ModBlockEntities.registerBlockEntities();
        ModParticles.register();
        ModSounds.registerModSounds();

        LOGGER.info("Hello Fabric world!");
    }
}
