package com.cdp.codpattern.app.zombies.service;

public final class ZombiesNavigationProgressTrackerCompatTest {
    private ZombiesNavigationProgressTrackerCompatTest() {
    }

    public static void main(String[] args) {
        movingAwayAndClimbingCountAsProgress();
        slowMovementAccumulates();
        repeatedRouteLoopsCannotRenewProgress();
        stationaryReplanningAndBoundaryJitterDoNotCount();
        pushingWithoutARouteDoesNotCount();
        resettingRouteHistoryDoesNotExtendTheDeadline();
        staleAndInvalidObservationsAreIgnored();
        spatialHistoryIsBounded();
    }

    private static void movingAwayAndClimbingCountAsProgress() {
        ZombiesNavigationProgressTracker tracker = tracker();
        require(tracker.observe(10, -0.5D, 0.0D, 0.0D, true),
                "a route may first move away from the target");
        require(tracker.observe(20, -0.5D, 0.5D, 0.0D, true),
                "ascent must count without horizontal distance reduction");
        require(tracker.getLastProgressGameTime() == 20, "ascent should renew actual progress");
    }

    private static void slowMovementAccumulates() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 20; tick++) {
            tracker.observe(tick, tick * 0.05D, 0.0D, 0.0D, true);
        }
        require(tracker.getLastProgressGameTime() == 20,
                "movement smaller than the threshold per tick must accumulate");
    }

    private static void repeatedRouteLoopsCannotRenewProgress() {
        ZombiesNavigationProgressTracker tracker = tracker();
        require(tracker.observe(10, 0.5D, 0.0D, 0.0D, true), "first outward movement counts");
        require(tracker.observe(20, 0.5D, 0.0D, 0.5D, true), "a new corner counts once");
        require(tracker.observe(30, 0.0D, 0.0D, 0.5D, true), "the third corner counts once");
        for (int tick = 40; tick < 200; tick += 4) {
            tracker.observe(tick, 0.0D, 0.0D, 0.0D, true);
            tracker.observe(tick + 1, 0.5D, 0.0D, 0.0D, true);
            tracker.observe(tick + 2, 0.5D, 0.0D, 0.5D, true);
            tracker.observe(tick + 3, 0.0D, 0.0D, 0.5D, true);
        }
        require(tracker.getLastProgressGameTime() == 30,
                "revisiting the same route cells must not indefinitely renew progress");
    }

    private static void stationaryReplanningAndBoundaryJitterDoNotCount() {
        ZombiesNavigationProgressTracker tracker = new ZombiesNavigationProgressTracker(0, 0.49D, 0, 0);
        for (int tick = 1; tick <= 100; tick++) {
            tracker.observe(tick, tick % 2 == 0 ? 0.49D : 0.51D, 0, 0, true);
        }
        require(tracker.getLastProgressGameTime() == 0,
                "crossing a cell boundary by tiny jitter must not count as route progress");
        for (int tick = 101; tick <= 200; tick++) {
            tracker.observe(tick, 0.49D, 0, 0, true);
        }
        require(tracker.getLastProgressGameTime() == 0,
                "replacing paths or moving the target without moving the mob cannot renew progress");
    }

    private static void pushingWithoutARouteDoesNotCount() {
        ZombiesNavigationProgressTracker tracker = tracker();
        tracker.observe(10, 4.0D, 0.0D, 0.0D, false);
        require(!tracker.observe(20, 4.0D, 0.0D, 0.0D, true),
                "starting a path at a pushed position cannot credit the earlier displacement");
        require(!tracker.observe(21, 4.1D, 0.0D, 0.0D, true),
                "the push must not remain in the route movement accumulator");
        require(tracker.getLastProgressGameTime() == 0, "route-free pushes cannot extend the deadline");
        require(tracker.observe(30, 4.5D, 0.0D, 0.0D, true),
                "subsequent new ground covered on a route can count");
    }

    private static void resettingRouteHistoryDoesNotExtendTheDeadline() {
        ZombiesNavigationProgressTracker tracker = tracker();
        tracker.observe(10, 0.5D, 0.0D, 0.0D, true);
        tracker.observe(50, 0.5D, 0.0D, 0.0D, false);
        tracker.resetRouteHistory();
        require(tracker.getLastProgressGameTime() == 10, "history reset cannot renew the failure deadline");
        require(!tracker.observe(60, 0.5D, 0.0D, 0.0D, true),
                "reset must remember the current cell rather than crediting standing still");
        require(tracker.observe(70, 1.0D, 0.0D, 0.0D, true), "new context permits actual new movement");
    }

    private static void staleAndInvalidObservationsAreIgnored() {
        ZombiesNavigationProgressTracker tracker = tracker();
        tracker.observe(20, 0.5D, 0.0D, 0.0D, true);
        require(!tracker.observe(10, 5.0D, 0.0D, 0.0D, true), "stale samples must be ignored");
        require(!tracker.observe(30, Double.NaN, 0.0D, 0.0D, true), "invalid samples must be ignored");
        require(tracker.getLastProgressGameTime() == 20, "invalid observations cannot rewrite progress time");
    }

    private static void spatialHistoryIsBounded() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 1000; tick++) {
            tracker.observe(tick, tick, 0.0D, 0.0D, true);
        }
        require(tracker.visitedCellCount() == ZombiesNavigationProgressTracker.MAX_VISITED_CELLS,
                "spatial history must remain bounded during long paths");
        require(tracker.getLastProgressGameTime() == 1000, "long productive paths must keep progressing");
    }

    private static ZombiesNavigationProgressTracker tracker() {
        return new ZombiesNavigationProgressTracker(0, 0.0D, 0.0D, 0.0D);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
