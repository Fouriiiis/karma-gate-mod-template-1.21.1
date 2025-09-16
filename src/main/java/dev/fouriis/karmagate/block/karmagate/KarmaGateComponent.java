package dev.fouriis.karmagate.block.karmagate;

import java.util.UUID;
import net.minecraft.util.math.BlockPos;

public interface KarmaGateComponent {
    KarmaGateRole role();

    // optional but useful hooks for grouping
    default void bindToController(BlockPos controllerPos, UUID gateID) {}
    default void unbindFromController() {}
}