package dev.fouriis.karmagate.client;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.texture.NativeImage;
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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.client.render.RenderPhase.*;

/**
 * Renders distant billboard sprites AFTER Iris has done its fog/composite pass.
 * Call {@link #renderLate(float, Camera)} from a mixin injected at the end of WorldRenderer.render().
 */
public final class DistantStructuresRenderer {

    private static final Identifier CONFIG_ID = Identifier.of("karma-gate-mod", "structures/distant_structures.json");
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    // Emissive overlay textures for the “lightning” glow
    private static final Identifier LIGHT1 = Identifier.of("karma-gate-mod", "structures/atc_light1.png");
    private static final Identifier LIGHT2 = Identifier.of("karma-gate-mod", "structures/atc_light2.png");
    private static final Identifier LIGHT3 = Identifier.of("karma-gate-mod", "structures/atc_light3.png");

    // Per-entry lightning state (RW-like)
    private static final Map<Entry, Lightning> LIGHTNING = new ConcurrentHashMap<>();

    // Cache source image sizes to preserve overlay/base size ratio
    private static final Map<Identifier, int[]> TEX_SIZE = new ConcurrentHashMap<>();

    private static final float GLOW_Z_PUSH = -0.0025f; // render just behind the base

    /** Billboard entry in world space (units = blocks). */
    public record Entry(
            Identifier texture,
            double x, double y, double z,
            float width, float height,
            boolean emissive,     // ignored for base
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

        // Build VIEW = R^{-1} * T(-camPos)
        Vec3d camPos = camera.getPos();
        Matrix4f view = new Matrix4f()
                .rotation(camera.getRotation())
                .transpose()
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // Apply vanilla bobbing BEFORE the view
        MatrixStack matrices = new MatrixStack();
        if (mc.options.getBobView().getValue()) {
            ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                    .karmaGate$invokeBobView(matrices, tickDelta);
        }
        matrices.peek().getPositionMatrix().mul(view);

        // Dynamic FOV (exact) + extended far plane
        double dynFovDeg = ((dev.fouriis.karmagate.mixin.client.GameRendererAccessor) mc.gameRenderer)
                .karmaGate$invokeGetFov(camera, tickDelta, true);
        float fovRad = (float) Math.toRadians(dynFovDeg);

        float aspect = (float) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());
        float near = 0.0001f;
        float far  = (float) (mc.options.getClampedViewDistance() * 16.0 * 100.0);
        Matrix4f extendedProj = new Matrix4f().setPerspective(fovRad, aspect, near, far);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(extendedProj, VertexSorter.BY_DISTANCE);

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        // Ambient grayscale (day/night brightness) without hue tint
        float ambient01 = 1f;
        int cr = 255, cg = 255, cb = 255;
        if (mc.world != null) {
            Vec3d sky = mc.world.getSkyColor(camPos, tickDelta);
            float r = MathHelper.clamp((float) sky.x, 0f, 1f);
            float g = MathHelper.clamp((float) sky.y, 0f, 1f);
            float b = MathHelper.clamp((float) sky.z, 0f, 1f);
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float minNight = 0.12f;
            float scale    = 0.95f;
            float ambient  = MathHelper.clamp(minNight + scale * luma, minNight, 1.0f);
            ambient01 = ambient;
            int gray = (int) (ambient * 255f);
            cr = gray; cg = gray; cb = gray;
        }

        // Night factor goes 0 (bright day) -> 1 (deep night).
        float nightFactor = smoothstep(0.65f, 0.15f, ambient01);

        // ---- CHANGE: +50% lightning in the day (adds +0.125 when nightFactor = 0; fades to 0 at night)
        float baseMult = 0.25f + 1.25f * nightFactor;
        float dayBoostAdd = 0.125f * (1.0f - nightFactor); // +50% of 0.25 at noon, 0 at night
        float globalGlowMultiplier = MathHelper.clamp(baseMult + dayBoostAdd, 0.25f, 1.5f);

