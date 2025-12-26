package dev.fouriis.karmagate.block.karmagate;

import com.mojang.serialization.MapCodec;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import dev.fouriis.karmagate.entity.karmagate.KarmaGateBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Gate part block. Stores base gate position in BE.
 * Dimensions are shared with the base block via constants below.
 */
public class KarmaGatePartBlock extends BlockWithEntity {
    public static final MapCodec<KarmaGatePartBlock> CODEC = createCodec(KarmaGatePartBlock::new);
    @Override public MapCodec<KarmaGatePartBlock> getCodec() { return CODEC; }

    // ===== Configurable dimensions (keep in sync with KarmaGateBlock) =====
    public static final int GATE_WIDTH  = 5; // span along perpendicular axis to the gate axis
    public static final int GATE_HEIGHT = 9; // vertical
    public static final int GATE_DEPTH  = 2; // along the gate axis (0 is the base slice)

    // State: gate axis + indices
    public static final EnumProperty<Direction.Axis> AXIS  = Properties.HORIZONTAL_AXIS;
    public static final IntProperty HEIGHT = IntProperty.of("height", 0, GATE_HEIGHT - 1);
    public static final IntProperty AOFF   = IntProperty.of("aoff",   0, GATE_WIDTH  - 1); // width index
    public static final IntProperty DOFF   = IntProperty.of("doff",   0, GATE_DEPTH  - 1); // depth index (0..DEPTH-1, 0 = base slice)

    public KarmaGatePartBlock(Settings settings) {
        super(settings.nonOpaque());
        setDefaultState(getStateManager().getDefaultState()
                .with(AXIS, Direction.Axis.Z)
                .with(HEIGHT, 0)
                .with(AOFF, (GATE_WIDTH - 1) / 2) // center
                .with(DOFF, 0));                  // base slice by default
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> b) {
        b.add(AXIS, HEIGHT, AOFF, DOFF);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PartBE(pos, state);
    }

    

    /** Prefer BE-stored base position; if absent, compute from the indices. */
    private static BlockPos resolveBasePos(World w, BlockPos partPos, BlockState partState) {
        BlockEntity be = w.getBlockEntity(partPos);
        if (be instanceof PartBE pbe && pbe.getBasePos() != null) {
            return pbe.getBasePos();
        }

        // Fallback: compute relative to properties.
        Direction.Axis gateAxis = partState.get(AXIS);
        int h   = partState.get(HEIGHT);
        int a   = partState.get(AOFF) - (GATE_WIDTH - 1) / 2; // width offset centered around 0
        int d   = partState.get(DOFF);                        // depth forward from base (0..DEPTH-1)

        Direction alongAxisDir = (gateAxis == Direction.Axis.X) ? Direction.EAST  : Direction.SOUTH; // depth
        Direction perpAxisDir  = (gateAxis == Direction.Axis.X) ? Direction.SOUTH : Direction.EAST;  // width

        // placement uses base.add(-d * along, +a * perp).up(h)
        // -> inverse here: base = part.down(h).offset(perp, -a).offset(along, +d)
        return partPos.down(h).offset(perpAxisDir, -a).offset(alongAxisDir, +d);
    }

    private static boolean isOpen(World w, BlockPos partPos, BlockState partState) {
        BlockPos basePos = resolveBasePos(w, partPos, partState);
        BlockEntity be = w.getBlockEntity(basePos);
        return (be instanceof KarmaGateBlockEntity k) && k.isOpen();
    }

    /* ---------- Shapes: full cube when closed; empty when open ---------- */

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        if (ctx instanceof EntityShapeContext esc && esc.getEntity() instanceof PlayerEntity player && player.isCreative()) {
            // Creative players always see a hitbox
            return VoxelShapes.fullCube();
        }

        if (view instanceof World w && isOpen(w, pos, state)) {
            return VoxelShapes.empty();
        }
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView view, BlockPos pos, ShapeContext ctx) {
        if (view instanceof World w && isOpen(w, pos, state)) {
            return VoxelShapes.empty();
        }
        return VoxelShapes.fullCube();
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                                PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        // Resolve base position from BE or fallback calculation
        BlockPos basePos = resolveBasePos(world, pos, state);
        BlockState baseState = world.getBlockState(basePos);

        if (baseState.getBlock() instanceof KarmaGateBlock) {
            // Forward the interaction to the gate block
            return baseState.onUse(world, player, new BlockHitResult(
                    hit.getPos(),
                    hit.getSide(),
                    basePos,
                    hit.isInsideBlock()
            ));
        }

        return ActionResult.PASS;
    }




    /* ---------- Break propagation ---------- */

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        super.onBreak(world, pos, state, player);
        if (!world.isClient) {
            BlockPos base = resolveBasePos(world, pos, state);
            if (!base.equals(pos) && !world.isAir(base)) world.breakBlock(base, false);
        }
        return state;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (!world.isClient && state.getBlock() != newState.getBlock()) {
            BlockPos base = resolveBasePos(world, pos, state);
            if (!base.equals(pos) && !world.isAir(base)) world.breakBlock(base, false);
        }
    }

    /* ---------- BlockEntity that stores the base gate position ---------- */

    public static class PartBE extends BlockEntity {
        private BlockPos basePos;

        public PartBE(BlockPos pos, BlockState state) {
            super(ModBlockEntities.KARMA_GATE_PART_BE, pos, state);
        }

        public BlockPos getBasePos() { return basePos; }
        public void setBasePos(BlockPos p) { basePos = p; markDirty(); }

        // 1.21.1 signatures
        @Override
        protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            super.writeNbt(nbt, lookup);
            if (basePos != null) {
                nbt.putInt("baseX", basePos.getX());
                nbt.putInt("baseY", basePos.getY());
                nbt.putInt("baseZ", basePos.getZ());
            }
        }

        @Override
        public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            super.readNbt(nbt, lookup);
            basePos = nbt.contains("baseX")
                    ? new BlockPos(nbt.getInt("baseX"), nbt.getInt("baseY"), nbt.getInt("baseZ"))
                    : null;
        }
    }
}
