package dev.fouriis.karmagate.entity.client;

import dev.fouriis.karmagate.block.karmagate.WaterfallBlock;
import dev.fouriis.karmagate.entity.karmagate.WaterfallBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Optimized waterfall renderer:
 *  - Uses MathHelper.sin (lookup-table) instead of Math.sin
 *  - Precomputes per-column (u) terms once per render call
 *  - Reduces hole decision to a single smoothstep + threshold
 *  - Adds simple LOD on stripsPerBlock for very tall waterfalls
 */
public class WaterfallBlockRenderer implements BlockEntityRenderer<WaterfallBlockEntity> {

    private static final Identifier WATER_TEX = Identifier.of("minecraft", "textures/block/water_flow.png");

    private static final int MAX_BLOCKS_DOWN = 128;

    // Base geometry knobs (LOD will reduce strips on tall falls)
    private static final int STRIPS_PER_BLOCK = 12;
    private static final int SEGS_X = 32;
    private static final int MAX_QUADS = 60_000;

    private static final float PLANE_HALF_WIDTH = 0.5f;
    private static final float DEPTH_NUDGE = 0.0015f;

    // ---------------- Pattern controls (no texture noise) ----------------
    private static final float V_STRETCH = 2.0f / 3.0f;

    // NOTE: you currently have extra speed baked in here; keep as-is.
    private static final float SPEED_MULT = (4.0f / 3.0f) * 2.0f;

    private static final float BAND_FREQ_X = 10.0f;
    private static final float BAND_FREQ_Y = 0.65f * V_STRETCH;
    private static final float BAND_SPEED  = 0.85f * SPEED_MULT;

    private static final float BAND2_FREQ_X = 18.0f;
    private static final float BAND2_FREQ_Y = 1.10f * V_STRETCH;
    private static final float BAND2_SPEED  = 1.10f * SPEED_MULT;

    private static final float FINE_FREQ_X = 36.0f;
    private static final float FINE_FREQ_Y = 2.40f * V_STRETCH;
    private static final float FINE_SPEED  = 1.60f * SPEED_MULT;

    private static final float WARP_Y_FREQ = 1.35f * V_STRETCH;
    private static final float WARP_X_FREQ = 2.20f;
    private static final float WARP_SPEED  = 0.55f * SPEED_MULT;
    private static final float WARP_U_STRENGTH = 1.35f;
    private static final float WARP_V_STRENGTH = 0.55f;

    private static final float RIP_FREQ_X = 6.0f;
    private static final float RIP_FREQ_Y = 0.55f * V_STRETCH;
    private static final float RIP_SPEED  = 0.70f * SPEED_MULT;
    private static final float RIP_INTENSITY = 0.55f;

    private static final float HOLE_THRESH0 = 0.62f;
    private static final float HOLE_THRESH1 = 0.78f;

    // Optional "layered" feel without harsh quantization
    private static final int BAND_STEPS = 7;
    private static final float BAND_STEP_BLEND = 0.65f;

    private static final float V_TILES_PER_BLOCK = 1.0f;

    // Constants to avoid repeated literals
    private static final float PI2 = 6.283185307179586f;

    public WaterfallBlockRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(WaterfallBlockEntity be, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        World world = be.getWorld();
        if (world == null) return;

        BlockPos pos = be.getPos();
        BlockPos anchorTop = findTopWaterfall(world, pos);
        int anchorOffsetYBlocks = anchorTop.getY() - pos.getY();

        BlockState state = be.getCachedState();
        Direction facing = Direction.NORTH;
        if (state != null && state.contains(WaterfallBlock.FACING)) {
            facing = state.get(WaterfallBlock.FACING);
        }

        int blocksDown = findBlocksDownToFirstSolid(world, pos);
        if (blocksDown <= 0) return;

        renderSimpleSheet(be, tickDelta, matrices, vertexConsumers, light, facing, blocksDown, anchorOffsetYBlocks, anchorTop);
    }

