package com.cdp.codpattern.app.zombies.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks actual route movement over a short window. Revisiting an old position is allowed;
 * repeated execution failures belong to the navigation controller, not a permanent spatial
 * blacklist here. All times are server game ticks.
 */
public final class ZombiesNavigationProgressTracker {
    static final int MOTION_WINDOW_TICKS = 40;
    static final int MAX_MOTION_SAMPLES = MOTION_WINDOW_TICKS + 2;
    private static final double MIN_PROGRESS_DISTANCE_SQUARED = 0.2D * 0.2D;
    private static final double MIN_NET_MOVEMENT_RATIO = 0.5D;
    private static final double DISTANCE_EPSILON = 1.0E-9D;

    private final Deque<MotionSample> motion = new ArrayDeque<>();
    private double motionDistance;
    private long lastProgressGameTime;
    private long lastObservationGameTime;
    private double currentX;
    private double currentY;
    private double currentZ;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    public ZombiesNavigationProgressTracker(long now, double x, double y, double z) {
        if (!isFinitePosition(x, y, z)) {
            throw new IllegalArgumentException("Initial navigation position must be finite");
        }
        lastProgressGameTime = now;
        lastObservationGameTime = now;
        setCurrentPosition(x, y, z);
        resetRouteHistory();
    }

    /**
     * Returns whether this observation establishes new route progress. The caller supplies
     * whether a usable route is advancing; proximity or a new path object is not enough.
     * Small movements accumulate from the last credited position. Recent back-and-forth
     * travel must not count as sustained movement just because each individual step moved.
     * Movement without a route establishes a new baseline but cannot extend the timeout;
     * it stays in the motion window so path stops cannot erase evidence of an oscillation.
     */
    public boolean observe(long now, double x, double y, double z, boolean followingPath) {
        if (!sample(now, x, y, z)) return false;
        if (!followingPath) {
            setAnchorToCurrentPosition();
            return false;
        }
        return credit(now, x, y, z);
    }

    /** Samples all real movement, even on frames with no path advancement. */
    public boolean sample(long now, double x, double y, double z) {
        if (now < lastObservationGameTime || !isFinitePosition(x, y, z)) {
            return false;
        }
        lastObservationGameTime = now;
        setCurrentPosition(x, y, z);
        recordMotion(new MotionSample(now, x, y, z));
        return true;
    }

    /** Grants progress independently from sampling; callers must establish route evidence. */
    public boolean credit(long now, double x, double y, double z) {
        if (now != lastObservationGameTime || x != currentX || y != currentY || z != currentZ) return false;

        double dx = x - anchorX;
        double dy = y - anchorY;
        double dz = z - anchorZ;
        if (dx * dx + dy * dy + dz * dz + DISTANCE_EPSILON < MIN_PROGRESS_DISTANCE_SQUARED
                || !hasNetMovement()) {
            return false;
        }
        setAnchorToCurrentPosition();
        lastProgressGameTime = now;
        return true;
    }

    /**
     * Starts a genuinely different movement context without extending the failure deadline.
     * Replanning, target movement and temporary movement ownership changes must retain the
     * window. Otherwise repeated short routes could continually hide their return movement.
     */
    public void resetRouteHistory() {
        motion.clear();
        motion.addLast(new MotionSample(lastObservationGameTime, currentX, currentY, currentZ));
        motionDistance = 0.0D;
        setAnchorToCurrentPosition();
    }

    public long getLastProgressGameTime() {
        return lastProgressGameTime;
    }

    int motionSampleCount() {
        return motion.size();
    }

    private boolean hasNetMovement() {
        if (motion.size() < 2 || motionDistance <= 0.0D) {
            return false;
        }
        double minimumNetDistance = motionDistance * MIN_NET_MOVEMENT_RATIO;
        return motion.peekFirst().distanceSquared(motion.peekLast()) + DISTANCE_EPSILON
                >= minimumNetDistance * minimumNetDistance;
    }

    private void recordMotion(MotionSample sample) {
        // Several observations in one tick occupy one slot, not an unbounded amount of history.
        if (!motion.isEmpty() && motion.peekLast().gameTime == sample.gameTime) {
            MotionSample replaced = motion.removeLast();
            if (!motion.isEmpty()) {
                motionDistance = Math.max(0.0D, motionDistance - motion.peekLast().distance(replaced));
            }
        }
        if (!motion.isEmpty()) {
            motionDistance += motion.peekLast().distance(sample);
        }
        motion.addLast(sample);

        // Keep at most one sample before the window boundary, including with sparse observations.
        while (motion.size() > 2) {
            var iterator = motion.iterator();
            iterator.next();
            MotionSample second = iterator.next();
            if (motion.size() <= MAX_MOTION_SAMPLES
                    && second.gameTime > sample.gameTime - MOTION_WINDOW_TICKS) {
                break;
            }
            MotionSample removed = motion.removeFirst();
            motionDistance = Math.max(0.0D, motionDistance - removed.distance(motion.peekFirst()));
        }
    }

    private void setCurrentPosition(double x, double y, double z) {
        currentX = x;
        currentY = y;
        currentZ = z;
    }

    private void setAnchorToCurrentPosition() {
        anchorX = currentX;
        anchorY = currentY;
        anchorZ = currentZ;
    }

    private static boolean isFinitePosition(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private record MotionSample(long gameTime, double x, double y, double z) {
        private double distanceSquared(MotionSample other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        private double distance(MotionSample other) {
            return Math.sqrt(distanceSquared(other));
        }
    }
}
