package dev.fouriis.karmagate.entity;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {

    public static final BlockEntityType<KarmaGateBlockEntity> KARMA_GATE_BLOCK_ENTITY =
            Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_block_entity"),
                    FabricBlockEntityTypeBuilder.create(KarmaGateBlockEntity::new, ModBlocks.KARMA_GATE).build());

    public static void registerBlockEntities() {
        KarmaGateMod.LOGGER.info("Registering Block Entities for " + KarmaGateMod.MOD_ID);
    }
}
