// dev/fouriis/karmagate/client/hologram/HologramProjectorRenderer.java
package dev.fouriis.karmagate.hologram;

import dev.fouriis.karmagate.entity.hologram.HologramProjectorBlockEntity;
import dev.fouriis.karmagate.block.hologram.HologramProjectorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.*;

public class HologramProjectorRenderer implements BlockEntityRenderer<HologramProjectorBlockEntity> {

    private static final Identifier SHEET = Identifier.of("karma-gate-mod", "textures/hologram/gate_sheet.png");
    private static final String FRAMES_JSON_PATH = "karma-gate-mod:hologram/gate_sheet_frames.json";

    private static final Identifier NOISE = Identifier.of("karma-gate-mod", "textures/hologram/noise.png");

    private static final float PANEL_W_BLOCKS = 4.0f;
    private static final float PANEL_H_BLOCKS = 8.0f;

    private static final float NOISE_SCROLL_CYCLES_PER_TICK = 0.045f; // unchanged feel

    // Fallback geometry subdivision used ONLY if noise not yet loaded. Once loaded we use noise width/height.
    private static final int FALLBACK_GRID_X = 64;
    private static final int FALLBACK_GRID_Y = 64;

    // Performance guard: maximum number of noise sample pixels (gridX * gridY) before we downscale.
    // This prevents pathological vertex counts when the trimmed glyph spans many blocks.
    private static final int MAX_NOISE_PIXELS = 32_000; // adjustable (empirically safe vs FPS)

    // Additional performance tunables
    private static final int FRAME_SKIP_NEAR = 0;      // frames to skip when close (0 = no skip)
    private static final int FRAME_SKIP_FAR  = 1;      // frames to skip when far (update every other frame)
    private static final double FAR_DIST_SQ  = 32 * 32; // beyond 32 blocks we consider far for skipping
    private static final double LOD1_DIST_SQ = 18 * 18; // distance thresholds for LOD
    private static final double LOD2_DIST_SQ = 26 * 26;
    // LOD factors: how much to downscale sampling resolution (1 = full, 2 = half, 4 = quarter)

    // Simple per-renderer tick counter (client side only)
    private static long clientFrameCounter = 0;

    private static HoloFrameIndex FRAMES;
    private static NativeImage NOISE_IMG;
    private static int NOISE_W, NOISE_H;

    public HologramProjectorRenderer(BlockEntityRendererFactory.Context ctx) {
        if (FRAMES == null) {
            FRAMES = HoloFrameIndex.load(SHEET.toString(), FRAMES_JSON_PATH);
        }
        ensureNoiseLoaded();
    }

