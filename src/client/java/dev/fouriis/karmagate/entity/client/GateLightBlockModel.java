package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.karmagate.GateLightBlockEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class GateLightBlockModel extends GeoModel<GateLightBlockEntity> {

    @Override
    public Identifier getModelResource(GateLightBlockEntity animatable) {
        Identifier modelId = Identifier.of(KarmaGateMod.MOD_ID, "geo/gate_light.geo.json");
        return modelId;
    }

    @Override
    public Identifier getTextureResource(GateLightBlockEntity animatable) {
        Identifier textureId = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/gate_light.png");
        return textureId;
    }

    @Override
    public Identifier getAnimationResource(GateLightBlockEntity animatable) {
        Identifier animationId = Identifier.of(KarmaGateMod.MOD_ID, "animations/gate_light.animation.json");
        return animationId;
    }
}
