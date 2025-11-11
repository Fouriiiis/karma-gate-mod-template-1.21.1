package dev.fouriis.karmagate.entity.shelterdoor;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
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

public class ShelterDoorBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final String ANIM_OPEN       = "open";
    private static final String ANIM_CLOSE      = "close";
    private static final String ANIM_OPEN_IDLE  = "open_idle";
    private static final String ANIM_CLOSE_IDLE = "close_idle";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean open = false;
    private boolean clientInitialized = false;

    public ShelterDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHELTER_DOOR_BLOCK_ENTITY, pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<ShelterDoorBlockEntity> controller =
                new AnimationController<>(this, "controller", 0, this::predicate);

    controller
        .triggerableAnim(ANIM_OPEN,
            RawAnimation.begin()
                .then(ANIM_OPEN, Animation.LoopType.PLAY_ONCE)
                .then(ANIM_OPEN_IDLE, Animation.LoopType.LOOP))
        .triggerableAnim(ANIM_CLOSE,
            RawAnimation.begin()
                .then(ANIM_CLOSE, Animation.LoopType.PLAY_ONCE)
                .then(ANIM_CLOSE_IDLE, Animation.LoopType.LOOP));

        controllers.add(controller);
    }

    private PlayState predicate(AnimationState<ShelterDoorBlockEntity> state) {
        if (world != null && world.isClient && !clientInitialized) {
            final AnimationController<ShelterDoorBlockEntity> ctrl = state.getController();
            ctrl.forceAnimationReset();
        ctrl.setAnimation(RawAnimation.begin()
            .then(open ? ANIM_OPEN_IDLE : ANIM_CLOSE_IDLE, Animation.LoopType.LOOP));
            clientInitialized = true;
        }
        return PlayState.CONTINUE;
    }

    public void toggle() {
        if (world == null || world.isClient) return;
        this.open = !this.open;
        markDirtySync();
        this.triggerAnim("controller", this.open ? ANIM_OPEN : ANIM_CLOSE);
    }

    public boolean isOpen() {
        return this.open;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    private void markDirtySync() {
        markDirty();
        if (world instanceof ServerWorld sw) sw.getChunkManager().markForUpdate(pos);
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("open", this.open);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        this.open = nbt.getBoolean("open");
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

    public void tick(World world1, BlockPos pos, BlockState state1, ShelterDoorBlockEntity be) {
        // currently no server tick logic required
    }
}
