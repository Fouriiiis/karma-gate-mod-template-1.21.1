// dev/fouriis/karmagate/client/AtcSkyFabricAdapter.java
package dev.fouriis.karmagate.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.joml.Matrix4f;

public final class AtcSkyFabricAdapter {
    private AtcSkyFabricAdapter() {}

    public static void render(WorldRenderContext ctx) {
        float tickDelta = 0f;
        if (ctx.tickCounter() != null) tickDelta = ctx.tickCounter().getTickDelta(false);

        Matrix4f modelView = (ctx.matrixStack() != null)
                ? new Matrix4f(ctx.matrixStack().peek().getPositionMatrix())
                : new Matrix4f();

        Matrix4f projection = (ctx.projectionMatrix() != null)
                ? new Matrix4f(ctx.projectionMatrix())
                : new Matrix4f();

        AtcSkyRenderer.renderSkybox(modelView, projection, tickDelta, ctx.camera());
    }
}
