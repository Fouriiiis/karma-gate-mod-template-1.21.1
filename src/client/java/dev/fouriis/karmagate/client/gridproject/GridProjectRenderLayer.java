package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;

public final class GridProjectRenderLayer {
    private GridProjectRenderLayer() {}

    private static final Identifier WHITE = Identifier.of("minecraft", "textures/misc/white.png");
    private static final Identifier GLYPHS = Identifier.of("karma-gate-mod", "textures/projector/glyphs.png");

    public static RenderLayer get() {
        if (GridProjectShaders.PROGRAM == null) {
            // Safe fallback to avoid crashes during early resource load.
            return RenderLayer.getEntityTranslucent(GLYPHS);
        }

        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
            .program(GridProjectShaders.phase())
            .texture(new RenderPhase.Texture(GLYPHS, false, false))
            .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
            .cull(RenderPhase.DISABLE_CULLING)
            .lightmap(RenderPhase.ENABLE_LIGHTMAP)
            .build(true);

        return RenderLayer.of(
            "karma_grid_project",
            VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
            VertexFormat.DrawMode.QUADS,
            256,
            true,
            true,
            params
        );
    }
}
