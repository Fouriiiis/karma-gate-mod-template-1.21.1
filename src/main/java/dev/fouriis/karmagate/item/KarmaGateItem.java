package dev.fouriis.karmagate.item;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public class KarmaGateItem extends BlockItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public KarmaGateItem(Block block, Item.Settings settings) {
        super(block, settings);
    }

    // Renderer is registered on the client initializer to avoid client imports here.

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // No animations for this item
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
