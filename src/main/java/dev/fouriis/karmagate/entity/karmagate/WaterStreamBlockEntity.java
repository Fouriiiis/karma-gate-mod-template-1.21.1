package dev.fouriis.karmagate.entity.karmagate;

import dev.fouriis.karmagate.entity.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class WaterStreamBlockEntity extends WaterfallBlockEntity {
    private float targetFlow = 0.0f;

    public WaterStreamBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WATER_STREAM_BLOCK_ENTITY, pos, state);
        // Start dry by default
        this.setFlow(0.0f);
        this.targetFlow = 0.0f;
    }

    /** Client tick: smooth toward target and maintain keyframes. */
    public static void tick(World world, BlockPos pos, BlockState state, WaterStreamBlockEntity be) {
        if (!world.isClient) return;

        // Smooth current flow toward target each tick
        final float speed = 0.05f; // units per tick
        float currentFlow = be.getFlow();
        if (Math.abs(currentFlow - be.targetFlow) <= speed) {
            currentFlow = be.targetFlow;
        } else {
            currentFlow += Math.signum(be.targetFlow - currentFlow) * speed;
        }
        
        // Update the flow in the superclass (WaterfallBlockEntity)
        // This updates the 'flow' field and triggers any necessary updates
        be.setFlow(currentFlow);
        // Delegate to WaterfallBlockEntity to maintain keyframes for rendering
        WaterfallBlockEntity.clientTick(world, pos, state, be);
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putFloat("targetFlow", targetFlow);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // On client, ignore server's flow value for WaterStreamBlockEntity
        // because we animate it locally. We only want to read 'targetFlow'.
        if (world != null && world.isClient && nbt.contains("flow")) {
            float serverFlow = nbt.getFloat("flow");
            nbt.remove("flow");
            super.readNbt(nbt, lookup);
            nbt.putFloat("flow", serverFlow); // Restore just in case
        } else {
            super.readNbt(nbt, lookup);
        }

        if (nbt.contains("targetFlow")) {
            targetFlow = nbt.getFloat("targetFlow");
        } else {
            targetFlow = getFlow();
        }
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        NbtCompound nbt = super.toInitialChunkDataNbt(lookup);
        nbt.putFloat("targetFlow", targetFlow);
        return nbt;
    }

    /** Sets the desired target flow; the visual flow approaches this each tick. */
    public void setTargetFlow(float f) {
        targetFlow = Math.max(0f, Math.min(1f, f));
        markDirty();
        // Ensure clients receive updated BE data (targetFlow) immediately
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }
}
