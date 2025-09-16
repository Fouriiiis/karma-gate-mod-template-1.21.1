package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class KarmaGateBlockModel extends GeoModel<KarmaGateBlockEntity> {

    @Override
    public Identifier getModelResource(KarmaGateBlockEntity animatable) {
        Identifier modelId = Identifier.of(KarmaGateMod.MOD_ID, "geo/karma_gate.geo.json");
        return modelId;
    }

    @Override
    public Identifier getTextureResource(KarmaGateBlockEntity animatable) {
        Identifier textureId = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/karma_gate.png");
        return textureId;
    }

    @Override
    public Identifier getAnimationResource(KarmaGateBlockEntity animatable) {
        Identifier animationId = Identifier.of(KarmaGateMod.MOD_ID, "animations/karma_gate.animation.json");
        return animationId;
    }
}
