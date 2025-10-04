package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.item.GateLightItem;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class GateLightItemModel extends GeoModel<GateLightItem> {
    private static final Identifier MODEL   = Identifier.of(KarmaGateMod.MOD_ID, "geo/gate_light.geo.json");
    private static final Identifier TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/gate_light.png");
    private static final Identifier ANIM    = Identifier.of(KarmaGateMod.MOD_ID, "animations/gate_light.animation.json");

    @Override public Identifier getModelResource(GateLightItem animatable) { return MODEL; }
    @Override public Identifier getTextureResource(GateLightItem animatable) { return TEXTURE; }
    @Override public Identifier getAnimationResource(GateLightItem animatable) { return ANIM; }
}
