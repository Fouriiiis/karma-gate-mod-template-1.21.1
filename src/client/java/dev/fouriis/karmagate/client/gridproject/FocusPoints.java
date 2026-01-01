package dev.fouriis.karmagate.client.gridproject;

import net.minecraft.util.math.Vec3d;

/** Render-thread readable focus point registry (client-only). */
public final class FocusPoints {
    private FocusPoints() {}

    public static final int MAX_POINTS = 16;

    private static final Vec3d[] POINTS = new Vec3d[MAX_POINTS];
    private static int count = 0;

    public static synchronized void add(Vec3d pos) {
        if (pos == null) return;

        if (count < MAX_POINTS) {
            POINTS[count++] = pos;
            return;
        }

        // Drop oldest.
        System.arraycopy(POINTS, 1, POINTS, 0, MAX_POINTS - 1);
        POINTS[MAX_POINTS - 1] = pos;
    }

    public static synchronized int count() {
        return count;
    }

    public static synchronized Vec3d get(int index) {
        if (index < 0 || index >= count) return null;
        return POINTS[index];
    }
}
