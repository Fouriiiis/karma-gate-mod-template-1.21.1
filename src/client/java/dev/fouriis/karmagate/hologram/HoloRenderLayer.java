// dev/fouriis/karmagate/client/hologram/HoloRenderLayer.java
package dev.fouriis.karmagate.hologram;

import dev.fouriis.karmagate.client.hologram.HologramShaders;
import net.minecraft.client.render.*;
import net.minecraft.util.Identifier;

public final class HoloRenderLayer {
    public static RenderLayer get(Identifier sheet, Identifier noise) {
    // If our shader isn't ready yet (e.g., very early after resource reload),
    // fall back to a standard translucent layer to avoid null shader crashes.
    if (dev.fouriis.karmagate.client.hologram.HologramShaders.PROGRAM == null) {
        return RenderLayer.getEntityTranslucent(sheet);
    }
    RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
        .program(HologramShaders.phase())
        .texture(new RenderPhase.Texture(sheet, false, false))
        .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
        .cull(RenderPhase.DISABLE_CULLING)
        .lightmap(RenderPhase.ENABLE_LIGHTMAP)
    .build(true);
    return RenderLayer.of(
        "karma_holo",
        VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
        VertexFormat.DrawMode.QUADS,
        256,
        true, true, params
    );
    }
}
