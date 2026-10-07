package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Test-only whole-server timing. Every observer on one server shares exactly one START/END pair. */
@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesNavigationTestTiming {
    private static final Map<MinecraftServer, Clock> CLOCKS = new IdentityHashMap<>();

    private ZombiesNavigationTestTiming() { }

    /** Installed by the verified test environment before the first behavioral tick. */
    public static void install(MinecraftServer server) {
        CLOCKS.computeIfAbsent(server, Clock::new);
    }

    public static LoadClock loadClock(MinecraftServer server) {
        install(server);
        return new LoadClock(CLOCKS.get(server));
    }

    public static void begin(GameTestHelper helper, String scenario, boolean performanceGate) {
        MinecraftServer server = helper.getLevel().getServer();
        Clock clock = CLOCKS.get(server);
        if (clock == null) throw new IllegalStateException("The verified test environment must install server timing first");
        TickWindow existing = clock.active.get(helper);
        if (existing != null) throw new IllegalStateException("Duplicate timing window for " + existing.scenario);
        if (clock.names.contains(scenario)) throw new IllegalStateException("Duplicate complex scenario name " + scenario);
        clock.names.add(scenario);
        TestIdentity identity = StackWalker.getInstance().walk(frames -> frames
                .map(frame -> {
                    String className = frame.getClassName();
                    String simpleName = className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
                    String methodName = frame.getMethodName().toLowerCase(Locale.ROOT);
                    return GameTestRegistry.findTestFunction(simpleName.toLowerCase(Locale.ROOT) + "." + methodName)
                            .or(() -> GameTestRegistry.findTestFunction(methodName))
                            .map(test -> new TestIdentity(test.getTestName(), className + "." + frame.getMethodName()))
                            .orElse(null);
                }).filter(candidate -> candidate != null)
                .findFirst().orElseThrow(() -> new IllegalStateException("Timing must begin in the original GameTest entry call")));
        clock.active.put(helper, new TickWindow(scenario, identity.testName(),
                identity.declaredTestMethod(), server.getTickCount(), performanceGate));
    }

    private record TestIdentity(String testName, String declaredTestMethod) { }

    /** Marks completion only; the current tick is sealed later, after END scheduling has run. */
    public static void finish(GameTestHelper helper, boolean success) {
        Clock clock = CLOCKS.get(helper.getLevel().getServer());
        if (clock == null) return;
        TickWindow window = clock.active.get(helper);
        if (window != null) window.finish(helper.getLevel().getServer().getTickCount(), success);
    }

    /** Observation hook around the original GameTest completion API. */
    public static void succeed(GameTestHelper helper) {
        finish(helper, true);
        helper.succeed();
    }

    public static void fail(GameTestHelper helper, String reason) {
        finish(helper, false);
        helper.fail(reason);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        Clock clock = CLOCKS.remove(event.getServer());
        if (clock != null) clock.close();
    }

    public record Snapshot(int serverTick, long nanos, long startEpochMillis, long endEpochMillis,
                           long schedulerTick, long schedulerNanos) { }

    /** Load keeps its existing previous-completed-tick and warmup policy; closing this view never stops other windows. */
    public static final class LoadClock implements AutoCloseable {
        private Clock clock;
        private final int installedDuringTick;
        private LoadClock(Clock clock) {
            this.clock = clock;
            this.installedDuringTick = clock.server.getTickCount();
        }
        public Snapshot snapshot() {
            if (clock == null) throw new IllegalStateException("Load clock is closed");
            // Its former private timer was installed inside this tick, after START. Preserve that first sample.
            if (clock.completed.serverTick() <= installedDuringTick)
                return new Snapshot(-1, 0, 0, 0, Long.MIN_VALUE, 0);
            return clock.completed;
        }
        @Override public void close() { clock = null; }
    }

    private static final class Clock implements AutoCloseable {
        final MinecraftServer server;
        final Consumer<TickEvent.ServerTickEvent> startListener = this::start;
        final Consumer<TickEvent.ServerTickEvent> endListener = this::end;
        final Map<GameTestHelper, TickWindow> active = new IdentityHashMap<>();
        final List<Map<String, Object>> finished = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>();
        long startedNanos, startedEpochMillis;
        int rawStartServerTick = -1;
        Snapshot completed = new Snapshot(-1, 0, 0, 0, Long.MIN_VALUE, 0);
        boolean closed;

        Clock(MinecraftServer server) {
            this.server = server;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, startListener);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, endListener);
        }

        void start(TickEvent.ServerTickEvent event) {
            if (closed || event.getServer() != server || event.phase != TickEvent.Phase.START) return;
            startedNanos = System.nanoTime();
            startedEpochMillis = System.currentTimeMillis();
            // Forge onPreServerTick runs before MinecraftServer increments tickCount.
            rawStartServerTick = server.getTickCount();
        }

        void end(TickEvent.ServerTickEvent event) {
            if (closed || event.getServer() != server || event.phase != TickEvent.Phase.END || startedNanos == 0) return;
            // Take the endpoint before report formatting/I/O. All production END handlers run at NORMAL priority.
            long elapsed = System.nanoTime() - startedNanos;
            long endedEpochMillis = System.currentTimeMillis();
            NavigationScheduler scheduler = NavigationScheduler.existingForServer(server);
            int tick = server.getTickCount();
            completed = new Snapshot(tick, elapsed, startedEpochMillis, endedEpochMillis,
                    scheduler == null ? Long.MIN_VALUE : scheduler.lastAdvancedTick(),
                    scheduler == null ? 0 : scheduler.metrics().elapsedNanos());
            startedNanos = 0;
            if (active.isEmpty()) return;
            List<String> concurrent = active.values().stream().map(window -> window.scenario).sorted().toList();
            boolean changed = false;
            var iterator = active.entrySet().iterator();
            while (iterator.hasNext()) {
                TickWindow window = iterator.next().getValue();
                window.sample(completed, concurrent, rawStartServerTick);
                if (window.endRequestedTick >= 0 && window.endRequestedTick <= tick) {
                    finished.add(window.report());
                    iterator.remove();
                    changed = true;
                }
            }
            if (changed) report();
        }

        void report() {
            if (names.isEmpty()) return;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 1);
            result.put("measurement", "forge-start-highest-through-end-lowest");
            result.put("measurementScope", "whole MinecraftServer, shared START HIGHEST through END LOWEST; each window includes all concurrent server work");
            result.put("windowPolicy", "all behavioral ticks including the final END; no warmup exclusion; first sample includes earlier work in its server tick");
            result.put("reportingOverhead", "sidecar formatting and I/O occur after the captured END endpoint");
            result.put("scopeExclusions", "geometry-only evidence and small standalone compatibility holders are not complex entity performance windows");
            result.put("p95LimitMillis", 50.0);
            result.put("p99Policy", "report actual P99; no additional P99 threshold in plan section 7.3.6");
            result.put("startedWindowCount", names.size());
            result.put("completedWindowCount", finished.size());
            result.put("unfinishedWindowCount", active.size());
            result.put("windows", List.copyOf(finished));
            result.put("unfinishedWindows", active.values().stream().map(TickWindow::report).toList());
            result.put("timingComplete", active.isEmpty() && finished.size() == names.size()
                    && finished.stream().allMatch(window -> Boolean.TRUE.equals(window.get("complete"))));
            ZombiesNavigationTestReport.write("complex-mspt", result);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { report(); }
            finally {
                active.clear(); finished.clear(); names.clear();
                try { MinecraftForge.EVENT_BUS.unregister(startListener); }
                finally { MinecraftForge.EVENT_BUS.unregister(endListener); }
            }
        }
    }

    /** Pure accumulator, separately tested for final-tick inclusion, gaps, bad coverage and overlapping windows. */
    static final class TickWindow {
        final String scenario, testName, declaredTestMethod;
        final boolean performanceGate;
        final long startRequestedTick;
        final List<Long> nanos = new ArrayList<>();
        final Set<String> concurrentScenarios = new LinkedHashSet<>();
        long endRequestedTick = -1, firstMeasuredTick = -1, lastMeasuredTick = -1;
        long startEpochMillis, endEpochMillis, uncoveredNanos;
        int coverageSamples, mismatchedSchedulerTicks, mismatchedStartTicks, gaps, maxConcurrency;
        Boolean functionalSuccess;

        TickWindow(String scenario, String testName, String declaredTestMethod, long startRequestedTick, boolean performanceGate) {
            this.scenario = scenario; this.testName = testName; this.declaredTestMethod = declaredTestMethod;
            this.startRequestedTick = startRequestedTick; this.performanceGate = performanceGate;
        }

        void finish(long tick, boolean success) {
            if (endRequestedTick < 0) endRequestedTick = tick;
            // An exception during cleanup in the same tick must not retain an earlier success mark.
            functionalSuccess = functionalSuccess == null ? success : functionalSuccess && success;
        }

        void sample(Snapshot sample, List<String> concurrent, int rawStartServerTick) {
            long tick = sample.serverTick();
            if (tick < startRequestedTick || tick <= lastMeasuredTick
                    || endRequestedTick >= 0 && tick > endRequestedTick) return;
            long expected = lastMeasuredTick < 0 ? startRequestedTick : lastMeasuredTick + 1;
            gaps += Math.toIntExact(Math.max(0, tick - expected));
            if (firstMeasuredTick < 0) {
                firstMeasuredTick = tick;
                startEpochMillis = sample.startEpochMillis();
            }
            lastMeasuredTick = tick;
            endEpochMillis = sample.endEpochMillis();
            nanos.add(sample.nanos());
            coverageSamples++;
            if (sample.schedulerTick() != tick) mismatchedSchedulerTicks++;
            // START observes N; tickCount++ precedes world/GameTest work, scheduling and END at N+1.
            if (rawStartServerTick + 1 != sample.serverTick()) mismatchedStartTicks++;
            uncoveredNanos = Math.max(uncoveredNanos, Math.max(0, sample.schedulerNanos() - sample.nanos()));
            maxConcurrency = Math.max(maxConcurrency, concurrent.size());
            concurrentScenarios.addAll(concurrent);
        }

        Map<String, Object> report() {
            List<Long> sorted = nanos.stream().sorted().toList();
            boolean complete = endRequestedTick >= startRequestedTick && firstMeasuredTick == startRequestedTick
                    && lastMeasuredTick == endRequestedTick && nanos.size() == endRequestedTick - startRequestedTick + 1
                    && gaps == 0 && mismatchedSchedulerTicks == 0 && mismatchedStartTicks == 0
                    && uncoveredNanos == 0 && !nanos.isEmpty() && sorted.get(0) > 0;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scenario", scenario);
            result.put("testName", testName);
            result.put("declaredTestMethod", declaredTestMethod);
            result.put("performanceGate", performanceGate);
            result.put("measurement", "forge-start-highest-through-end-lowest");
            result.put("serverWide", true);
            result.put("startRequestedServerTick", startRequestedTick);
            result.put("endRequestedServerTick", endRequestedTick < 0 ? null : endRequestedTick);
            result.put("firstMeasuredServerTick", firstMeasuredTick < 0 ? null : firstMeasuredTick);
            result.put("lastMeasuredServerTick", lastMeasuredTick < 0 ? null : lastMeasuredTick);
            result.put("measurementStartedEpochMillis", startEpochMillis == 0 ? null : startEpochMillis);
            result.put("measurementFinishedEpochMillis", endEpochMillis == 0 ? null : endEpochMillis);
            result.put("serverTickSamples", nanos.size());
            result.put("serverTickP50Millis", percentile(sorted, .50));
            result.put("serverTickP95Millis", percentile(sorted, .95));
            result.put("serverTickP99Millis", percentile(sorted, .99));
            result.put("serverTickMaxMillis", sorted.isEmpty() ? null : sorted.get(sorted.size()-1) / 1_000_000.0);
            result.put("schedulerCoverageSamples", coverageSamples);
            result.put("schedulerTickMismatchSamples", mismatchedSchedulerTicks);
            result.put("startTickMismatchSamples", mismatchedStartTicks);
            result.put("missingTickSamples", gaps);
            result.put("maxUncoveredSchedulerNanos", uncoveredNanos);
            result.put("maxConcurrentWindows", maxConcurrency);
            result.put("concurrentScenarios", concurrentScenarios.stream().sorted().toList());
            result.put("functionalSuccess", functionalSuccess);
            result.put("complete", complete);
            result.put("p95Within50Millis", complete ? percentile(sorted, .95) <= 50.0 : null);
            return result;
        }

        private static Double percentile(List<Long> sorted, double fraction) {
            return sorted.isEmpty() ? null : sorted.get(Math.max(0, (int)Math.ceil(sorted.size()*fraction)-1)) / 1_000_000.0;
        }
    }
}
