package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.item.HeatCoilItem;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class HeatCoilItemModel extends GeoModel<HeatCoilItem> {
    private static final Identifier MODEL   = Identifier.of(KarmaGateMod.MOD_ID, "geo/heat_coil.geo.json");
    private static final Identifier TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/heat_coil.png");

    @Override public Identifier getModelResource(HeatCoilItem animatable) { return MODEL; }
    @Override public Identifier getTextureResource(HeatCoilItem anim) { return TEXTURE; }
    @Override public Identifier getAnimationResource(HeatCoilItem anim) { return null; }
}
