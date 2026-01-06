package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a 3D projection zone defined by two corner BlockPos positions.
 * The pattern is projected outward from the center XZ of the zone onto visible block faces,
 * as if there were 4 flat projectors facing outward from the center.
 */
public final class ProjectionZone {
    private final BlockPos min;
    private final BlockPos max;
    private final double centerX;
    private final double centerZ;
    private final Box bounds;

    // Static list of all active projection zones
    private static final List<ProjectionZone> ZONES = new ArrayList<>();

    public ProjectionZone(BlockPos corner1, BlockPos corner2) {
        // Normalize to ensure min < max
        int minX = Math.min(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int maxY = Math.max(corner1.getY(), corner2.getY());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());

        this.min = new BlockPos(minX, minY, minZ);
        this.max = new BlockPos(maxX, maxY, maxZ);

        // Center XZ of the zone (projection origin)
        this.centerX = (minX + maxX + 1) / 2.0;
        this.centerZ = (minZ + maxZ + 1) / 2.0;

        // Bounding box for containment checks
        this.bounds = new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public BlockPos getMin() {
        return min;
    }

    public BlockPos getMax() {
        return max;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public Box getBounds() {
        return bounds;
    }

    /**
     * Checks if a block position is within this zone's bounds.
     */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /**
     * Checks if a block position is within this zone's XZ bounds (ignoring Y).
     */
    public boolean containsXZ(int x, int z) {
        return x >= min.getX() && x <= max.getX()
            && z >= min.getZ() && z <= max.getZ();
    }

    /**
     * Gets the width (X dimension) of the zone in blocks.
     */
    public int getWidth() {
        return max.getX() - min.getX() + 1;
    }

    /**
     * Gets the height (Y dimension) of the zone in blocks.
     */
    public int getHeight() {
        return max.getY() - min.getY() + 1;
    }

    /**
     * Gets the depth (Z dimension) of the zone in blocks.
     */
    public int getDepth() {
        return max.getZ() - min.getZ() + 1;
    }

    // ========== Static zone management ==========

    /**
     * Registers a new projection zone.
     */
    public static void addZone(ProjectionZone zone) {
        ZONES.add(zone);
    }

    /**
     * Removes a projection zone.
     */
    public static void removeZone(ProjectionZone zone) {
        ZONES.remove(zone);
    }

    /**
     * Clears all projection zones.
     */
    public static void clearZones() {
        ZONES.clear();
    }

    /**
     * Gets an unmodifiable list of all active projection zones.
     */
    public static List<ProjectionZone> getZones() {
        return Collections.unmodifiableList(ZONES);
    }

    /**
     * Initializes the default test zones.
     */
    public static void initTestZones() {
        clearZones();
        // Test zone: 6x6x6 cube from (0, 0, 0) to (5, 5, 5)
        addZone(new ProjectionZone(new BlockPos(0, 0, 0), new BlockPos(50, 50, 50)));
    }

    @Override
    public String toString() {
        return "ProjectionZone{min=" + min + ", max=" + max + ", center=(" + centerX + ", " + centerZ + ")}";
    }
}
