package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.shelterdoor.ShelterDoorBlockEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class ShelterDoorModel extends GeoModel<ShelterDoorBlockEntity> {
    @Override
    public Identifier getModelResource(ShelterDoorBlockEntity animatable) {
        return Identifier.of(KarmaGateMod.MOD_ID, "geo/shelter.geo.json");
    }

    @Override
    public Identifier getTextureResource(ShelterDoorBlockEntity animatable) {
        return Identifier.of(KarmaGateMod.MOD_ID, "textures/block/shelter.png");
    }

    @Override
    public Identifier getAnimationResource(ShelterDoorBlockEntity animatable) {
        return Identifier.of(KarmaGateMod.MOD_ID, "animations/shelter.animation.json");
    }
}
