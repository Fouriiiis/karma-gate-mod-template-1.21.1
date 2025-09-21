package dev.fouriis.karmagate.entity;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.ModBlocks;
import dev.fouriis.karmagate.block.karmagate.KarmaGatePartBlock;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.WaterStreamBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.GateLightBlockEntity;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {


    public static final BlockEntityType<KarmaGateBlockEntity> KARMA_GATE_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_block_entity"),
            FabricBlockEntityTypeBuilder.create(KarmaGateBlockEntity::new, ModBlocks.KARMA_GATE).build());

    public static final BlockEntityType<GateLightBlockEntity> GATE_LIGHT_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "gate_light_block_entity"),
            FabricBlockEntityTypeBuilder.create(GateLightBlockEntity::new, ModBlocks.GATE_LIGHT).build());

    public static final BlockEntityType<HeatCoilBlockEntity> HEAT_COIL_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "heat_coil_block_entity"),
            FabricBlockEntityTypeBuilder.create(HeatCoilBlockEntity::new, ModBlocks.HEAT_COIL).build());

    public static final BlockEntityType<WaterStreamBlockEntity> WATER_STREAM_BLOCK_ENTITY =
        Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "water_stream_block_entity"),
            FabricBlockEntityTypeBuilder.create(WaterStreamBlockEntity::new, ModBlocks.WATER_STREAM).build());

            public static final BlockEntityType<KarmaGatePartBlock.PartBE> KARMA_GATE_PART_BE =
        Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(KarmaGateMod.MOD_ID, "karma_gate_part_be"),
            BlockEntityType.Builder.create(KarmaGatePartBlock.PartBE::new, ModBlocks.KARMA_GATE_PART).build(null)
        );
    


    public static void registerBlockEntities() {
        KarmaGateMod.LOGGER.info("Registering Block Entities for " + KarmaGateMod.MOD_ID);
    }
}
