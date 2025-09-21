package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.block.karmagate.WaterStreamBlock;
import dev.fouriis.karmagate.particle.ModParticles;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class WaterStreamBlockEntity extends BlockEntity {
    /** Flow strength in [0.0, 1.0]. 0 = no flow, 1 = max flow. */
    private float flow = 1.0f;

    public WaterStreamBlockEntity(BlockPos pos, BlockState state) {
        super(dev.fouriis.karmagate.entity.ModBlockEntities.WATER_STREAM_BLOCK_ENTITY, pos, state);
    }

    /** Client tick: emit a stream of particles depending on flow strength. */
    public static void tick(World world, BlockPos pos, BlockState state, WaterStreamBlockEntity be) {
        if (!world.isClient) return;
        if (!state.get(WaterStreamBlock.ENABLED)) return;
        if (be.flow <= 0f) return; // nothing if flow = 0

        final long t = world.getTime();
        final double baseX = pos.getX() + 0.5;
        final double baseY = pos.getY() + 0.95;
        final double baseZ = pos.getZ() + 0.5;

        // Scale particle count with flow (up to 2 per tick at full flow)
        int count = Math.max(1, Math.round(2 * be.flow));

        for (int i = 0; i < count; i++) {
            long seed = (t * 341873128712L) ^ (pos.asLong() * 132897987541L) ^ (i * 0x9E3779B97F4A7C15L);

            double jx = (unitNoise(seed ^ 0xA2C2A) - 0.5) * 0.98;
            double jz = (unitNoise(seed ^ 0xB5D5B) - 0.5) * 0.98;

            double y = baseY - (i * 0.18) - ((t % 5) * 0.01);

            // Base velocities
            double vy = -0.22 - unitNoise(seed ^ 0xC7E7C) * 0.04;
            double vx = (unitNoise(seed ^ 0xD8F8D) - 0.5) * 0.01;
            double vz = (unitNoise(seed ^ 0xE9A9E) - 0.5) * 0.01;

            // Scale velocities by flow (0 = no speed, 1 = full speed)
            vx *= (2.0 * be.flow);
            vy *= (2.0 * be.flow);
            vz *= (2.0 * be.flow);

            // Spawn a pair of particles for thickness
            world.addParticle(ModParticles.WATER_STREAM, baseX + jx, y, baseZ + jz, vx, vy, vz);
            world.addParticle(ModParticles.WATER_STREAM, baseX + jx, y, baseZ + jz, vx, vy, vz);
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
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("flow")) {
            flow = nbt.getFloat("flow");
        }
    }

    /* ---------------- Public API ---------------- */

    public float getFlow() {
        return flow;
    }

    /** Clamp flow to [0,1] and mark dirty for save/sync. */
    public void setFlow(float f) {
        flow = Math.max(0f, Math.min(1f, f));
        markDirty();
    }
}
