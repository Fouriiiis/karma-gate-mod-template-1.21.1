package dev.fouriis.karmagate.entity.karmagate;

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

import java.util.ArrayDeque;

public class WaterfallBlockEntity extends BlockEntity {
    /** Server-authoritative flow in [0,1]. */
    private float flow = 1.0f;

    /** Client-side: keyframes of received flow values for propagation down the sheet. */
    private final ArrayDeque<FlowKeyframe> clientKeyframes = new ArrayDeque<>();

    public WaterfallBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.WATERFALL_BLOCK_ENTITY, pos, state);
    }

    protected WaterfallBlockEntity(net.minecraft.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /* ---------------- Ticking ---------------- */

    /** Client tick used to cap keyframes + ensure we have an initial value. */
    public static <T extends BlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T blockEntity) {
        if (!(blockEntity instanceof WaterfallBlockEntity be)) return;
        if (!world.isClient) return;
        be.clientMaintainKeyframes();
    }

    private void clientMaintainKeyframes() {
        if (world == null || !world.isClient) return;
        long now = world.getTime();

        // Ensure we always have at least one keyframe.
        if (clientKeyframes.isEmpty()) {
            clientKeyframes.addLast(new FlowKeyframe(now, flow));
            return;
        }

        // Drop ancient keyframes to keep memory bounded.
        // We only need enough history to cover the maximum rendered height.
        final long MAX_AGE_TICKS = 20L * 20L; // 20s
        while (clientKeyframes.size() > 1 && (now - clientKeyframes.peekFirst().time) > MAX_AGE_TICKS) {
            clientKeyframes.removeFirst();
        }
    }

    /* ---------------- Public API ---------------- */

    public float getFlow() {
        return flow;
    }

    public void setFlow(float next) {
        flow = clamp01(next);
        markDirty();

        if (world != null) {
            if (world instanceof ServerWorld sw && !world.isClient) {
                sw.getChunkManager().markForUpdate(pos);
            } else if (world.isClient) {
                // On client, treat local changes as keyframes for propagation
                long now = world.getTime();
                FlowKeyframe last = clientKeyframes.peekLast();
                if (last == null || Math.abs(last.flow - flow) > 1e-4f) {
                    clientKeyframes.addLast(new FlowKeyframe(now, flow));
                }
                clientMaintainKeyframes();
            }
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    /**
     * Client: returns the effective flow for a point 'distanceBlocks' down the waterfall,
     * by sampling the keyframe history with a time delay.
     */
    public float getEffectiveFlow(double clientTimeTicks, double distanceBlocks) {
        // How many ticks it takes for a change to travel 1 block downward.
        final double TICKS_PER_BLOCK = 2.5; // tweak for "realistic" propagation
        double sampleTime = clientTimeTicks - (distanceBlocks * TICKS_PER_BLOCK);
        return sampleFlowAt(sampleTime);
    }

    private float sampleFlowAt(double sampleTimeTicks) {
        if (world == null || !world.isClient) return flow;
        if (clientKeyframes.isEmpty()) return flow;

        FlowKeyframe prev = null;
        FlowKeyframe next = null;
        for (FlowKeyframe k : clientKeyframes) {
            if (k.time <= sampleTimeTicks) {
                prev = k;
                continue;
            }
            next = k;
            break;
        }

        if (prev == null) {
            // Before the first keyframe: treat as earliest known value.
            return clientKeyframes.peekFirst().flow;
        }
        if (next == null) {
            // After the last keyframe: use last known value.
            return clientKeyframes.peekLast().flow;
        }

        double dt = (double) (next.time - prev.time);
        if (dt <= 0.0001) return next.flow;
        double t = (sampleTimeTicks - prev.time) / dt;
        t = clamp01((float) t);
        return lerp(prev.flow, next.flow, (float) t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /* ---------------- NBT / Sync ---------------- */

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("flow", flow);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("flow")) {
            float newFlow = clamp01(nbt.getFloat("flow"));

            // If we're on the client, treat incoming changes as keyframes for propagation.
            if (world != null && world.isClient) {
                long now = world.getTime();
                FlowKeyframe last = clientKeyframes.peekLast();
                if (last == null || Math.abs(last.flow - newFlow) > 1e-4f) {
                    clientKeyframes.addLast(new FlowKeyframe(now, newFlow));
                }
                // Also cap and ensure we always have history.
                clientMaintainKeyframes();
            }

            flow = newFlow;
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putFloat("flow", flow);
        return nbt;
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    private record FlowKeyframe(long time, float flow) {}
}
