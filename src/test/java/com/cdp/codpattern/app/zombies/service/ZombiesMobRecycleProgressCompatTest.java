package com.cdp.codpattern.app.zombies.service;

public final class ZombiesMobRecycleProgressCompatTest {
    private ZombiesMobRecycleProgressCompatTest() {
    }

    public static void main(String[] args) {
        blockedOrOscillatingMobEventuallyTimesOut();
        routeProgressPreservesLongDetours();
        activeEngagementPreservesStationaryCombat();
        actionGraceHasAFiniteDeadline();
        observationUsesGameTimeAndProvidesInitialGrace();
        System.out.println("PASS zombies mob recycle progress compat");
    }

    private static void blockedOrOscillatingMobEventuallyTimesOut() {
        var monitor = new ZombiesMobRecycleService.GroundStallMonitor(1_000L);
        // Close targets and arbitrary displacement are not evidence of a usable route or an attack.
        for (long now = 1_000L; now < 1_320L; now += 80L) {
            require(!monitor.timedOut(now, 1_000L, 0L, 0L),
                    "a blocked mob should retain its existing recovery window");
        }
        require(monitor.timedOut(1_320L, 1_000L, 0L, 0L),
                "a close blocked or oscillating mob must expire without useful navigation evidence");
        require(monitor.timedOut(9_000L, 1_000L, 0L, 0L),
                "repeated observations must not refresh stalled pursuit");
    }

    private static void routeProgressPreservesLongDetours() {
        var monitor = new ZombiesMobRecycleService.GroundStallMonitor(1_000L);
        // Advancing path nodes can lead sideways, upward or away from the target.
        for (long now = 1_080L; now <= 3_400L; now += 80L) {
            require(!monitor.timedOut(now, now - 20L, 0L, 0L),
                    "productive detours must outlive the ordinary stuck timeout");
        }
        require(!monitor.timedOut(3_680L, 3_380L, 0L, 0L),
                "brief crowding after route progress must be tolerated");
        require(monitor.timedOut(3_760L, 3_380L, 0L, 0L),
                "route progress must not provide a permanent exemption after stopping");
    }

    private static void activeEngagementPreservesStationaryCombat() {
        var monitor = new ZombiesMobRecycleService.GroundStallMonitor(1_000L);
        require(!monitor.timedOut(5_000L, 1_000L, 4_990L, 0L),
                "legitimate engagement must protect a mob that need not move");
        require(!monitor.timedOut(5_300L, 1_000L, 4_990L, 0L),
                "losing attack access must leave time for a new route");
        require(monitor.timedOut(5_320L, 1_000L, 4_990L, 0L),
                "old engagement must expire when a wall or floor prevents further attacks");
    }

    private static void actionGraceHasAFiniteDeadline() {
        var monitor = new ZombiesMobRecycleService.GroundStallMonitor(1_000L);
        require(!monitor.timedOut(1_360L, 1_000L, 0L, 1_400L),
                "a committed action must not be interrupted during its finite grace window");
        require(monitor.timedOut(1_400L, 1_000L, 0L, 1_400L),
                "an action with no progress must lose its exemption at the deadline");
        require(!monitor.timedOut(1_440L, 1_410L, 0L, 1_400L),
                "successful movement after an action should restart normal progress tracking");
    }

    private static void observationUsesGameTimeAndProvidesInitialGrace() {
        var monitor = new ZombiesMobRecycleService.GroundStallMonitor(50_000L);
        require(!monitor.timedOut(50_000L, 0L, 0L, 0L),
                "a newly observed controller must not inherit a timeout from the world's age");
        require(!monitor.timedOut(50_319L, 0L, 0L, 0L),
                "the full initial game-time recovery window must be retained");
        require(monitor.timedOut(50_640L, 0L, 0L, 0L),
                "skipped observations must not delay expiry by counting scanner invocations");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
