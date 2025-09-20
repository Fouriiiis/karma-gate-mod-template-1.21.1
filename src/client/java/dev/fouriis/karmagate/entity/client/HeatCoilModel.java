package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.entity.karmagate.HeatCoilBlockEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class HeatCoilModel extends GeoModel<HeatCoilBlockEntity> {
    private static final Identifier MODEL     = Identifier.of(KarmaGateMod.MOD_ID, "geo/heat_coil.geo.json");
    private static final Identifier TEXTURE   = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/heat_coil.png");
    // The emissive texture (your “hot” texture)
    public static final Identifier EMISSIVE   = Identifier.of(KarmaGateMod.MOD_ID, "textures/block/heat_coil_hot.png");

    @Override public Identifier getModelResource(HeatCoilBlockEntity animatable)   { return MODEL; }
    @Override public Identifier getTextureResource(HeatCoilBlockEntity animatable) { return TEXTURE; }
    @Override public Identifier getAnimationResource(HeatCoilBlockEntity a)        { return null; } // none needed
}