        // Back-to-front sort for translucency
        List<Entry> sorted = new ArrayList<>(ENTRIES);
        sorted.sort((a, b) -> Double.compare(
                camPos.squaredDistanceTo(b.x, b.y, b.z),
                camPos.squaredDistanceTo(a.x, a.y, a.z)
        ));

        // Ticks timeline (precision-safe)
        long nowTicks = 0L;
        if (mc.world != null) {
            long base = mc.world.getTime(); // long ticks
            int frac = MathHelper.floor(tickDelta * 20.0f + 0.5f);
            nowTicks = base + frac;
        }

        for (Entry e : sorted) {
            Vec3d target = new Vec3d(e.x, e.y, e.z);

            // Camera-relative vector for yaw & optional far clamp
            Vec3d rel = target.subtract(camPos);
            Vec3d place = target;
            if (e.alwaysVisible) {
                double dist = rel.length();
                double maxDist = Math.max(1.0, far * 0.98);
                if (dist > maxDist) {
                    rel = rel.normalize().multiply(maxDist);
                    place = camPos.add(rel);
                }
            }

            // Billboard yaw
            float yawRad = (float) Math.atan2(-rel.x, -rel.z);
            if (Float.isNaN(yawRad)) yawRad = 0f;

            matrices.push();
            matrices.translate((float) place.x, (float) place.y, (float) place.z);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));

            // ---- Base, non-emissive pass ----
            matrices.push();
            matrices.scale(e.width, e.height, 1f);
            VertexConsumer vcBase = immediate.getBuffer(baseLayer(e.texture()));
            int packedLight;
            var world = mc.world;
            if (world != null) {
                int bx = MathHelper.floor(place.x);
                int by = MathHelper.floor(place.y);
                int bz = MathHelper.floor(place.z);
                int block = world.getLightLevel(LightType.BLOCK, new BlockPos(bx, by, bz));
                int sky   = world.getLightLevel(LightType.SKY,   new BlockPos(bx, by, bz));
                packedLight = LightmapTextureManager.pack(block, sky);
            } else {
                packedLight = LightmapTextureManager.pack(0, 0);
            }
            renderQuad(vcBase, matrices, 1f, 1f, packedLight, cr, cg, cb, 255);
            matrices.pop(); // base scale

            // ---- Emissive lightning overlay ----
            Identifier lightTex = overlayFor(e.texture());
            if (lightTex != null && mc.world != null) {
                Lightning L = LIGHTNING.computeIfAbsent(e, DistantStructuresRenderer::makeLightning);
                L.updateTo(nowTicks);
                L.globalMultiplier = globalGlowMultiplier;

                float alpha = L.lightIntensity(tickDelta) * 0.50f; // keep clear visibility
                if (alpha > 0.003f) {
                    float[] ratio = overlayScaleRatio(e.texture(), lightTex);
                    float sx = ratio[0];
                    float sy = ratio[1];

                    int ia = MathHelper.clamp((int)(alpha * 255f), 0, 255);
                    int fullbright = LightmapTextureManager.pack(15, 15);

                    matrices.push();
                    float yOffset = -(e.height * (sy - 1f) * 0.5f);
                    matrices.translate(0f, yOffset, +GLOW_Z_PUSH);
                    matrices.scale(e.width * sx, e.height * sy, 1f);

                    VertexConsumer vcGlow = immediate.getBuffer(glowLayer(lightTex));
                    renderQuad(vcGlow, matrices, 1f, 1f, fullbright, 255, 255, 255, ia);
                    matrices.pop();
                }
            }

            matrices.pop(); // base transform (pos + yaw)
        }

        immediate.draw();
        RenderSystem.setProjectionMatrix(savedProj, VertexSorter.BY_DISTANCE);
    }

    /* ----------------------------------------------------------------------
       Texture ratio helpers
       ---------------------------------------------------------------------- */

    private static float[] overlayScaleRatio(Identifier baseTex, Identifier overlayTex) {
        int[] base = getTextureSize(baseTex);     // [w,h]
        int[] over = getTextureSize(overlayTex);  // [w,h]
        if (base[0] <= 0 || base[1] <= 0 || over[0] <= 0 || over[1] <= 0) {
            return new float[]{1f, 1f};
        }
        float sx = (float) over[0] / (float) base[0];
        float sy = (float) over[1] / (float) base[1];
        return new float[]{sx, sy};
    }

    private static int[] getTextureSize(Identifier id) {
        return TEX_SIZE.computeIfAbsent(id, tex -> {
            try {
                ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
                var opt = rm.getResource(tex);
                if (opt.isEmpty()) return new int[]{0, 0};
                Resource res = opt.get();
                try (InputStream in = res.getInputStream()) {
                    NativeImage img = NativeImage.read(in);
                    int[] size = new int[]{img.getWidth(), img.getHeight()};
                    img.close();
                    return size;
                }
            } catch (Throwable ignored) {
                return new int[]{0, 0};
            }
        });
    }

    /* ----------------------------------------------------------------------
       Rain-World-like lightning logic (tick-based)
       ---------------------------------------------------------------------- */

    private static Lightning makeLightning(Entry e) {
        long seed = seedFrom(e);
        return new Lightning(seed);
    }

    private static long seedFrom(Entry e) {
        long h = 1469598103934665603L;
        h ^= e.texture.toString().hashCode(); h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.x);   h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.y);   h *= 1099511628211L;
        h ^= Double.doubleToLongBits(e.z);   h *= 1099511628211L;
        return h;
    }

    private static Identifier overlayFor(Identifier base) {
        String p = base.getPath();
        if (p.endsWith("atc_structure1.png")) return LIGHT1;
        if (p.endsWith("atc_structure2.png")) return LIGHT2;
        if (p.endsWith("atc_structure3.png")) return LIGHT3;
        return null;
    }

    private static final class Lightning {
        final Random rng;

        int wait;
        int tinyThunderWait;
        int tinyThunder;
        int tinyThunderLength;
        int thunder;
        int thunderLength;
        float randomLevel;
        int randomLevelChange;
        float power;

        float lastIntensity;
        float intensity;

        boolean nonPositionBasedIntensity = true;
        float intensityMultiplier = 1f;
        float globalMultiplier    = 1f;

        long lastTickAdvanced = Long.MIN_VALUE;

        Lightning(long seed) {
            this.rng = new Random(seed);
            this.tinyThunderWait = 5;
            resetBurst();
            this.thunder = 0;
            this.tinyThunder = 0;
            this.tinyThunderLength = 0;
            this.randomLevel = rng.nextFloat();
            this.randomLevelChange = 1 + rng.nextInt(5);
            this.lastIntensity = 0f;
            this.intensity = 0f;
            this.lastTickAdvanced = Long.MIN_VALUE;
        }

        private void resetBurst() {
            this.wait = lerpInt(10, 440, rng.nextFloat());
            this.power = lerp(0.7f, 1.0f, rng.nextFloat());
            this.thunderLength = 1 + rng.nextInt(Math.max(1, (int) lerp(10f, 32f, power)));
        }

        void updateTo(long nowTick) {
            if (lastTickAdvanced == Long.MIN_VALUE) {
                lastTickAdvanced = nowTick;
                return;
            }
            if (nowTick <= lastTickAdvanced) return;

            for (long t = lastTickAdvanced + 1; t <= nowTick; t++) stepOneTick();
            lastTickAdvanced = nowTick;
        }

        private void stepOneTick() {
            randomLevelChange--;
            if (randomLevelChange < 1) {
                randomLevelChange = 1 + rng.nextInt(5);
                randomLevel = rng.nextFloat();
            }

            if (wait > 0) {
                wait--;
                if (wait < 1) {
                    thunder = thunderLength;
                }
            } else {
                thunder--;
                if (thunder < 1) {
                    resetBurst();
                }
            }

            if (tinyThunderWait > 0) {
                tinyThunderWait--;
                if (tinyThunderWait < 1) {
                    tinyThunderWait = 10 + rng.nextInt(71);
                    tinyThunderLength = 5 + rng.nextInt(tinyThunderWait - 4);
                    tinyThunder = tinyThunderLength;
                }
            }

            lastIntensity = intensity;

            float a = 0f;
            float b = 0f;

            if (thunder > 0) {
                float thunderFac = 1f - (float) thunder / (float) Math.max(1, thunderLength);
                float expo = lerp(3f, 0.1f, (float) Math.sin(thunderFac * Math.PI));
                a = (float) Math.pow(clamp01(randomLevel), expo);
            }

            if (tinyThunder > 0) {
                tinyThunder--;
                float tinyFac = 1f - (float) tinyThunder / (float) Math.max(1, tinyThunderLength);
                float expo = lerp(3f, 0.1f, (float) Math.sin(tinyFac * Math.PI));
                b = (float) Math.pow(rng.nextFloat(), expo) * 0.7f;
            }

            intensity = Math.max(a, b);
        }

        float lightIntensity(float timeStacker) {
            float num = lerp(lastIntensity, intensity, clamp01(timeStacker));
            if (rng.nextFloat() < (1f / 3f)) {
                float target = (rng.nextFloat() < 0.5f) ? 1f : 0f;
                num = lerp(num, target, rng.nextFloat() * num);
            }
            float shaped = sCurve(num);
            shaped = (float)Math.pow(shaped, 0.7f);
            return shaped * intensityMultiplier * globalMultiplier;
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
        private static int lerpInt(int a, int b, float t) { return Math.round(a + (b - a) * t); }
        private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }
        private static float sCurve(float x) {
            x = clamp01(x);
            return x * x * (3f - 2f * x);
        }
    }

    /* ----------------------------------------------------------------------
       Render helpers
       ---------------------------------------------------------------------- */
    private static void renderQuad(VertexConsumer vc, MatrixStack matrices, float width, float height,
                                   int light, int r, int g, int b, int a) {
        MatrixStack.Entry me = matrices.peek();
        Matrix4f model = me.getPositionMatrix();

        float halfW = width * 0.5f;
        float bottom = 0f;

        vc.vertex(model, -halfW, bottom + height, 0).color(r, g, b, a).texture(0f, 0f).light(light);
        vc.vertex(model,  halfW, bottom + height, 0).color(r, g, b, a).texture(1f, 0f).light(light);
        vc.vertex(model,  halfW, bottom,          0).color(r, g, b, a).texture(1f, 1f).light(light);
        vc.vertex(model, -halfW, bottom,          0).color(r, g, b, a).texture(0f, 1f).light(light);
    }

    private static RenderLayer baseLayer(Identifier texture) {
        return RenderLayer.of(
                "karma_gate_billboard_base",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                        .texture(new Texture(texture, false, true))
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(DISABLE_CULLING)
                        .lightmap(ENABLE_LIGHTMAP)
                        .depthTest(LEQUAL_DEPTH_TEST)
                        .writeMaskState(ALL_MASK)
                        .build(false)
        );
    }

    private static RenderLayer glowLayer(Identifier texture) {
        return RenderLayer.of(
                "karma_gate_billboard_glow",
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                1024,
                false,
                true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(POSITION_COLOR_TEXTURE_LIGHTMAP_PROGRAM)
                        .texture(new Texture(texture, false, true))
                        .transparency(TRANSLUCENT_TRANSPARENCY)
                        .cull(DISABLE_CULLING)
                        .lightmap(ENABLE_LIGHTMAP)   // submit fullbright
                        .depthTest(LEQUAL_DEPTH_TEST)
                        .writeMaskState(COLOR_MASK)
                        .build(false)
        );
    }

    /* ----------------------------------------------------------------------
       Config loading
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
                        boolean always = o.has("alwaysVisible") && o.get("alwaysVisible").getAsBoolean();
                        ENTRIES.add(new Entry(tex, x, y, z, w, h, false, always));
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
                false,
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
            list.add(new Entry(tex, x, y, z, 96f, 128f, false, true));
        }
    }

    /* ----------------------------------------------------------------------
       small math helpers
       ---------------------------------------------------------------------- */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = MathHelper.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
