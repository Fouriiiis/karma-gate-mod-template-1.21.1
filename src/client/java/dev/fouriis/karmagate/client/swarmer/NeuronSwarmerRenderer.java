package dev.fouriis.karmagate.client.swarmer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fouriis.karmagate.KarmaGateMod;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renders neuron swarmers as glowing billboard particles.
 */
public class NeuronSwarmerRenderer {
    private static final Identifier NEURON_TEXTURE = Identifier.of(KarmaGateMod.MOD_ID, "textures/particle/neuron.png");
    
    // Size of the swarmer sprite in blocks
    private static final float SPRITE_SIZE = 0.25f;
    
    // Glow effect intensity
    private static final float GLOW_INTENSITY = 1.0f;
    
    // Glow radius multiplier for the outer glow layer
    private static final float GLOW_SCALE = 2.5f;
    
    private static boolean initialized = false;
    
    /**
     * Registers the renderer with Fabric's world render events.
     */
    public static void register() {
        if (initialized) return;
        initialized = true;
        
        // Render after translucent blocks for proper blending
        WorldRenderEvents.AFTER_TRANSLUCENT.register(NeuronSwarmerRenderer::render);
    }
    
    /**
     * Main render method called each frame.
     */
    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        
        List<NeuronSwarmer> swarmers = NeuronSwarmerManager.getInstance().getAllSwarmers();
        if (swarmers.isEmpty()) return;
        
        MatrixStack matrices = context.matrixStack();
        Camera camera = context.camera();
        float tickDelta = context.tickCounter().getTickDelta(true);
        
        Vec3d cameraPos = camera.getPos();
        
