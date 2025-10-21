package dev.fouriis.karmagate.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix4f;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.client.render.RenderPhase.*;

public final class DistantStructuresRenderer {

    private static final Identifier CONFIG_ID = Identifier.of("karma-gate-mod", "structures/distant_structures.json");
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;
    private static MatrixStack bobbedStack = null;

    /** Billboard entry in world space (units = blocks). */
    public record Entry(
            Identifier texture,
            double x, double y, double z,
            float width, float height,
            boolean emissive,
            boolean alwaysVisible // when true, clamp to far plane sphere but keep world anchoring
    ) {}

    private DistantStructuresRenderer() {}

    public static void init() {
        WorldRenderEvents.LAST.register(ctx -> {
            ensureLoaded();
            if (ENTRIES.isEmpty() || ctx.consumers() == null || ctx.camera() == null) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            MatrixStack matrices = bobbedStack == null ? ctx.matrixStack() : bobbedStack;
            Vec3d camPos = ctx.camera().getPos();
            float tickDelta = ctx.tickCounter().getTickDelta(true);

            Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());

            double fov = mc.gameRenderer.getFov(ctx.camera(), tickDelta, true);
            float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
            float near = 0.0001f;
            float far = (float) (mc.options.getClampedViewDistance() * 16.0 * 100.0);
            Matrix4f extendedProj = RenderSystem.getProjectionMatrix().setPerspective((float) Math.toRadians(fov), aspect, near, far);
            RenderSystem.setProjectionMatrix(extendedProj, VertexSorter.BY_DISTANCE);

            VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

            List<Entry> sorted = new ArrayList<>(ENTRIES);
            sorted.sort((a, b) -> Double.compare(
                    camPos.squaredDistanceTo(b.x, b.y, b.z),
                    camPos.squaredDistanceTo(a.x, a.y, a.z)
            ));

            for (Entry e : sorted) {
                // World-space target and camera-relative vector
                Vec3d target = new Vec3d(e.x, e.y, e.z);
                Vec3d rel = target.subtract(camPos);
                // If marked alwaysVisible, clamp to just inside the extended far plane so it never gets clipped
                if (e.alwaysVisible) {
                    double dist = rel.length();
                    double maxDist = Math.max(1.0, far * 0.98);
                    if (dist > maxDist) {
                        rel = rel.normalize().multiply(maxDist);
                    }
                }
                // Billboard yaw so the quad faces the camera (around Y axis)
                float yawRad = (float) Math.atan2(-rel.x, -rel.z);
                if (Float.isNaN(yawRad)) yawRad = 0f;

                matrices.push();
                matrices.translate(rel.x, rel.y, rel.z);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));
                matrices.scale(e.width, e.height, 1f);

                VertexConsumer vc = immediate.getBuffer(customRenderLayer(e.texture()));
                int packedLight;
                if (e.emissive) {
                    packedLight = LightmapTextureManager.pack(15, 15);
                } else {
                    var world = mc.world;
                    int bx = MathHelper.floor(target.x);
                    int by = MathHelper.floor(target.y);
                    int bz = MathHelper.floor(target.z);
                    int block = world != null ? world.getLightLevel(LightType.BLOCK, new net.minecraft.util.math.BlockPos(bx, by, bz)) : 15;
                    int sky   = world != null ? world.getLightLevel(LightType.SKY,   new net.minecraft.util.math.BlockPos(bx, by, bz)) : 15;
                    packedLight = LightmapTextureManager.pack(block, sky);
                }
                renderQuad(vc, matrices, 1, 1, packedLight);

                matrices.pop();
            }

            immediate.draw();

            RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
            bobbedStack = null;
        });
    }

    public static void bobView(MatrixStack matrices, float tickDelta) {
        bobViewInverse(matrices, tickDelta);
        MinecraftClient mc = MinecraftClient.getInstance();
        GameRenderer gameRenderer = mc.gameRenderer;
        if (gameRenderer.getClient().getCameraEntity() instanceof PlayerEntity) {
            PlayerEntity playerEntity = (PlayerEntity)gameRenderer.getClient().getCameraEntity();
            float f = playerEntity.horizontalSpeed - playerEntity.prevHorizontalSpeed;
            float g = -(playerEntity.horizontalSpeed + f * tickDelta);
            float h = MathHelper.lerp(tickDelta, playerEntity.prevStrideDistance, playerEntity.strideDistance);
            matrices.translate(MathHelper.sin(g * (float)Math.PI) * h * 0.5F, -Math.abs(MathHelper.cos(g * (float)Math.PI) * h), 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(g * (float)Math.PI) * h * 3.0F));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(Math.abs(MathHelper.cos(g * (float)Math.PI - 0.2F) * h) * 5.0F));
        }
    }

    public static void bobViewInverse(MatrixStack matrices, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        GameRenderer gameRenderer = mc.gameRenderer;
        if (gameRenderer.getClient().getCameraEntity() instanceof PlayerEntity) {

            PlayerEntity playerEntity = (PlayerEntity)gameRenderer.getClient().getCameraEntity();
            float f = playerEntity.horizontalSpeed - playerEntity.prevHorizontalSpeed;
            float g = -(playerEntity.horizontalSpeed + f * tickDelta);
            float h = MathHelper.lerp(tickDelta, playerEntity.prevStrideDistance, playerEntity.strideDistance);
            matrices.translate(MathHelper.sin(g * (float)Math.PI) * h * 0.5F, -Math.abs(MathHelper.cos(g * (float)Math.PI) * h), 0.0F);
            matrices.multiply(RotationAxis.NEGATIVE_Z.rotationDegrees(MathHelper.sin(g * (float)Math.PI) * h * 3.0F));
            matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(Math.abs(MathHelper.cos(g * (float)Math.PI - 0.2F) * h) * 5.0F));
            bobbedStack = matrices;
        }
    }

    /* ---------- render helpers (TEXT_SEE_THROUGH expects POSITION_COLOR_TEXTURE_LIGHT) ---------- */

    private static void renderQuad(VertexConsumer vc, MatrixStack matrices, float width, float height, int light) {
        MatrixStack.Entry me = matrices.peek();
        Matrix4f model = me.getPositionMatrix();

        float halfW = width * 0.5f;
        float bottom = 0f;
        int r = 255, g = 255, b = 255, a = 255;

        vc.vertex(model, -halfW, bottom + height, 0).color(r, g, b, a).texture(0f, 0f).light(light);
        vc.vertex(model,  halfW, bottom + height, 0).color(r, g, b, a).texture(1f, 0f).light(light);
        vc.vertex(model,  halfW, bottom,          0).color(r, g, b, a).texture(1f, 1f).light(light);
        vc.vertex(model, -halfW, bottom,          0).color(r, g, b, a).texture(0f, 1f).light(light);
    }

    /* ---------- config ---------- */

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            var opt = rm.getResource(CONFIG_ID);
            if (opt.isPresent()) {
                Resource res = opt.get();
                try (var in = res.getInputStream(); var reader = new InputStreamReader(in)) {
                    JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        Identifier tex = Identifier.of(o.get("texture").getAsString());
                        double x = o.get("x").getAsDouble();
                        double y = o.get("y").getAsDouble();
                        double z = o.get("z").getAsDouble();
                        float w = o.has("width") ? o.get("width").getAsFloat() : 64f;
                        float h = o.has("height") ? o.get("height").getAsFloat() : 64f;
                        boolean emissive = o.has("emissive") && o.get("emissive").getAsBoolean(); // unused by see-through layer
                        boolean always   = o.has("alwaysVisible") && o.get("alwaysVisible").getAsBoolean();
                        ENTRIES.add(new Entry(tex, x, y, z, w, h, emissive, always));
                    }
                }
            } else {
                autoGenerateEntries(ENTRIES);
            }
        } catch (Exception ignored) {}

        // Debug: one at world origin; mark alwaysVisible so it demonstrates unclipped rendering
        ENTRIES.add(0, new Entry(
                Identifier.of("karma-gate-mod", "structures/atc_spire1.png"),
                0.0, 0.0, 0.0,
                32f, 48f,
                true,
                true
        ));
    }

    private static void autoGenerateEntries(List<Entry> list) {
        String[] names = {
                "atc_spire1.png","atc_spire2.png","atc_spire3.png","atc_spire4.png","atc_spire5.png","atc_spire6.png","atc_spire7.png","atc_spire8.png","atc_spire9.png",
                "atc_structure1.png","atc_structure2.png","atc_structure3.png","atc_structure4.png","atc_structure5.png","atc_structure6.png"
        };
        double radius = 3000.0, y = 160.0;
        for (int i = 0; i < names.length; i++) {
            double ang = (Math.PI * 2.0) * i / names.length;
            double x = Math.sin(ang) * radius;
            double z = Math.cos(ang) * radius;
            Identifier tex = Identifier.of("karma-gate-mod", "structures/" + names[i]);
            list.add(new Entry(tex, x, y, z, 96f, 128f, true, true));
        }
    }

    public static RenderLayer customRenderLayer(Identifier texture) {
        return RenderLayer.of(
                "text_see_through",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                        .texture(new RenderPhase.Texture(texture, false, true))
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .lightmap(ENABLE_LIGHTMAP)
                        .depthTest(LEQUAL_DEPTH_TEST)
                        .writeMaskState(ALL_MASK)
                        .build(false)
        );
    }
}