package com.cdp.codpattern.app.zombies.service.navigation;

/** Native path identity may change; directed node coordinates remain the progress reference. */
public final class NativeRouteProgress {
    private NativeRouteProgress() { }

    public static double projection(double fromX, double fromY, double fromZ,
            double toX, double toY, double toZ, double x, double y, double z) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(length) || length < 1.0E-6) return Double.NaN;
        double along = ((x - fromX) * dx + (y - fromY) * dy + (z - fromZ) * dz) / length;
        if (!Double.isFinite(along)) return Double.NaN;
        return Math.max(0, Math.min(length, along));
    }
}