        // Set up rendering state for additive glow blending
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        // Enable depth writing so swarmers write to depth buffer
        // This allows projector (rendered later) to be occluded by swarmers
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull(); // Disable face culling so particles are visible from both sides
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, NEURON_TEXTURE);
        
        BufferBuilder buffer = Tessellator.getInstance().begin(
            VertexFormat.DrawMode.QUADS, 
            VertexFormats.POSITION_TEXTURE_COLOR
        );
        
        // Get camera rotation for billboarding
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();
        
        matrices.push();
        
        // Translate to camera-relative origin
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        
        for (NeuronSwarmer swarmer : swarmers) {
            // Render outer glow layer first (larger, more transparent)
            renderSwarmerGlow(buffer, matrix, swarmer, cameraPos, cameraYaw, cameraPitch, tickDelta);
            // Render core (smaller, brighter)
            renderSwarmer(buffer, matrix, swarmer, cameraPos, cameraYaw, cameraPitch, tickDelta);
        }
        
        matrices.pop();
        
        // Draw the buffer
        BuiltBuffer builtBuffer = buffer.endNullable();
        if (builtBuffer != null) {
            BufferRenderer.drawWithGlobalProgram(builtBuffer);
        }
        
        // Restore render state - keep depth mask true (default state)
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull(); // Re-enable face culling
    }
    
    /**
     * Renders a single swarmer as a billboard quad.
     */
    private static void renderSwarmer(
            BufferBuilder buffer, 
            Matrix4f matrix,
            NeuronSwarmer swarmer, 
            Vec3d cameraPos,
            float cameraYaw,
            float cameraPitch,
            float tickDelta) {
        
        // Interpolate position
        double x = MathHelper.lerp(tickDelta, swarmer.lastPosition.x, swarmer.position.x);
        double y = MathHelper.lerp(tickDelta, swarmer.lastPosition.y, swarmer.position.y);
        double z = MathHelper.lerp(tickDelta, swarmer.lastPosition.z, swarmer.position.z);
        
        // Interpolate rotation for visual effect
        float rotation = MathHelper.lerp(tickDelta, swarmer.lastRotation, swarmer.rotation);
        
        // Calculate color from swarmer's color values
        // colorX determines hue (0 = cyan, 0.5 = purple, 1 = pink/magenta)
        // colorY determines brightness/saturation
        float[] rgb = calculateColor(swarmer.colorX, swarmer.colorY);
        int r = (int)(rgb[0] * 255);
        int g = (int)(rgb[1] * 255);
        int b = (int)(rgb[2] * 255);
        int a = (int)(GLOW_INTENSITY * 255);
        
        // Camera basis (for billboarding)
        float camYawRad = (float) Math.toRadians(-cameraYaw);
        float camPitchRad = (float) Math.toRadians(cameraPitch);
        float baseRightX = (float) Math.cos(camYawRad);
        float baseRightZ = (float) Math.sin(camYawRad);
        float baseUpX = (float) (-Math.sin(camYawRad) * Math.sin(camPitchRad));
        float baseUpY = (float) Math.cos(camPitchRad);
        float baseUpZ = (float) (Math.cos(camYawRad) * Math.sin(camPitchRad));

        // Determine movement yaw; fall back to camera yaw if direction is tiny
        Vec3d moveDir = swarmer.direction;
        double mvLen = moveDir.length();
        float moveYaw;
        if (mvLen > 1e-4) {
            moveYaw = (float) Math.atan2(moveDir.z, moveDir.x);
        } else {
            moveYaw = (float) Math.toRadians(-cameraYaw);
        }

            float[] yawOffsets = new float[] { moveYaw, moveYaw + (float)(Math.PI / 2.0) };

        float size = SPRITE_SIZE * (1.0f + 0.1f * (float) Math.sin(rotation * Math.PI * 2));

        for (float yawOff : yawOffsets) {
            // Rotate base camera right/up by yawOff around Y to face movement direction
            float cosA = (float) Math.cos(yawOff);
            float sinA = (float) Math.sin(yawOff);

            float rx = (baseRightX * cosA - baseRightZ * sinA) * size;
            float rz = (baseRightX * sinA + baseRightZ * cosA) * size;

            float ux = (baseUpX * cosA - baseUpZ * sinA) * size;
            float uz = (baseUpX * sinA + baseUpZ * cosA) * size;
            float uy = baseUpY * size;

            // Bottom-left
            buffer.vertex(matrix, (float)(x - rx - ux), (float)(y - uy), (float)(z - rz - uz))
                .texture(0, 1)
                .color(r, g, b, a);

            // Bottom-right
            buffer.vertex(matrix, (float)(x + rx - ux), (float)(y - uy), (float)(z + rz - uz))
                .texture(1, 1)
                .color(r, g, b, a);

            // Top-right
            buffer.vertex(matrix, (float)(x + rx + ux), (float)(y + uy), (float)(z + rz + uz))
                .texture(1, 0)
                .color(r, g, b, a);

            // Top-left
            buffer.vertex(matrix, (float)(x - rx + ux), (float)(y + uy), (float)(z - rz + uz))
                .texture(0, 0)
                .color(r, g, b, a);
        }
    }
    
    /**
     * Calculates RGB color from the swarmer's color parameters.
     * Based on Rain World's color mapping for oracle swarmers.
     */
    private static float[] calculateColor(float colorX, float colorY) {
        // Map colorX to hue:
        // 0.0 -> cyan (0.44 in the code, which is ~160 degrees)
        // 0.5 -> purple (0.66 in the code, which is ~240 degrees)
        // 1.0 -> magenta/pink (0.997 in the code, which is ~359 degrees)
        
        float hue;
        if (colorX < 0.5f) {
            // Lerp from cyan to purple
            hue = lerp(0.5f, 0.75f, colorX * 2);
        } else {
            // Lerp from purple to magenta
            hue = lerp(0.75f, 0.9f, (colorX - 0.5f) * 2);
        }
        
        // Saturation is always high
        float saturation = 1.0f;
        
        // Lightness affected by colorY
        float lightness = 0.5f + 0.3f * colorY;
        
        return hslToRgb(hue, saturation, lightness);
    }
    
    /**
     * Converts HSL to RGB.
     */
    private static float[] hslToRgb(float h, float s, float l) {
        float r, g, b;
        
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f/3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f/3f);
        }
        
        return new float[]{r, g, b};
    }
    
    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f/6f) return p + (q - p) * 6 * t;
        if (t < 1f/2f) return q;
        if (t < 2f/3f) return p + (q - p) * (2f/3f - t) * 6;
        return p;
    }
    
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
    
    /**
     * Renders the outer glow layer of a swarmer.
     */
    private static void renderSwarmerGlow(
            BufferBuilder buffer, 
            Matrix4f matrix,
            NeuronSwarmer swarmer, 
            Vec3d cameraPos,
            float cameraYaw,
            float cameraPitch,
            float tickDelta) {
        
        // Interpolate position
        double x = MathHelper.lerp(tickDelta, swarmer.lastPosition.x, swarmer.position.x);
        double y = MathHelper.lerp(tickDelta, swarmer.lastPosition.y, swarmer.position.y);
        double z = MathHelper.lerp(tickDelta, swarmer.lastPosition.z, swarmer.position.z);
        
        // Interpolate rotation for visual effect
        float rotation = MathHelper.lerp(tickDelta, swarmer.lastRotation, swarmer.rotation);
        
        // Calculate color - glow uses same hue but lower alpha
        float[] rgb = calculateColor(swarmer.colorX, swarmer.colorY);
        int r = (int)(rgb[0] * 255);
        int g = (int)(rgb[1] * 255);
        int b = (int)(rgb[2] * 255);
        int a = (int)(0.3f * 255); // Lower alpha for glow
        
        // Billboard rotation vectors
        float yawRad = (float) Math.toRadians(-cameraYaw);
        float pitchRad = (float) Math.toRadians(cameraPitch);
        
        float rightX = (float) Math.cos(yawRad);
        float rightZ = (float) Math.sin(yawRad);
        
        float upX = (float) (-Math.sin(yawRad) * Math.sin(pitchRad));
        float upY = (float) Math.cos(pitchRad);
        float upZ = (float) (Math.cos(yawRad) * Math.sin(pitchRad));
        
        // Larger size for glow
        float size = SPRITE_SIZE * GLOW_SCALE;
        
        // Pulsing effect
        float pulse = 1.0f + 0.2f * (float) Math.sin(rotation * Math.PI * 2);
        size *= pulse;
        
        float rx = rightX * size;
        float rz = rightZ * size;
        float ux = upX * size;
        float uy = upY * size;
        float uz = upZ * size;
        
        buffer.vertex(matrix, (float)(x - rx - ux), (float)(y - uy), (float)(z - rz - uz))
            .texture(0, 1)
            .color(r, g, b, a);
        
        buffer.vertex(matrix, (float)(x + rx - ux), (float)(y - uy), (float)(z + rz - uz))
            .texture(1, 1)
            .color(r, g, b, a);
        
        buffer.vertex(matrix, (float)(x + rx + ux), (float)(y + uy), (float)(z + rz + uz))
            .texture(1, 0)
            .color(r, g, b, a);
        
        buffer.vertex(matrix, (float)(x - rx + ux), (float)(y + uy), (float)(z - rz + uz))
            .texture(0, 0)
            .color(r, g, b, a);
    }
}
