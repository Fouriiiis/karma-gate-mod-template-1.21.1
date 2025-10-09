package dev.fouriis.karmagate.client;

import com.google.gson.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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
            boolean alwaysVisible // when true, clamp to far plane sphere but keep world anchoring
    ) {}

    private DistantStructuresRenderer() {}

    public static void init() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
            ensureLoaded();
            if (ENTRIES.isEmpty() || ctx.consumers() == null || ctx.camera() == null) return;

            final MatrixStack matrices = ctx.matrixStack();
            final Vec3d camPos = ctx.camera().getPos();
            final float camYaw = ctx.camera().getYaw();
            final float camPitch = ctx.camera().getPitch();
            final double farBlocks = computeFarPlaneBlocks(); // far plane distance in world units

            for (Entry e : ENTRIES) {
                // True world target (anchor)
                final Vec3d target = new Vec3d(e.x, e.y, e.z);

                // Clamp to just inside far plane only if needed (no crosshair pinning)
                final Vec3d pos = e.alwaysVisible
                        ? projectToFrustumFar(camPos, camYaw, camPitch, target, e.width, e.height, farBlocks)
                        : target;

                // Preserve apparent size when clamped
                final double trueD  = camPos.distanceTo(target);
                final double proxyD = camPos.distanceTo(pos);
                final float scale = (trueD > 1e-3) ? (float)(proxyD / trueD) : 1.0f;

                // Face world origin (bearing-based, independent of camera)
                float yawDeg = (float) Math.toDegrees(Math.atan2(-e.x, -e.z));
                if (Float.isNaN(yawDeg)) yawDeg = 0f;

                matrices.push();
                matrices.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
                // Extra 90° so the textured face points at the origin as requested
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yawDeg - 90f));

                VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getTextSeeThrough(e.texture()));
                renderQuad(vc, matrices, e.width * scale, e.height * scale, LightmapTextureManager.MAX_LIGHT_COORDINATE);

                matrices.pop();
            }
        });
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

    /* ---------- projection helpers (no crosshair pinning) ---------- */

    /**
     * If the billboard center would exceed the far plane, move it along the cam→target ray
     * so its center is just inside the far plane by a conservative margin (half diagonal).
     * Otherwise, leave it at the true world position. Never pushes along camera forward
     * unless that is actually the target direction; this avoids “sticking to crosshair.”
     */
    private static Vec3d projectToFrustumFar(Vec3d camPos, float camYaw, float camPitch,
                                             Vec3d targetWorld, float spriteW, float spriteH,
                                             double farBlocks) {
        Vec3d to = targetWorld.subtract(camPos);
        double len = to.length();
        if (len < 1e-6) return targetWorld;

        Vec3d d = to.multiply(1.0 / len);            // direction cam -> target
        Vec3d fwd = Vec3d.fromPolar(camPitch, camYaw); // camera forward (unit, degrees input)
        double cos = d.dotProduct(fwd);

        // If target is at/behind perpendicular wrt camera forward, don't try to clamp: keep world anchored
        if (cos <= 1e-6) return targetWorld;

        // Distance of center along the camera forward axis
        double forwardDist = len * cos;

        // Margin so the whole quad fits inside the far plane (half of the diagonal + small cushion)
        double halfDiag = 0.5 * Math.hypot(spriteW, spriteH);
        double margin   = Math.max(2.0, halfDiag + 1.0);

        // Already safely within far plane
        if (forwardDist <= farBlocks - margin) return targetWorld;

        // Bring center just inside far plane accounting for the margin
        double t = (farBlocks - margin) / cos;       // distance along d
        t = Math.max(0.0, Math.min(len, t));         // clamp to [0, len]
        return camPos.add(d.multiply(t));
    }

    /** Approximate far plane in blocks from current render distance. */
    private static double computeFarPlaneBlocks() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            if (mc != null && mc.options != null) {
                // view distance (chunks) * 16 blocks/chunk
                return mc.options.getClampedViewDistance() * 16.0;
            }
        } catch (Throwable ignored) {}
        return 256.0;
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
}