    @Override
    public void render(HologramProjectorBlockEntity be, float tickDelta, MatrixStack ms, VertexConsumerProvider buf, int light, int overlay) {
    clientFrameCounter++;

    var frame = FRAMES.get(be.getSymbolKey());
        if (frame == null) return;

        // === Time / effects ===
        float time = (MinecraftClient.getInstance().world.getTime() + tickDelta);
        float flicker = be.getFlicker();
        float glow = be.getGlow();
        float brightness = 0.85f * (0.9f - 0.6f * flicker);
        float wobble = (float) java.lang.Math.sin(time * 0.35) * 0.0025f * (0.5f + 0.5f * flicker);

        // === Transform: center the 4×8 panel at the block and face the block's direction ===
        ms.push();
        ms.translate(0.5, PANEL_H_BLOCKS * 0.5f, 0.5);

        int rotDeg = 0;
        try {
            var state = be.getCachedState();
            if (state != null && state.contains(HologramProjectorBlock.FACING)) {
                switch (state.get(HologramProjectorBlock.FACING)) {
                    case SOUTH -> rotDeg = 0;
                    case NORTH -> rotDeg = 180;
                    case WEST  -> rotDeg = 90;
                    case EAST  -> rotDeg = 270;
                    default -> rotDeg = 0;
                }
            }
        } catch (Exception ignored) {}
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rotDeg));
        ms.translate(0.0, 0.0, 0.001); // nudge off the surface

        // === Fit the untrimmed canvas into the 4×8 panel (preserve aspect), then draw only the trimmed rect ===
        int srcWpx = frame.sourceW;
        int srcHpx = frame.sourceH;

        float scaleX = PANEL_W_BLOCKS / (float) srcWpx;
        float scaleY = PANEL_H_BLOCKS / (float) srcHpx;
        float pxToWorld = java.lang.Math.min(scaleX, scaleY);

        float trimW = frame.w * pxToWorld; // world units
        float trimH = frame.h * pxToWorld; // world units
        float halfTrimW = trimW * 0.5f;
        float halfTrimH = trimH * 0.5f;

        // Offset of trimmed rect relative to untrimmed center (convert px -> world)
        float cx_px = (frame.offX + frame.w * 0.5f) - (srcWpx * 0.5f);
        float cy_px = (srcHpx * 0.5f) - (frame.offY + frame.h * 0.5f); // flip Y from atlas
        float cx = cx_px * pxToWorld;
        float cy = cy_px * pxToWorld;

        // === Color ===
        int rgb = be.getDisplayColor(tickDelta);
        float rf = ((rgb >> 16) & 0xFF) / 255f * glow * brightness;
        float gf = ((rgb >> 8) & 0xFF) / 255f * glow * brightness;
        float bf = (rgb & 0xFF) / 255f * glow * brightness;

        // Static amount handled as geometry cutout probability (no alpha fade under shaderpacks)
        float staticLevel = be.getStaticLevel();
        float baseAlpha = 0.95f; // keep visible; removal happens via geometry cutout
        int argb = ColorHelper.Argb.getArgb((int) (baseAlpha * 255), (int) (rf * 255), (int) (gf * 255), (int) (bf * 255));

        // === Build the 4 corners of the trimmed quad in world space ===
        MatrixStack.Entry e = ms.peek();
        Matrix4f pm = e.getPositionMatrix();
        Matrix3f nm = e.getNormalMatrix();

        Vector4f q1 = new Vector4f(cx - halfTrimW, cy + halfTrimH, 0f, 1f).mul(pm); // TL
        Vector4f q2 = new Vector4f(cx + halfTrimW, cy + halfTrimH, 0f, 1f).mul(pm); // TR
        Vector4f q3 = new Vector4f(cx + halfTrimW, cy - halfTrimH, 0f, 1f).mul(pm); // BR
        Vector4f q4 = new Vector4f(cx - halfTrimW, cy - halfTrimH, 0f, 1f).mul(pm); // BL
        Vector3f n = new Vector3f(0f, 0f, 1f).mul(nm).normalize();

    // === Noise sampling parameters (panel-space) ===
    ensureNoiseLoaded();
    float noiseScroll = time * NOISE_SCROLL_CYCLES_PER_TICK; // fractional texture cycles over time (V direction)

    // We now sample noise at original resolution BUT scaled so ONE full noise texture == ONE world block.
    // Therefore across the trimmed panel (trimW x trimH blocks) we have trimW * NOISE_W pixels horizontally.
    // Geometry subdivision grid dimensions scale with panel size in blocks to keep 1:1 noise pixels.
    int gridX = (NOISE_W > 0) ? java.lang.Math.max(1, (int) java.lang.Math.ceil(NOISE_W * trimW)) : FALLBACK_GRID_X;
    int gridY = (NOISE_H > 0) ? java.lang.Math.max(1, (int) java.lang.Math.ceil(NOISE_H * trimH)) : FALLBACK_GRID_Y;

    // Distance-based LOD + frame skip
    PlayerEntity player = MinecraftClient.getInstance().player;
    double distSq = 0;
    if (player != null) {
        double dx = (be.getPos().getX() + 0.5) - player.getX();
        double dy = (be.getPos().getY() + 0.5) - player.getY();
        double dz = (be.getPos().getZ() + 0.5) - player.getZ();
        distSq = dx*dx + dy*dy + dz*dz;
    }

    int lodFactor = 1;
    if (distSq > LOD2_DIST_SQ) lodFactor = 4; else if (distSq > LOD1_DIST_SQ) lodFactor = 2;
    if (lodFactor > 1) {
        gridX = java.lang.Math.max(1, gridX / lodFactor);
        gridY = java.lang.Math.max(1, gridY / lodFactor);
    }

    int frameSkip = (distSq > FAR_DIST_SQ) ? FRAME_SKIP_FAR : FRAME_SKIP_NEAR;
    if (frameSkip > 0 && (clientFrameCounter % (frameSkip + 1)) != 0) {
        // Still need to pop matrix
        ms.pop();
        return; // reuse previous frame's geometry (left rendered in last buffer submission)
    }

    // Downscale uniformly if above cap (preserve aspect so pixels remain roughly square)
    long total = (long) gridX * (long) gridY;
    if (total > MAX_NOISE_PIXELS) {
        double scale = java.lang.Math.sqrt((double) total / (double) MAX_NOISE_PIXELS); // >1
        gridX = java.lang.Math.max(1, (int) java.lang.Math.round(gridX / scale));
        gridY = java.lang.Math.max(1, (int) java.lang.Math.round(gridY / scale));
    }

        // Cutout threshold mapping (like your shader: ~0.05 -> ~0.95)
        float cutoff = 0.05f + staticLevel * 0.90f;

        // === Draw using vanilla translucent with the atlas bound ===
        VertexConsumer vc = buf.getBuffer(RenderLayer.getEntityTranslucent(SHEET));

        // Optimized subdivision: row-wise run-length encoding. We only emit quads for contiguous "visible" spans.
        // This can reduce vertex count by 10-100x vs per-pixel quads, especially at low static (high fill) where
        // each row becomes a single quad, or high static (few isolated pixels) where geometry is sparse anyway.
        // Optimized row processing using incremental noise sampling
        if (NOISE_IMG == null || NOISE_W == 0 || NOISE_H == 0) {
            // Fallback to single quad if noise missing
            emitRunQuad(0, gridX - 1, 0, gridX, 1, 0f, 1f, wobble, frame, vc, argb, overlay, light, n, q1, q2, q3, q4);
        } else {
            for (int gy = 0; gy < gridY; gy++) {
                float fy0 = (float) gy / gridY;
                float fy1 = (float) (gy + 1) / gridY;
                float panelVCenter = (gy + 0.5f) / gridY;
                float blockV = panelVCenter * trimH; // (0..trimH)
                // Precompute iy once per row
                float vSample = (blockV + noiseScroll) * NOISE_H; // texture space
                int iy = floorMod((int) java.lang.Math.floor(vSample), NOISE_H);

                // Horizontal sampling setup
                float du = trimW / gridX; // delta in blockU per gx increment
                float uStart = (0.5f * trimW) / gridX; // blockU for gx=0 center
                float uTex = uStart * NOISE_W; // convert to texture space *once*
                float duTex = du * NOISE_W;

                int runStart = -1;
                for (int gx = 0; gx < gridX; gx++) {
                    int ix = floorMod((int) uTex, NOISE_W);
                    int argbc = NOISE_IMG.getColor(ix, iy);
                    int a = (argbc >>> 24) & 0xFF;
                    int r = (argbc >>> 16) & 0xFF;
                    int g = (argbc >>> 8) & 0xFF;
                    int b = (argbc) & 0xFF;
                    // fast luminance (avoid division until end)
                    float lum = (r + g + b) * (1f / (3f * 255f)) * (a / 255f);
                    boolean on = lum >= cutoff;
                    if (on) {
                        if (runStart == -1) runStart = gx;
                    } else if (runStart != -1) {
                        emitRunQuad(runStart, gx - 1, gy, gridX, gridY, fy0, fy1, wobble, frame, vc, argb, overlay, light, n, q1, q2, q3, q4);
                        runStart = -1;
                    }
                    uTex += duTex;
                }
                if (runStart != -1) {
                    emitRunQuad(runStart, gridX - 1, gy, gridX, gridY, fy0, fy1, wobble, frame, vc, argb, overlay, light, n, q1, q2, q3, q4);
                }
            }
        }

        ms.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(HologramProjectorBlockEntity be) {
        return true;
    }

    // === Helpers ===

    private static Vector4f lerp(Vector4f a, Vector4f b, float t) {
        return new Vector4f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t,
            1.0f
        );
    }

    private static Vector4f bilerp(Vector4f tl, Vector4f tr, Vector4f bl, Vector4f br, float sx, float sy) {
        Vector4f top = lerp(tl, tr, sx);
        Vector4f bot = lerp(bl, br, sx);
        return lerp(top, bot, sy);
    }

    // Emit a single quad covering a horizontal run of pixels in one row (runStart..runEnd inclusive)
    private static void emitRunQuad(int runStart, int runEnd, int gy,
                                    int gridX, int gridY,
                                    float fy0, float fy1,
                                    float wobble,
                                    HoloFrameIndex.Frame frame,
                                    VertexConsumer vc,
                                    int argb, int overlay, int light,
                                    Vector3f n,
                                    Vector4f q1, Vector4f q2, Vector4f q3, Vector4f q4) {
        // Normalized horizontal span
        float fx0 = (float) runStart / gridX;
        float fx1 = (float) (runEnd + 1) / gridX; // end is inclusive

        // Corners via bilinear interpolation across trimmed quad
        Vector4f vTL = bilerp(q1, q2, q4, q3, fx0, fy0);
        Vector4f vTR = bilerp(q1, q2, q4, q3, fx1, fy0);
        Vector4f vBR = bilerp(q1, q2, q4, q3, fx1, fy1);
        Vector4f vBL = bilerp(q1, q2, q4, q3, fx0, fy1);

        // Atlas UV mapping (preserve proportion of frame)
        float uCol0 = frame.u0 + (frame.u1 - frame.u0) * fx0 + wobble;
        float uCol1 = frame.u0 + (frame.u1 - frame.u0) * fx1 + wobble;
        float vRow0 = frame.v0 + (frame.v1 - frame.v0) * fy0;
        float vRow1 = frame.v0 + (frame.v1 - frame.v0) * fy1;

        vc.vertex(vTL.x, vTL.y, vTL.z, argb, uCol0, vRow0, overlay, light, n.x, n.y, n.z);
        vc.vertex(vTR.x, vTR.y, vTR.z, argb, uCol1, vRow0, overlay, light, n.x, n.y, n.z);
        vc.vertex(vBR.x, vBR.y, vBR.z, argb, uCol1, vRow1, overlay, light, n.x, n.y, n.z);
        vc.vertex(vBL.x, vBL.y, vBL.z, argb, uCol0, vRow1, overlay, light, n.x, n.y, n.z);
    }

    private static void ensureNoiseLoaded() {
        if (NOISE_IMG != null) return;
        try {
            ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
            Resource res = rm.getResourceOrThrow(NOISE);
            try (var in = res.getInputStream()) {
                NOISE_IMG = NativeImage.read(in);
                NOISE_W = NOISE_IMG.getWidth();
                NOISE_H = NOISE_IMG.getHeight();
            }
        } catch (Exception e) {
            NOISE_IMG = null;
            NOISE_W = NOISE_H = 0;
        }
    }

    // (Removed old sampleNoise; fast path inline sampling now used.)

    private static int floorMod(int x, int m) {
        int r = x % m;
        return (r < 0) ? r + m : r;
    }
}
