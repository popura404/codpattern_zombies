package com.cdp.codpattern.app.zombies.gametest;

import java.util.List;
import java.util.Map;

/** Deterministic observation tests; no server boot or entity behavior is substituted. */
public final class ZombiesNavigationTimingCompatTest {
    private static int checks;

    public static void main(String[] args) {
        completedTickMustWaitForEnd();
        singleTickWindowStillIncludesItsEnd();
        overlappingWindowsShareWholeServerSamples();
        missingTicksCannotPass();
        staleSchedulerSnapshotCannotPass();
        uncoveredSchedulingCannotPass();
        mismatchedStartCannotPass();
        forgeStartPrecedesServerCounterIncrement();
        emptyWindowReportsMissingValues();
        cleanupFailureOverridesSuccess();
        compatibilityObservationDoesNotBecomeAPerformanceGate();
        System.out.println("ZombiesNavigationTimingCompatTest: " + checks + " checks passed");
    }

    private static void completedTickMustWaitForEnd() {
        var window = window("cold", 10, true);
        sample(window, 10, 80);
        sample(window, 11, 2);
        window.finish(12, true);
        require(!complete(window), "finishing inside tick 12 cannot seal it before END");
        sample(window, 12, 3);
        Map<String, Object> report = window.report();
        require(complete(window) && number(report, "serverTickSamples") == 3,
                "the completion tick must be present exactly once");
        require(number(report, "serverTickP95Millis") == 80 && number(report, "serverTickP99Millis") == 80,
                "cold planning cost must not disappear through implicit warmup");
        require(Boolean.FALSE.equals(report.get("p95Within50Millis")), "an expensive cold window must fail the fixed threshold");
        sample(window, 12, 900);
        sample(window, 13, 900);
        require(number(window.report(), "serverTickSamples") == 3,
                "duplicate and post-completion ticks must not inflate the window");
        checks++;
    }

    private static void singleTickWindowStillIncludesItsEnd() {
        var window = window("one", 4, true);
        window.finish(4, true);
        require(!complete(window), "a same-tick success is initially unmeasured");
        sample(window, 4, 7);
        require(complete(window) && number(window.report(), "serverTickP99Millis") == 7,
                "one-tick observations must include the actual completed span");
        checks++;
    }

    private static void overlappingWindowsShareWholeServerSamples() {
        var a = window("a", 10, true);
        var b = window("b", 11, true);
        sample(a, 10, 1);
        var shared = snapshot(11, 6, 11, 4);
        b.finish(11, true);
        a.sample(shared, List.of("a", "b"), 10);
        b.sample(shared, List.of("a", "b"), 10);
        a.finish(12, true);
        sample(a, 12, 2);
        require(complete(a) && complete(b), "overlapping windows must each seal their own END");
        require(number(a.report(), "maxConcurrentWindows") == 2 && number(b.report(), "maxConcurrentWindows") == 2,
                "both windows must disclose the same concurrent load");
        require(number(a.report(), "serverTickMaxMillis") == 6 && number(b.report(), "serverTickMaxMillis") == 6,
                "shared tick observations must not add room times into a fictitious 12 ms server tick");
        require(a.report().get("concurrentScenarios").equals(List.of("a", "b")), "peer scenario names must be preserved");
        checks++;
    }

    private static void missingTicksCannotPass() {
        var window = window("gap", 10, true);
        sample(window, 10, 1);
        window.finish(12, true);
        sample(window, 12, 1);
        require(!complete(window) && number(window.report(), "missingTickSamples") == 1,
                "missing middle ticks must fail timing completeness");
        var late = window("late", 10, true);
        late.finish(11, true);
        sample(late, 11, 1);
        require(!complete(late), "missing the first cold tick must also fail completeness");
        checks++;
    }

    private static void staleSchedulerSnapshotCannotPass() {
        var window = window("stale", 10, true);
        window.finish(10, true);
        window.sample(snapshot(10, 5, 9, 1), List.of("stale"), 9);
        require(!complete(window) && number(window.report(), "schedulerTickMismatchSamples") == 1,
                "a small previous-tick scheduler value cannot prove END coverage");
        checks++;
    }

