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
 * Renders an animated vertical water sheet from the block down to the first solid block.
 * Flow changes propagate from top to bottom via client keyframes.
 */
public class WaterfallBlockRenderer implements BlockEntityRenderer<WaterfallBlockEntity> {

    // Bare minimum: vanilla translucent water texture.
    private static final Identifier WATER_TEX = Identifier.of("minecraft", "textures/block/water_still.png");

    // Visual/Perf tunables
    private static final int MAX_BLOCKS_DOWN = 128;

    // Keep geometry light.
    private static final int STRIPS_PER_BLOCK = 12;
    private static final int SEGS_X = 32;
    private static final int MAX_QUADS = 60_000;

    private static final float PLANE_HALF_WIDTH = 0.5f;
    private static final float DEPTH_NUDGE = 0.0015f;

    // Simple pattern motion
    private static final float SCROLL_BLOCKS_PER_TICK = 0.10f;

    // Simple cutout pattern (smooth, no pixel noise)
    private static final float CUT_FREQ_X = 24.0f;
    private static final float CUT_FREQ_Y = 1.35f;
    private static final float CUT_SPEED = 0.90f;
    private static final float CUT_THRESHOLD0 = 0.78f;
    private static final float CUT_THRESHOLD1 = 0.88f;
    private static final float CUT_FEATHER = 0.10f;

    // Texture tiling: how many times the water texture repeats per block of height.
    private static final float V_TILES_PER_BLOCK = 1.0f;

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
        float t = (float) clientTime;
        float seed = stableSeed01(anchorTop);

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);

        Vector3f normal = normalForFacing(facing);
        matrices.translate(normal.x() * DEPTH_NUDGE, 0.0, normal.z() * DEPTH_NUDGE);

        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector3f right = rightForFacing(facing);

        float topY = 1.0f;
        float bottomY = 1.0f - blocksDown;

        int stripsPerBlock = STRIPS_PER_BLOCK;
        int segsX = SEGS_X;

        int totalStrips = blocksDown * stripsPerBlock;
        long estimatedQuads = (long) totalStrips * (long) segsX;
        if (estimatedQuads > MAX_QUADS) {
            segsX = Math.max(6, (int) (MAX_QUADS / Math.max(1L, (long) totalStrips)));
        }

        float stripH = (topY - bottomY) / (float) totalStrips;

        for (int strip = 0; strip < totalStrips; strip++) {
            float y0 = topY - (strip + 1) * stripH;
            float y1 = topY - strip * stripH;

            double distanceBlocks = (strip + 0.5) / (double) stripsPerBlock;
            float effectiveFlow = be.getEffectiveFlow(clientTime, distanceBlocks);
            if (effectiveFlow <= 0.001f) continue;

            float vBlocks0 = anchorOffsetYBlocks + distanceDownFromLocalY(y0);
            float vBlocks1 = anchorOffsetYBlocks + distanceDownFromLocalY(y1);
            float vTile0 = vTileForBlocks(vBlocks0);
            float vTile1 = vTileForBlocks(vBlocks1);

            for (int sx = 0; sx < segsX; sx++) {
                float uA = (float) sx / (float) segsX;
                float uB = (float) (sx + 1) / (float) segsX;

                float lxA = lerp(-PLANE_HALF_WIDTH, PLANE_HALF_WIDTH, uA);
                float lxB = lerp(-PLANE_HALF_WIDTH, PLANE_HALF_WIDTH, uB);

                float rxA = lxA * right.x();
                float rzA = lxA * right.z();
                float rxB = lxB * right.x();
                float rzB = lxB * right.z();

                float uMid = (uA + uB) * 0.5f;
                float vMid = (vBlocks0 + vBlocks1) * 0.5f;

                float flow01 = MathHelper.clamp(effectiveFlow, 0.0f, 1.0f);

                // Smooth moving cutouts (no pixel noise):
                // Create vertical-ish streaks from X with a gentle Y-warp that scrolls down.
                float y = vMid * CUT_FREQ_Y - t * CUT_SPEED;
                float x = uMid * CUT_FREQ_X + seed * 19.0f;
                float warp = 0.70f * (float) Math.sin(y);
                float raw = 0.5f + 0.5f * (float) Math.sin(x + warp);
                // Convert to a sparse "hole" mask, feathered at edges.
                float holeCore = smoothstep(CUT_THRESHOLD0, CUT_THRESHOLD1, raw);
                float feather = Math.max(CUT_FEATHER, 1e-4f);
                float hole = smoothstep(0.0f, feather, holeCore);

                // Base visibility + gentle downward shading so it doesn't look flat.
                float scrollShade = 0.85f + 0.15f * (float) Math.sin(vMid * 3.0f - t * SCROLL_BLOCKS_PER_TICK * 6.0f + seed * 7.0f);

                float baseAlpha = (0.30f + 0.70f * flow01) * scrollShade;
                float alpha = baseAlpha * (1.0f - hole);

                int a = MathHelper.clamp((int) (alpha * 255f), 0, 255);
                if (a <= 1) continue;

                vc.vertex(m, rxA, y0, rzA).color(255, 255, 255, a).texture(uA, vTile0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxB, y0, rzB).color(255, 255, 255, a).texture(uB, vTile0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxB, y1, rzB).color(255, 255, 255, a).texture(uB, vTile1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
                vc.vertex(m, rxA, y1, rzA).color(255, 255, 255, a).texture(uA, vTile1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal.x(), 0, normal.z());
            }
        }

        matrices.pop();
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
        float frac = (float) (distanceDown - java.lang.Math.floor(distanceDown));
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
