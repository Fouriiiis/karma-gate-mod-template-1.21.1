// dev/fouriis/karmagate/mixin/client/WorldRendererMixin_Sky.java
package dev.fouriis.karmagate.mixin.client;

import dev.fouriis.karmagate.client.AtcSkyRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin_Sky {
    @Inject(
        method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void karmaGate$replaceSky(Matrix4f modelView, Matrix4f projection,
                                      float tickDelta, Camera camera,
                                      boolean thickFog, Runnable clouds,
                                      CallbackInfo ci) {
        AtcSkyRenderer.renderSkybox(modelView, projection, tickDelta, camera);
        ci.cancel();
    }
}
