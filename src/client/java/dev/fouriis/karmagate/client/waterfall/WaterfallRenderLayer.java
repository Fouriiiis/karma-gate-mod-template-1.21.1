package dev.fouriis.karmagate.client.waterfall;

import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;

public final class WaterfallRenderLayer {
    private WaterfallRenderLayer() {}

    private static final Identifier WATER_TEX = Identifier.of("minecraft", "textures/block/water_still.png");

    public static RenderLayer get() {
        if (WaterfallShaders.PROGRAM == null) {
            // Safe fallback.
            return RenderLayer.getEntityTranslucent(WATER_TEX);
        }

        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
            .program(WaterfallShaders.phase())
            .texture(new RenderPhase.Texture(WATER_TEX, false, false))
            .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
            .cull(RenderPhase.DISABLE_CULLING)
            .lightmap(RenderPhase.ENABLE_LIGHTMAP)
            .build(true);

        return RenderLayer.of(
            "karma_waterfall",
            VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
            VertexFormat.DrawMode.QUADS,
            256,
            true, true,
            params
        );
    }
}