    private static void uncoveredSchedulingCannotPass() {
        var window = window("uncovered", 10, true);
        window.finish(10, true);
        window.sample(snapshot(10, 2, 10, 3), List.of("uncovered"), 9);
        require(!complete(window) && number(window.report(), "maxUncoveredSchedulerNanos") == 1_000_000,
                "a measured span shorter than scheduling must be rejected");
        checks++;
    }

    private static void mismatchedStartCannotPass() {
        var window = window("start", 10, true);
        window.finish(10, true);
        window.sample(snapshot(10, 5, 10, 1), List.of("start"), 10);
        require(!complete(window) && number(window.report(), "startTickMismatchSamples") == 1,
                "an END paired with another START must not pass");
        checks++;
    }

    private static void forgeStartPrecedesServerCounterIncrement() {
        // Mapped MinecraftServer.tickServer bytecode: onPreServerTick, ++tickCount, tickChildren, END.
        int rawStartCount = 40;
        int worldAndEndCount = 41;
        var window = window("forge-counter-order", worldAndEndCount, true);
        window.finish(worldAndEndCount, true);
        window.sample(snapshot(worldAndEndCount, 7, worldAndEndCount, 1),
                List.of("forge-counter-order"), rawStartCount);
        require(complete(window) && number(window.report(), "firstMeasuredServerTick") == worldAndEndCount
                        && number(window.report(), "lastMeasuredServerTick") == worldAndEndCount
                        && number(window.report(), "serverTickSamples") == 1,
                "raw START 40 must pair with world/scheduler/END 41 without shifting the behavioral window");
        var missingStart = window("missing-start", worldAndEndCount, true);
        missingStart.finish(worldAndEndCount, true);
        missingStart.sample(snapshot(worldAndEndCount, 7, worldAndEndCount, 1),
                List.of("missing-start"), rawStartCount - 1);
        require(!complete(missingStart), "a truly stale START must still fail strict coverage");
        checks++;
    }

    private static void emptyWindowReportsMissingValues() {
        var window = window("empty", 10, true);
        window.finish(10, false);
        require(!complete(window) && window.report().get("serverTickP95Millis") == null
                        && window.report().get("p95Within50Millis") == null,
                "absence of measurement must never become zero cost or a pass");
        checks++;
    }

    private static void cleanupFailureOverridesSuccess() {
        var window = window("cleanup", 10, true);
        window.finish(10, true);
        window.finish(10, false);
        window.finish(10, true);
        sample(window, 10, 1);
        require(complete(window) && Boolean.FALSE.equals(window.report().get("functionalSuccess")),
                "accurate timing cannot erase a cleanup failure");
        checks++;
    }

    private static void compatibilityObservationDoesNotBecomeAPerformanceGate() {
        var window = window("old-short-combat", 10, false);
        window.finish(10, true);
        sample(window, 10, 80);
        require(complete(window) && Boolean.FALSE.equals(window.report().get("performanceGate"))
                        && number(window.report(), "serverTickP95Millis") == 80,
                "fixed classification must preserve an expensive compatibility observation without inventing a new gate");
        checks++;
    }

    private static ZombiesNavigationTestTiming.TickWindow window(String scenario, long tick, boolean gate) {
        return new ZombiesNavigationTestTiming.TickWindow(scenario, scenario+"test", "fixture."+scenario, tick, gate);
    }
    private static ZombiesNavigationTestTiming.Snapshot snapshot(int tick, long millis, long schedulerTick, long schedulerMillis) {
        return new ZombiesNavigationTestTiming.Snapshot(tick, millis*1_000_000, tick*50L, tick*50L+millis,
                schedulerTick, schedulerMillis*1_000_000);
    }
    private static void sample(ZombiesNavigationTestTiming.TickWindow window, int tick, long millis) {
        window.sample(snapshot(tick, millis, tick, 0), List.of(window.scenario), tick - 1);
    }
    private static boolean complete(ZombiesNavigationTestTiming.TickWindow window) {
        return Boolean.TRUE.equals(window.report().get("complete"));
    }
    private static double number(Map<String,Object> report, String key) { return ((Number)report.get(key)).doubleValue(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
