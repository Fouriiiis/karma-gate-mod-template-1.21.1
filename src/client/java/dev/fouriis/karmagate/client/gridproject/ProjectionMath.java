package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.util.math.Vec3d;

/**
 * Utility class for projection mathematics used by both mesh generation and circle positioning.
 * Provides consistent mapping from world coordinates to the square-perimeter UV coordinate system.
 */
public final class ProjectionMath {
    private ProjectionMath() {}

    /**
     * Computes the perimeter-U coordinate for a point on the square perimeter projection.
     * Maps a world (X, Z) position to a continuous U value along the square perimeter.
     * 
     * The square perimeter is centered at (centerX, centerZ) with half-side = radius.
     * The perimeter wraps from 0 to 8*radius:
     *   - [0, 2R): right edge (positive X side)
     *   - [2R, 4R): back edge (positive Z side)
     *   - [4R, 6R): left edge (negative X side)  
     *   - [6R, 8R): front edge (negative Z side)
     * 
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param centerX Zone center X
     * @param centerZ Zone center Z
     * @param radius Zone radius (half the side length)
     * @return Perimeter U coordinate in range [0, 8*radius)
     */
    public static float computeSquarePerimeterU(float worldX, float worldZ,
                                                 float centerX, float centerZ,
                                                 float radius) {
        float R = Math.max(radius, 1e-6f);

        float rx = worldX - centerX;
        float rz = worldZ - centerZ;

        float len = (float) Math.sqrt(rx * rx + rz * rz);
        float dx = (len > 1e-9f) ? (rx / len) : 1.0f;
        float dz = (len > 1e-9f) ? (rz / len) : 0.0f;

        float m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m < 1e-9f) m = 1.0f;

        float hx = dx * (R / m);
        float hz = dz * (R / m);

        float ax = Math.abs(hx);
        float az = Math.abs(hz);

        float u;
        if (ax >= az) {
            if (hx >= 0.0f) {
                u = hz + R;
            } else {
                u = 4.0f * R + (R - hz);
            }
        } else {
            if (hz >= 0.0f) {
                u = 2.0f * R + (R - hx);
            } else {
                u = 6.0f * R + (hx + R);
            }
        }

        float perim = 8.0f * R;
        u = u % perim;
        if (u < 0.0f) u += perim;

        return u;
    }

    /**
     * Overload accepting doubles for convenience.
     */
    public static float computeSquarePerimeterU(double worldX, double worldZ,
                                                 double centerX, double centerZ,
                                                 double radius) {
        return computeSquarePerimeterU(
            (float) worldX, (float) worldZ,
            (float) centerX, (float) centerZ,
            (float) radius
        );
    }

    /**
     * Computes the perimeter length for a zone with given radius.
     */
    public static float getPerimeterLength(float radius) {
        return 8.0f * Math.max(radius, 1e-6f);
    }

    /**
     * Computes ring distance on a periodic coordinate.
     * Returns minimum distance considering wraparound.
     */
    public static float ringDistance(float a, float b, float period) {
        float d = Math.abs(a - b);
        return Math.min(d, period - d);
    }
}
