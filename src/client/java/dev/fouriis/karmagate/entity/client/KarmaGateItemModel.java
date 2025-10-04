package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.item.KarmaGateItem;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo model for the Karma Gate item. Reuses the same geometry/texture
 * as the block entity model, but targets the item type to satisfy
 * GeoItemRenderer's generics. No animations are driven from the item.
 */
public class KarmaGateItemModel extends GeoModel<KarmaGateItem> {

    @Override
    public Identifier getModelResource(KarmaGateItem animatable) {
        return Identifier.of(KarmaGateMod.MOD_ID, "geo/karma_gate.geo.json");
    }

    @Override
    public Identifier getTextureResource(KarmaGateItem animatable) {
        return Identifier.of(KarmaGateMod.MOD_ID, "textures/block/karma_gate.png");
    }

    @Override
    public Identifier getAnimationResource(KarmaGateItem animatable) {
        // It's fine to return the same animation file; the item defines no controllers,
        // so nothing will play. Alternatively, point to a no-op animation.
        return Identifier.of(KarmaGateMod.MOD_ID, "animations/karma_gate.animation.json");
    }
}
