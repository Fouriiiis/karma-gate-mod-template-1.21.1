package dev.fouriis.karmagate.entity.karmagate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.fouriis.karmagate.KarmaGateMod;
import dev.fouriis.karmagate.block.karmagate.KarmaGateBlock;
import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class KarmaGateBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final String ANIM_OPEN       = "open";
    private static final String ANIM_CLOSE      = "close";
    private static final String ANIM_OPEN_IDLE  = "open_idle";
    private static final String ANIM_CLOSE_IDLE = "close_idle";

    // ==========================================================================

    // Controller state
    boolean isController = false;   // package-private for controller convenience
    private UUID airlockId = null;

    // Controller logic is now here:
    private final KarmaGateController controller = new KarmaGateController(this);

    // ==========================================================================

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Persisted logical state (this single gate's open/closed)
    private boolean open = false;

    // Client-only: track first pose-after-NBT
    private boolean clientInitialized = false;

    public KarmaGateBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.KARMA_GATE_BLOCK_ENTITY, pos, state);
    }

    public KarmaGateBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /* ===================== Public server API ===================== */

    public void toggle() {
        if (world == null || world.isClient) return;
        if (open) close(); else open();
    }

    public void open() {
        if (world == null || world.isClient || open) return;
        open = true;
        markDirtySync();
        this.triggerAnim("controller", "open");  // plays OPEN then OPEN_IDLE
        KarmaGateMod.LOGGER.info("Gate @{} -> OPEN (triggered)", pos);
    }

    public void close() {
        if (world == null || world.isClient || !open) return;
        open = false;
        markDirtySync();
        this.triggerAnim("controller", "close"); // plays CLOSE then CLOSE_IDLE
        KarmaGateMod.LOGGER.info("Gate @{} -> CLOSED (triggered)", pos);
    }

    /** Server-side state flip that also drives animation. */
    public void setOpen(boolean value) {
        if (world == null || world.isClient) return;
        if (this.open == value) return;
        this.open = value;
        markDirtySync();
        this.triggerAnim("controller", value ? "open" : "close");
    }

    /** Expose current open state to controller. */
    public boolean isOpen() {
        return open;
    }

    /* ===================== Controller Binding ===================== */

    /** OP action: make this a controller and bind nearest two non-controller gates. */
    public int configureAsControllerAndBindNearest(int radius) {
        if (world == null || world.isClient) return 0;

        isController = true;
        if (airlockId == null) airlockId = UUID.randomUUID();

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = this.pos;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockEntity be = world.getBlockEntity(p);
                    if (be instanceof KarmaGateBlockEntity g && !g.isController) {
                        candidates.add(p);
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        int bound = Math.min(2, candidates.size());
        BlockPos gate1 = bound >= 1 ? candidates.get(0) : null;
        BlockPos gate2 = bound >= 2 ? candidates.get(1) : null;

        // Open side gates on bind (optional but keeps flow predictable)
        if (gate1 != null) {
            BlockEntity be1 = world.getBlockEntity(gate1);
            if (be1 instanceof KarmaGateBlockEntity g1) g1.setOpen(true);
        }
        if (gate2 != null) {
            BlockEntity be2 = world.getBlockEntity(gate2);
            if (be2 instanceof KarmaGateBlockEntity g2) g2.setOpen(true);
        }

        controller.setGates(gate1, gate2);
        controller.resetOnBind();

        // ALSO bind nearby lights now that we know orientation
        final int lightRadius = Math.max(15, radius); // reuse radius (or expand a bit) for convenience
        controller.bindLightsAndEffects(world, this.pos, this.getCachedState(), lightRadius);

        KarmaGateMod.LOGGER.info("Controller {} @{} bound {} gate(s): gate1={}, gate2={}",
                airlockId, origin, bound, gate1, gate2);
        markDirtySync();
        return bound;
    }

    /* ===================== GeckoLib ===================== */

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        AnimationController<KarmaGateBlockEntity> controller =
            new AnimationController<>(this, "controller", 0, this::predicate);

        controller
            .triggerableAnim("open",
                RawAnimation.begin()
                    .then(ANIM_OPEN, Animation.LoopType.PLAY_ONCE)
                    .then(ANIM_OPEN_IDLE, Animation.LoopType.LOOP))
            .triggerableAnim("close",
                RawAnimation.begin()
                    .then(ANIM_CLOSE, Animation.LoopType.PLAY_ONCE)
                    .then(ANIM_CLOSE_IDLE, Animation.LoopType.LOOP));

        registrar.add(controller);
    }

    private PlayState predicate(AnimationState<KarmaGateBlockEntity> state) {
        if (world != null && world.isClient && !clientInitialized) {
            final AnimationController<KarmaGateBlockEntity> ctrl = state.getController();
            ctrl.forceAnimationReset();
            ctrl.setAnimation(RawAnimation.begin()
                .then(open ? ANIM_OPEN_IDLE : ANIM_CLOSE_IDLE, Animation.LoopType.LOOP));
            clientInitialized = true;
            return PlayState.CONTINUE;
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /* ===================== Tick (server) ===================== */

    /** Called every tick via BlockEntityTicker (from KarmaGateBlock#getTicker). */
    public void tick(World world, BlockPos pos, BlockState state, KarmaGateBlockEntity be) {
        if (world == null || world.isClient) return;

        if (isController) {
            // Delegate all airlock/cycle + light logic to the controller
            controller.tick(world, pos, state);
        }
    }

    /* ===================== Misc helpers ===================== */

    void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    /* ===================== NBT & Packets ===================== */

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("open", open);
        nbt.putBoolean("isController", isController);
        if (airlockId != null) nbt.putUuid("airlockId", airlockId);

        // Persist controller state if we are a controller
        if (isController) controller.writeNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.open = nbt.getBoolean("open");
        this.isController = nbt.getBoolean("isController");
        this.airlockId = nbt.containsUuid("airlockId") ? nbt.getUuid("airlockId") : null;

        if (isController) controller.readNbt(nbt);

        if (world != null && world.isClient) clientInitialized = false;
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    /* ===================== Static utils used by controller ===================== */

    static boolean anyPlayerInSquare(World world, double cx, double cz, double halfSide) {
        if (world == null) return false;
        double minX = cx - halfSide, maxX = cx + halfSide;
        double minZ = cz - halfSide, maxZ = cz + halfSide;
        for (PlayerEntity p : world.getPlayers()) {
            double px = p.getX(), pz = p.getZ();
            if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) return true;
        }
        return false;
    }
}
