package dev.fouriis.karmagate.item;

import dev.fouriis.karmagate.entity.client.KarmaGateItemModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
public class KarmaGateItemRenderer extends GeoItemRenderer<KarmaGateItem> {
    public KarmaGateItemRenderer() {
        super(new KarmaGateItemModel());
    }
}
