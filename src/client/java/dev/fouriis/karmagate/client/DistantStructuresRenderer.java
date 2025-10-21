package dev.fouriis.karmagate.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix4f;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.client.render.RenderPhase.*;

/**
 * Renders distant billboard sprites AFTER Iris has done its fog/composite pass.
 * Call {@link #renderLate(float, Camera)} from a mixin injected at the end of WorldRenderer.render().
 */
public final class DistantStructuresRenderer {

    private static final Identifier CONFIG_ID = Identifier.of("karma-gate-mod", "structures/distant_structures.json");
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    /** Billboard entry in world space (units = blocks). */
    public record Entry(
            Identifier texture,
            double x, double y, double z,
            float width, float height,
            boolean emissive,
            boolean alwaysVisible // when true, clamp to far sphere but keep world anchoring
    ) {}

    private DistantStructuresRenderer() {}

    /* ----------------------------------------------------------------------
       PUBLIC: late render entry (call from WorldRenderer mixin @At("RETURN"))
       ---------------------------------------------------------------------- */
    public static void renderLate(float tickDelta, Camera camera) {
        ensureLoaded();
        if (ENTRIES.isEmpty() || camera == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        // Build VIEW = R^{-1} * T(-camPos)  (inverse rotation = transpose for orthonormal rotation)
        Vec3d camPos = camera.getPos();
        Matrix4f view = new Matrix4f()
                .rotation(camera.getRotation())  // camera -> world
                .transpose()                     // world -> camera (inverse)
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // ModelView stack; IMPORTANT: apply vanilla bobbing BEFORE we apply the camera view
        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            // This matches how the world matrix gets a subtle sway so geometry appears to bob.
            mc.gameRenderer.bobView(matrices, tickDelta);
        }
        // Now multiply in our VIEW so subsequent model transforms are world-anchored (and bobbed)
        matrices.peek().getPositionMatrix().mul(view);

        // Extended projection so far objects aren't clipped (Iris already finished its pass)
        double fov = mc.gameRenderer.getFov(camera, tickDelta, true);
        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float near = 0.0001f;
        float far = (float) (mc.options.getClampedViewDistance() * 16.0 * 100.0);
        Matrix4f extendedProj = new Matrix4f().setPerspective((float) Math.toRadians(fov), aspect, near, far);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(extendedProj, VertexSorter.BY_DISTANCE);

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        // Back-to-front sort for translucency
        List<Entry> sorted = new ArrayList<>(ENTRIES);
        sorted.sort((a, b) -> Double.compare(
                camPos.squaredDistanceTo(b.x, b.y, b.z),
                camPos.squaredDistanceTo(a.x, a.y, a.z)
        ));

        for (Entry e : sorted) {
            // World-space target
            Vec3d target = new Vec3d(e.x, e.y, e.z);

            // Camera-relative vector for yaw & (optional) far clamp
            Vec3d rel = target.subtract(camPos);
            Vec3d place = target;
            if (e.alwaysVisible) {
                double dist = rel.length();
                double maxDist = Math.max(1.0, far * 0.98);
                if (dist > maxDist) {
                    rel = rel.normalize().multiply(maxDist);
                    place = camPos.add(rel); // clamp along view ray, but keep world anchoring
                }
            }

            // Face the camera around Y (classic upright billboard)
            float yawRad = (float) Math.atan2(-rel.x, -rel.z);
            if (Float.isNaN(yawRad)) yawRad = 0f;

            matrices.push();
            // Translate by WORLD coordinates (view is already applied to the stack and includes bobbing)
            matrices.translate((float) place.x, (float) place.y, (float) place.z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));
            matrices.scale(e.width, e.height, 1f);

            VertexConsumer vc = immediate.getBuffer(customRenderLayer(e.texture()));

            int packedLight;
            if (e.emissive) {
                packedLight = LightmapTextureManager.pack(15, 15);
            } else {
                var world = mc.world;
                int bx = MathHelper.floor(e.x);
                int by = MathHelper.floor(e.y);
                int bz = MathHelper.floor(e.z);
                int block = world != null ? world.getLightLevel(LightType.BLOCK, new BlockPos(bx, by, bz)) : 15;
                int sky   = world != null ? world.getLightLevel(LightType.SKY,   new BlockPos(bx, by, bz)) : 15;
                packedLight = LightmapTextureManager.pack(block, sky);
            }

            renderQuad(vc, matrices, 1f, 1f, packedLight);
            matrices.pop();
        }

        immediate.draw();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    /* ----------------------------------------------------------------------
       PRIVATE: render helpers
       ---------------------------------------------------------------------- */

    // POSITION_COLOR_TEXTURE_LIGHT
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

    public static RenderLayer customRenderLayer(Identifier texture) {
        return RenderLayer.of(
                "karma_gate_billboard",
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

    /* ----------------------------------------------------------------------
       PRIVATE: config loading
       ---------------------------------------------------------------------- */
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
                        boolean emissive = o.has("emissive") && o.get("emissive").getAsBoolean();
                        boolean always   = o.has("alwaysVisible") && o.get("alwaysVisible").getAsBoolean();
                        ENTRIES.add(new Entry(tex, x, y, z, w, h, emissive, always));
                    }
                }
            } else {
                autoGenerateEntries(ENTRIES);
            }
        } catch (Exception ignored) {}

        // Optional debug: one at world origin
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
}
