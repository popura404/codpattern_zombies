package com.cdp.codpattern.client.zombies;

import org.joml.Matrix4f;
import org.joml.Vector4d;

/** Projects the same model coordinates used for drawing into GUI screen coordinates. */
public final class ZombiesLabelProjection {
    private static final double MIN_CLIP_W = 1.0E-6D;

    private ZombiesLabelProjection() {
    }

    /**
     * Projects an axis-aligned box, including a flat text rectangle when minZ == maxZ.
     * Text is rejected when it crosses the near plane. Weapon bounds are clipped there,
     * so a weapon partly behind the camera still reserves its visible screen footprint.
     * The result is intentionally not clamped to the viewport.
     */
    public static ZombiesObjectLabelLayout.Rect projectBox(
            Matrix4f clipMatrix,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            int screenWidth, int screenHeight,
            boolean clipNearPlane
    ) {
        if (clipMatrix == null || !clipMatrix.isFinite() || screenWidth <= 0 || screenHeight <= 0
                || !Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }

        Vector4d[] corners = new Vector4d[8];
        ScreenBounds bounds = new ScreenBounds(screenWidth, screenHeight);
        for (int index = 0; index < corners.length; index++) {
            Vector4d corner = new Vector4d(
                    (index & 1) == 0 ? minX : maxX,
                    (index & 2) == 0 ? minY : maxY,
                    (index & 4) == 0 ? minZ : maxZ,
                    1.0D).mul(clipMatrix);
            if (!corner.isFinite()) {
                return null;
            }
            corners[index] = corner;
            if (corner.z + corner.w >= 0.0D && corner.w > MIN_CLIP_W) {
                if (!bounds.include(corner)) {
                    return null;
                }
            } else if (!clipNearPlane) {
                return null;
            }
        }

        if (clipNearPlane) {
            // Every box edge changes exactly one of the three corner-index bits.
            for (int index = 0; index < corners.length; index++) {
                for (int axisBit = 1; axisBit <= 4; axisBit <<= 1) {
                    if ((index & axisBit) != 0) {
                        continue;
                    }
                    Vector4d start = corners[index];
                    Vector4d end = corners[index | axisBit];
                    double startDistance = start.z + start.w;
                    double endDistance = end.z + end.w;
                    if ((startDistance < 0.0D) == (endDistance < 0.0D)) {
                        continue;
                    }
                    double fraction = startDistance / (startDistance - endDistance);
                    Vector4d intersection = new Vector4d(start).lerp(end, fraction);
                    if (!intersection.isFinite()) {
                        return null;
                    }
                    if (intersection.w > MIN_CLIP_W && !bounds.include(intersection)) {
                        return null;
                    }
                }
            }
        }
        return bounds.toRect();
    }

    private static final class ScreenBounds {
        private final int width;
        private final int height;
        private double left = Double.POSITIVE_INFINITY;
        private double top = Double.POSITIVE_INFINITY;
        private double right = Double.NEGATIVE_INFINITY;
        private double bottom = Double.NEGATIVE_INFINITY;

        private ScreenBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private boolean include(Vector4d clip) {
            double x = (clip.x / clip.w + 1.0D) * 0.5D * width;
            double y = (1.0D - clip.y / clip.w) * 0.5D * height;
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return false;
            }
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
            return true;
        }

        private ZombiesObjectLabelLayout.Rect toRect() {
            return Double.isFinite(left) ? new ZombiesObjectLabelLayout.Rect(left, top, right, bottom) : null;
        }
    }
}
