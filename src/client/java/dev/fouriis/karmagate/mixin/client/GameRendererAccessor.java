// dev/fouriis/karmagate/mixin/client/GameRendererAccessor.java
package dev.fouriis.karmagate.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("bobView")
    void karmaGate$invokeBobView(MatrixStack matrices, float tickDelta);

    @Invoker("getFov")
    double karmaGate$invokeGetFov(Camera camera, float tickDelta, boolean changingFov);
}