    private static void renderSimpleSheet(WaterfallBlockEntity be,
                                          float tickDelta,
                                          MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers,
                                          int light,
                                          Direction facing,
                                          int blocksDown,
                                          int anchorOffsetYBlocks,
                                          BlockPos anchorTop) {
        World world = be.getWorld();
        if (world == null) return;

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WATER_TEX));

        double clientTime = world.getTime() + (double) tickDelta;
        float tTicks = (float) clientTime;
        float seed = stableSeed01(anchorTop);

        // ---- LOD: reduce vertical strips on tall waterfalls ----
        int stripsPerBlock = STRIPS_PER_BLOCK;
        if (blocksDown > 80) stripsPerBlock = 6;
        else if (blocksDown > 48) stripsPerBlock = 8;

        int segsX = SEGS_X;

        int totalStrips = blocksDown * stripsPerBlock;
        long estimatedQuads = (long) totalStrips * (long) segsX;
        if (estimatedQuads > MAX_QUADS) {
            segsX = Math.max(6, (int) (MAX_QUADS / Math.max(1L, (long) totalStrips)));
        }

        // Precompute per-column (uMid) terms once per render call
        float[] uMidArr   = new float[segsX];
        float[] uArr      = new float[segsX];
        float[] uHalfArr  = new float[segsX];
        float[] wxArr     = new float[segsX];
        float[] baseU1Arr = new float[segsX]; // base for broad
        float[] baseU2Arr = new float[segsX]; // base for mid
        float[] baseUFAr  = new float[segsX]; // base for fine
        float[] ripXArr   = new float[segsX]; // base for rip

        // Seed offsets (avoid recomputing per pixel)
        final float seed19 = seed * 19.0f;
        final float seed41 = seed * 41.0f;
        final float seed73 = seed * 73.0f;
        final float seed11 = seed * 11.0f;
        final float seed31 = seed * 3.1f;

        for (int sx = 0; sx < segsX; sx++) {
            float uA = (float) sx / (float) segsX;
            float uB = (float) (sx + 1) / (float) segsX;
            float uMid = (uA + uB) * 0.5f;

            uMidArr[sx] = uMid;

            float u = (uMid - 0.5f) * 2.0f;    // -1..1
            float uHalf = (u * 0.5f + 0.5f);   // 0..1

            uArr[sx] = u;
            uHalfArr[sx] = uHalf;

            wxArr[sx] = u * WARP_X_FREQ + seed31;

            baseU1Arr[sx] = uHalf * BAND_FREQ_X + seed19;
            baseU2Arr[sx] = uHalf * BAND2_FREQ_X + seed41;
            baseUFAr[sx]  = uHalf * FINE_FREQ_X + seed73;
            ripXArr[sx]   = uHalf * RIP_FREQ_X + seed11;
        }

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);

        Vector3f normal = normalForFacing(facing);
        matrices.translate(normal.x() * DEPTH_NUDGE, 0.0, normal.z() * DEPTH_NUDGE);

        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector3f right = rightForFacing(facing);

        float topY = 1.0f;
        float bottomY = 1.0f - blocksDown;

        float stripH = (topY - bottomY) / (float) totalStrips;

        // Time scale: you bumped this to 0.30f already; keep it centralized.
        final float tt = tTicks * 0.30f;

        // Precompute some constants for quantize
        final float invSteps = 1.0f / (float) BAND_STEPS;

        for (int strip = 0; strip < totalStrips; strip++) {
            float y0 = topY - (strip + 1) * stripH;
            float y1 = topY - strip * stripH;

            double distanceBlocks = (strip + 0.5) / (double) stripsPerBlock;
            float effectiveFlow = be.getEffectiveFlow(clientTime, distanceBlocks);
            if (effectiveFlow <= 0.001f) continue;

            float flow01 = MathHelper.clamp(effectiveFlow, 0.0f, 1.0f);
            float baseAlpha = (0.30f + 0.70f * flow01);
            int baseA = MathHelper.clamp((int) (baseAlpha * 255f), 0, 255);
            if (baseA <= 1) continue;

            float vBlocks0 = anchorOffsetYBlocks + distanceDownFromLocalY(y0);
            float vBlocks1 = anchorOffsetYBlocks + distanceDownFromLocalY(y1);
            float vTile0 = vTileForBlocks(vBlocks0);
            float vTile1 = vTileForBlocks(vBlocks1);

            // One pattern evaluation per quad uses vMid
            float vMidBlocks = (vBlocks0 + vBlocks1) * 0.5f;

            // Precompute v-scaled and wy once per strip (big win)
            float vScaled = vMidBlocks * V_STRETCH;
            float wy = vScaled * WARP_Y_FREQ - tt * WARP_SPEED;
            float wy07 = 0.7f * wy;

            // Compute v-phase bases once per strip
            float vPhaseBase  = vScaled * BAND_FREQ_Y  - tt * BAND_SPEED;
            float vPhase2Base = vScaled * BAND2_FREQ_Y - tt * BAND2_SPEED;
            float vFineBase   = vScaled * FINE_FREQ_Y  - tt * FINE_SPEED;
            float ripYBase    = vScaled * RIP_FREQ_Y   - tt * RIP_SPEED;

            for (int sx = 0; sx < segsX; sx++) {
                float uA = (float) sx / (float) segsX;
                float uB = (float) (sx + 1) / (float) segsX;

                float lxA = lerp(-PLANE_HALF_WIDTH, PLANE_HALF_WIDTH, uA);
                float lxB = lerp(-PLANE_HALF_WIDTH, PLANE_HALF_WIDTH, uB);

                float rxA = lxA * right.x();
                float rzA = lxA * right.z();
                float rxB = lxB * right.x();
                float rzB = lxB * right.z();

                // ---- Optimized complexHoleField (no allocations, fewer trig, MathHelper.sin) ----
                float wx = wxArr[sx];

                // warp1 = sin(wy + 1.2*sin(wx))
                float warp1 = MathHelper.sin(wy + 1.2f * MathHelper.sin(wx));

                // warp2 = sin(0.7*wy + 1.7*sin(1.3*wx + 2.0))
                float warp2 = MathHelper.sin(wy07 + 1.7f * MathHelper.sin(1.3f * wx + 2.0f));

                float warp = 0.55f * warp1 + 0.45f * warp2;

                // broad
                float vPhase = vPhaseBase + warp * WARP_V_STRENGTH;
                float broad = 0.5f + 0.5f * MathHelper.sin(baseU1Arr[sx] + warp * WARP_U_STRENGTH + 0.80f * MathHelper.sin(vPhase));

                // mid
                float vPhase2 = vPhase2Base + 0.4f * warp2;
                float mid = 0.5f + 0.5f * MathHelper.sin(baseU2Arr[sx] + 0.8f * warp + 0.65f * MathHelper.sin(vPhase2));

                // fine (lightweight)
                float vFine = vFineBase + 0.7f * warp1;
                float fine = 0.5f + 0.5f * MathHelper.sin(baseUFAr[sx] + 1.6f * warp + 0.25f * MathHelper.sin(vFine));

                // Optional soft stepping (keep, but cheaper)
                broad = quantizeSoftFast(broad, invSteps, BAND_STEP_BLEND);
                mid   = quantizeSoftFast(mid,   invSteps, BAND_STEP_BLEND);
                fine  = quantizeSoftFast(fine,  invSteps, Math.min(0.80f, BAND_STEP_BLEND + 0.10f));

                // rips
                float rip = 0.5f + 0.5f * MathHelper.sin(ripXArr[sx] + 1.2f * MathHelper.sin(ripYBase + 0.5f * warp2));
                float ripShaped = smoothstep(0.72f, 0.92f, rip);

                // hole tendency
                float hole = 0.55f * (1.0f - broad) + 0.30f * (1.0f - mid) + 0.15f * (1.0f - fine);
                hole = MathHelper.clamp(hole + RIP_INTENSITY * ripShaped, 0.0f, 1.0f);

                // Single smoothstep + threshold (replaces 2 smoothsteps)
                float holeSoft = smoothstep(HOLE_THRESH0, HOLE_THRESH1, hole);
                boolean isHole = holeSoft > 0.5f;

                int a = isHole ? 0 : baseA;
                if (a <= 1) continue;

                vc.vertex(m, rxA, y0, rzA).color(255, 255, 255, a).texture(uA, vTile0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxB, y0, rzB).color(255, 255, 255, a).texture(uB, vTile0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxB, y1, rzB).color(255, 255, 255, a).texture(uB, vTile1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxA, y1, rzA).color(255, 255, 255, a).texture(uA, vTile1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
            }
        }

        matrices.pop();
    }

    // Faster quantize/soft blend: avoids floor() on huge values; still uses floor but fewer ops.
    private static float quantizeSoftFast(float x, float invSteps, float blendToOriginal) {
        x = MathHelper.clamp(x, 0.0f, 1.0f);
        // q = floor(x * steps) / steps  <=> floor(x/ invSteps) * invSteps
        float scaled = x / invSteps;
        float q = (float) Math.floor(scaled) * invSteps;
        return MathHelper.lerp(q, x, MathHelper.clamp(blendToOriginal, 0.0f, 1.0f));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = MathHelper.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float vTileForBlocks(float vBlocks) {
        float distanceDown = Math.max(vBlocks, 0.0f) * V_TILES_PER_BLOCK;
        float frac = (float) (distanceDown - Math.floor(distanceDown));
        if (frac == 0.0f && distanceDown > 0.0f) {
            frac = 1.0f;
        }
        return frac;
    }

    @Override
    public boolean rendersOutsideBoundingBox(WaterfallBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 256;
    }

    private static float stableSeed01(BlockPos pos) {
        long h = pos.asLong() * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        long mantissa = (h >>> 11) & ((1L << 53) - 1);
        return (float) (mantissa / (double) (1L << 53));
    }

    private static float distanceDownFromLocalY(float localY) {
        return (1.0f - localY);
    }

    private static int findBlocksDownToFirstSolid(World world, BlockPos origin) {
        int bottomY = world.getBottomY();
        int y = origin.getY() - 1;
        int blocks = 0;
        while (y >= bottomY && blocks < MAX_BLOCKS_DOWN) {
            BlockPos p = new BlockPos(origin.getX(), y, origin.getZ());
            VoxelShape shape = world.getBlockState(p).getCollisionShape(world, p);
            if (!shape.isEmpty()) {
                return blocks;
            }
            blocks++;
            y--;
        }
        return blocks;
    }

    private static BlockPos findTopWaterfall(World world, BlockPos start) {
        BlockPos.Mutable p = start.mutableCopy();
        int topY = world.getTopY();
        int steps = 0;
        while (p.getY() + 1 < topY && steps < 64) {
            p.set(p.getX(), p.getY() + 1, p.getZ());
            BlockState st = world.getBlockState(p);
            if (!(st.getBlock() instanceof WaterfallBlock)) {
                p.set(p.getX(), p.getY() - 1, p.getZ());
                break;
            }
            steps++;
        }
        return p.toImmutable();
    }

    private static Vector3f normalForFacing(Direction facing) {
        return switch (facing) {
            case NORTH -> new Vector3f(0, 0, -1);
            case SOUTH -> new Vector3f(0, 0, 1);
            case EAST -> new Vector3f(1, 0, 0);
            case WEST -> new Vector3f(-1, 0, 0);
            default -> new Vector3f(0, 0, 1);
        };
    }

    private static Vector3f rightForFacing(Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> new Vector3f(1, 0, 0);
            case EAST, WEST -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };
    }
}
