package com.cdp.codpattern.app.zombies.service;

public final class ZombiesNavigationProgressTrackerCompatTest {
    private ZombiesNavigationProgressTrackerCompatTest() {
    }

    public static void main(String[] args) {
        movingAwayAndClimbingCountAsProgress();
        slowMovementAccumulates();
        longRetracingPursuitContinuesToProgress();
        ordinaryRoomCircuitsCanRevisitOldGround();
        shortOscillationsCannotRenewProgress();
        repeatedSmallCircuitsCannotRenewProgress();
        routeStopsDoNotEraseOscillationEvidence();
        leavingALoopCanMakeProgressAgain();
        stationaryReplanningAndBoundaryJitterDoNotCount();
        pushingWithoutARouteDoesNotCount();
        resettingRouteHistoryDoesNotExtendTheDeadline();
        staleAndInvalidObservationsAreIgnored();
        sparseObservationsStillAccumulateMovement();
        motionHistoryIsBounded();
        repeatedObservationsInOneTickUseOneSlot();
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
        for (int tick = 1; tick <= 800; tick++) {
            tracker.observe(tick, tick * 0.005D, 0.0D, 0.0D, true);
            if (tick >= 40) {
                require(tick - tracker.getLastProgressGameTime() < 40,
                        "slow steady motion must accumulate instead of losing its baseline every sample");
            }
        }
        require(tracker.getLastProgressGameTime() == 800,
                "movement smaller than the threshold per tick must accumulate");
    }

    private static void longRetracingPursuitContinuesToProgress() {
        ZombiesNavigationProgressTracker tracker = tracker();
        // Each 24-block leg takes 480 ticks: returning over the old path must survive
        // both the recovery threshold and more than one complete recycling timeout.
        for (int tick = 1; tick <= 1920; tick++) {
            int phase = tick % 960;
            double x = (phase <= 480 ? phase : 960 - phase) * 0.05D;
            tracker.observe(tick, x, 0.0D, 0.0D, true);
            require(tick - tracker.getLastProgressGameTime() <= 40,
                    "a genuine long return route must keep progressing through previously visited positions");
        }
        require(tracker.getLastProgressGameTime() > 1880,
                "repeated long return legs must not exhaust a permanent visited-cell allowance");
    }

    private static void ordinaryRoomCircuitsCanRevisitOldGround() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 960; tick++) {
            double[] point = squarePoint(tick % 240, 60, 6.0D);
            tracker.observe(tick, point[0], 0.0D, point[1], true);
            require(tick - tracker.getLastProgressGameTime() <= 10,
                    "continuous pursuit around a room must not expire after its first circuit");
        }
    }

    private static void shortOscillationsCannotRenewProgress() {
        for (int period : new int[]{4, 10, 20, 30, 40}) {
            ZombiesNavigationProgressTracker tracker = tracker();
            for (int tick = 1; tick <= 800; tick++) {
                int phase = tick % period;
                double x = 2.0D * Math.min(phase, period - phase) / period;
                tracker.observe(tick, x, 0.0D, 0.0D, true);
            }
            require(tracker.getLastProgressGameTime() <= 80,
                    "one-block oscillations cannot periodically renew the timeout; period=" + period);
        }
    }

    private static void repeatedSmallCircuitsCannotRenewProgress() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 800; tick++) {
            double[] point = squarePoint(tick % 20, 5, 0.5D);
            tracker.observe(tick, point[0], 0.0D, point[1], true);
        }
        require(tracker.getLastProgressGameTime() <= 80,
                "a half-block circuit must stop counting once the motion window contains its returns");
    }

    private static void routeStopsDoNotEraseOscillationEvidence() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 800; tick++) {
            int phase = tick % 20;
            double x = Math.min(phase, 20 - phase) * 0.1D;
            tracker.observe(tick, x, 0.0D, 0.0D, phase != 0 && phase != 10);
        }
        require(tracker.getLastProgressGameTime() <= 80,
                "ending and rebuilding a route at every turn cannot discard the physical return movement");
    }

    private static void leavingALoopCanMakeProgressAgain() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 400; tick++) {
            tracker.observe(tick, tick % 2 == 0 ? 0.0D : 0.5D, 0.0D, 0.0D, true);
        }
        require(tracker.getLastProgressGameTime() < 80, "the initial oscillation must first stop counting");
        for (int tick = 401; tick <= 500; tick++) {
            tracker.observe(tick, -(tick - 400) * 0.05D, 0.0D, 0.0D, true);
        }
        require(tracker.getLastProgressGameTime() >= 496,
                "a resolved obstruction must permit sustained movement without an explicit history reset");
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
                "reset must establish the current position rather than crediting standing still");
        require(tracker.observe(70, 1.0D, 0.0D, 0.0D, true), "new context permits actual new movement");
    }

    private static void staleAndInvalidObservationsAreIgnored() {
        ZombiesNavigationProgressTracker tracker = tracker();
        tracker.observe(20, 0.5D, 0.0D, 0.0D, true);
        require(!tracker.observe(10, 5.0D, 0.0D, 0.0D, true), "stale samples must be ignored");
        require(!tracker.observe(30, Double.NaN, 0.0D, 0.0D, true), "invalid samples must be ignored");
        require(tracker.getLastProgressGameTime() == 20, "invalid observations cannot rewrite progress time");
    }

    private static void sparseObservationsStillAccumulateMovement() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 100; tick <= 1000; tick += 100) {
            require(tracker.observe(tick, tick * 0.005D, 0.0D, 0.0D, true),
                    "a long sampling gap must not discard the observed displacement");
        }
    }

    private static void motionHistoryIsBounded() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int tick = 1; tick <= 1000; tick++) {
            tracker.observe(tick, tick, 0.0D, 0.0D, true);
            require(tracker.motionSampleCount() <= ZombiesNavigationProgressTracker.MAX_MOTION_SAMPLES,
                    "motion history must remain bounded during long paths");
        }
        require(tracker.getLastProgressGameTime() == 1000, "long productive paths must keep progressing");
    }

    private static void repeatedObservationsInOneTickUseOneSlot() {
        ZombiesNavigationProgressTracker tracker = tracker();
        for (int observation = 0; observation < 1000; observation++) {
            tracker.observe(10, 0.5D, 0.0D, 0.0D, true);
        }
        require(tracker.motionSampleCount() == 2, "one game tick must occupy at most one motion sample");
        require(tracker.getLastProgressGameTime() == 10, "repeated reads cannot advance the game clock");
    }

    private static double[] squarePoint(int phase, int ticksPerSide, double sideLength) {
        double offset = (phase % ticksPerSide) * sideLength / ticksPerSide;
        return switch (phase / ticksPerSide) {
            case 0 -> new double[]{offset, 0.0D};
            case 1 -> new double[]{sideLength, offset};
            case 2 -> new double[]{sideLength - offset, sideLength};
            default -> new double[]{0.0D, sideLength - offset};
        };
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
