package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.particle.ModParticles;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;

public class WaterStreamBlockEntity extends BlockEntity {
    /** Flow strength in [0.0, 1.0]. 0 = no flow, 1 = max flow. */
    private float flow = 0.0f;
    private float targetFlow = 0.0f; // for smooth transitions
    // client-side accumulator to convert fractional flow to particle pairs per tick smoothly
    private float emitAcc = 0f;

    public WaterStreamBlockEntity(BlockPos pos, BlockState state) {
        super(dev.fouriis.karmagate.entity.ModBlockEntities.WATER_STREAM_BLOCK_ENTITY, pos, state);
    }

    /** Client tick: smooth toward target and emit particles based on current flow. */
    public static void tick(World world, BlockPos pos, BlockState state, WaterStreamBlockEntity be) {
    if (!world.isClient) return;

        // Smooth current flow toward target each tick
        final float speed = 0.05f; // units per tick (slightly faster easing)
        if (Math.abs(be.flow - be.targetFlow) <= speed) {
            be.flow = be.targetFlow;
        } else {
            be.flow += Math.signum(be.targetFlow - be.flow) * speed;
        }
        be.flow = clamp01(be.flow);
        if (be.flow <= 0f) return; // emit nothing at 0.0

        final long t = world.getTime();
        final double baseX = pos.getX() + 0.5;
        final double baseY = pos.getY() + 0.95;
        final double baseZ = pos.getZ() + 0.5;

        // Scale particle emission with a fractional accumulator so low flows produce intermittent bursts
        final float MAX_PAIRS = 4.0f; // 8 particles total at full flow (pairs)
        be.emitAcc += MAX_PAIRS * be.flow;
        int count = (int)Math.floor(be.emitAcc);
        be.emitAcc -= count;
        if (count <= 0) return;

        for (int i = 0; i < count; i++) {
            long seed = (t * 341873128712L) ^ (pos.asLong() * 132897987541L) ^ (i * 0x9E3779B97F4A7C15L);
            // Expand horizontal jitter to ±1.5 blocks from center (range ~[-1.5, +1.5])
            final double JITTER_RADIUS = 1.5;
            double jx = (unitNoise(seed ^ 0xA2C2A) - 0.5) * (JITTER_RADIUS * 2.0);
            double jz = (unitNoise(seed ^ 0xB5D5B) - 0.5) * (JITTER_RADIUS * 2.0);

            double y = baseY - (i * 0.18) - ((t % 5) * 0.01);

            // Base velocities
            double vy = -0.22 - unitNoise(seed ^ 0xC7E7C) * 0.04;
            double vx = (unitNoise(seed ^ 0xD8F8D) - 0.5) * 0.01;
            double vz = (unitNoise(seed ^ 0xE9A9E) - 0.5) * 0.01;

            // Scale velocities by flow (0 = no speed, 1 = full speed)
            double velScale = 1.0 + 1.2 * be.flow; // slightly faster at high flow
            vx *= velScale;
            vy *= velScale;
            vz *= velScale;

            // Spawn a pair of particles for thickness
            double px = baseX + jx;
            double pz = baseZ + jz;
            world.addParticle(ModParticles.WATER_STREAM, px, y, pz, vx, vy, vz);
            world.addParticle(ModParticles.WATER_STREAM, px, y, pz, vx, vy, vz);

            // For the first particle in this batch, also spawn vanilla water splashes
            // at the first solid block hit below and for a few blocks after.
            if (i == 0) {
                final int MAX_SPLASH_BLOCKS = 6; // limit to avoid excessive particles
                int yi = (int)Math.floor(y - 0.1);
                int bottomY = world.getBottomY();
                int spawned = 0;
                while (yi >= bottomY && spawned < MAX_SPLASH_BLOCKS) {
                    BlockPos bp = new BlockPos((int)Math.floor(px), yi, (int)Math.floor(pz));
                    BlockState bs = world.getBlockState(bp);
                    if (!bs.isAir()) {
                        double sy = yi + 1.01; // top surface
                        // Emit a small splash burst
                        for (int k = 0; k < 6; k++) {
                            long s2 = seed + (k * 0x9E3779B97F4A7C15L);
                            double rvx = (unitNoise(s2 ^ 0x12345) - 0.5) * 0.15;
                            double rvz = (unitNoise((s2 << 1) ^ 0x56789) - 0.5) * 0.15;
                            double rvy = 0.02 + unitNoise((s2 << 2) ^ 0xABCDEF) * 0.08;
                            world.addParticle(ParticleTypes.SPLASH, px + rvx * 0.2, sy + 0.02, pz + rvz * 0.2, rvx, rvy, rvz);
                        }
                        spawned++;
                        yi--; // continue to next blocks below
                    } else {
                        yi--; // keep scanning downward to find the first hit
                    }
                }
            }
        }
    }

    /** Hash a 64-bit seed into a double in [0,1). */
    private static double unitNoise(long s) {
        s ^= (s >>> 33);
        s *= 0xff51afd7ed558ccdL;
        s ^= (s >>> 33);
        s *= 0xc4ceb9fe1a85ec53L;
        s ^= (s >>> 33);
        long mantissa = (s >>> 11) & ((1L << 53) - 1);
        return mantissa / (double)(1L << 53);
    }

    /* ---------------- NBT Save/Load ---------------- */

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("flow", flow);
        nbt.putFloat("targetFlow", targetFlow);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("flow")) {
            flow = nbt.getFloat("flow");
        }
        if (nbt.contains("targetFlow")) {
            targetFlow = nbt.getFloat("targetFlow");
        } else {
            targetFlow = flow;
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putFloat("flow", flow);
        nbt.putFloat("targetFlow", targetFlow);
        return nbt;
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    /* ---------------- Public API ---------------- */

    public float getFlow() {
        return flow;
    }

    /** Clamp flow to [0,1] and mark dirty for save/sync. */
    public void setFlow(float f) {
        setTargetFlow(f);
    }

    /** Sets the desired target flow; the visual flow approaches this each tick. */
    public void setTargetFlow(float f) {
        targetFlow = clamp01(f);
        markDirty();
        // Ensure clients receive updated BE data (targetFlow) immediately
        if (world != null) {
            if (world instanceof ServerWorld sw && !world.isClient) {
                sw.getChunkManager().markForUpdate(pos);
            }
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
