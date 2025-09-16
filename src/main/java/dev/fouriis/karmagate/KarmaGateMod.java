package dev.fouriis.karmagate;

import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.ModBlockEntities;

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

        LOGGER.info("Hello Fabric world!");
    }
}
