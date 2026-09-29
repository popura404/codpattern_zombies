package com.cdp.codpattern.app.zombies.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks new ground covered while following a route, independently of a target's movement
 * or the identity of the current path object. All times are server game ticks.
 */
public final class ZombiesNavigationProgressTracker {
    static final int MAX_VISITED_CELLS = 128;
    private static final double CELL_SIZE = 0.5D;
    private static final double MIN_PROGRESS_DISTANCE_SQUARED = 0.2D * 0.2D;

    private final Set<Cell> visitedCells = new LinkedHashSet<>();
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
     * whether a usable route is being followed; proximity to the player alone is not enough.
     * Small movements accumulate from the last credited position, so slow mobs can progress.
     * Movement without a route establishes a new baseline but cannot extend the timeout.
     */
    public boolean observe(long now, double x, double y, double z, boolean followingPath) {
        if (now < lastObservationGameTime || !isFinitePosition(x, y, z)) {
            return false;
        }
        lastObservationGameTime = now;
        setCurrentPosition(x, y, z);
        Cell cell = Cell.at(x, y, z);
        if (!followingPath) {
            remember(cell);
            setAnchorToCurrentPosition();
            return false;
        }

        double dx = x - anchorX;
        double dy = y - anchorY;
        double dz = z - anchorZ;
        if (visitedCells.contains(cell)
                || dx * dx + dy * dy + dz * dz < MIN_PROGRESS_DISTANCE_SQUARED) {
            return false;
        }
        remember(cell);
        setAnchorToCurrentPosition();
        lastProgressGameTime = now;
        return true;
    }

    /**
     * Starts spatial tracking for a genuinely different route context without extending
     * the failure deadline. Do not call this merely because a path was reconstructed or
     * the target moved: doing so would let repeated endpoint loops renew their history.
     */
    public void resetRouteHistory() {
        visitedCells.clear();
        remember(Cell.at(currentX, currentY, currentZ));
        setAnchorToCurrentPosition();
    }

    public long getLastProgressGameTime() {
        return lastProgressGameTime;
    }

    int visitedCellCount() {
        return visitedCells.size();
    }

    private void remember(Cell cell) {
        visitedCells.add(cell);
        if (visitedCells.size() > MAX_VISITED_CELLS) {
            var oldest = visitedCells.iterator();
            oldest.next();
            oldest.remove();
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

    private record Cell(long x, long y, long z) {
        private static Cell at(double x, double y, double z) {
            return new Cell(
                    (long) Math.floor(x / CELL_SIZE),
                    (long) Math.floor(y / CELL_SIZE),
                    (long) Math.floor(z / CELL_SIZE));
        }
    }
}
