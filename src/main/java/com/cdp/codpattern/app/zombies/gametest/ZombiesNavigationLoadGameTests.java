package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.runtime.ZombiesWaveRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveMobCounter;
import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobLifecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobRecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Sustained load measurements; every successful arrival stays alive until the 6000-tick observation ends. */
public final class ZombiesNavigationLoadGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final long SEED = ZombiesNavigationTestReport.SEED;
    private static final int DEADLINE_TICKS = 950;
    private static final int OBSERVATION_TICKS = 6000;
    private static final int WARMUP_TICKS = 20;
    private static final String TARGET_MOTION = targetMotionPolicy();

    private static String targetMotionPolicy() {
        String policy = System.getProperty("codpattern.zombies.navigationLoadTargetMotion", "after-deadline");
        if (!policy.equals("after-deadline") && !policy.equals("after-first-arrival")) {
            throw new IllegalArgumentException("Unknown navigation load target motion: " + policy);
        }
        return policy;
    }

    /** The first sample is exactly the original player position; the original ellipse is reached after 20 ticks. */
    static Vec3 referenceTargetPosition(long elapsed) {
        if (elapsed < 0) {
            throw new IllegalArgumentException("Motion elapsed ticks cannot be negative");
        }
        double phase = elapsed * 0.012D;
        double initialOffset = -0.5D * Math.max(0.0D, 1.0D - elapsed / 20.0D);
        return new Vec3(8.0D + 2.0D * Math.sin(phase) + initialOffset,
                6.0D, 5.0D + Math.cos(phase) + initialOffset);
    }

    private ZombiesNavigationLoadGameTests() {
    }

    @GameTestHolder("codpattern_navigation_load8")
    @PrefixGameTestTemplate(false)
    public static final class Load8 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load8", timeoutTicks = OBSERVATION_TICKS + 100, setupTicks = 20)
        public static void eightZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 8);
        }
    }

    @GameTestHolder("codpattern_navigation_load32")
    @PrefixGameTestTemplate(false)
    public static final class Load32 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load32", timeoutTicks = OBSERVATION_TICKS + 100, setupTicks = 20)
        public static void thirtyTwoZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 32);
        }
    }

    @GameTestHolder("codpattern_navigation_load64")
    @PrefixGameTestTemplate(false)
    public static final class Load64 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load64", timeoutTicks = OBSERVATION_TICKS + 100, setupTicks = 20)
        public static void sixtyFourZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 64);
        }
    }

    private static void run(GameTestHelper helper, int count) {
        runRooms(helper, count, 1);
    }

    @GameTestHolder("codpattern_navigation_multi")
    @PrefixGameTestTemplate(false)
    public static final class MultiRoom {
        @GameTest(template = "zombies_navigation_multi", batch = "zombies_navigation_multi", timeoutTicks = OBSERVATION_TICKS + 100, setupTicks = 20)
        public static void fourRoomsKeep128OriginalZombiesAliveUnderSustainedChase(GameTestHelper helper) {
            runRooms(helper, 32, 4);
        }
    }

    private static void runRooms(GameTestHelper helper, int count, int rooms) {
        ZombiesNavigationTestTiming.LoadClock timer = ZombiesNavigationTestTiming.loadClock(helper.getLevel().getServer());
        List<LoadRun> runs = new ArrayList<>();
        try {
            for (int room = 0; room < rooms; room++) {
                LoadRun run = new LoadRun(helper, count, room, rooms, timer);
                runs.add(run);
                run.spawn();
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(runs, timer, failure);
            throw failure;
        }
        helper.onEachTick(() -> {
            try {
                runs.forEach(LoadRun::observe);
                if (runs.stream().allMatch(run -> run.finished)) {
                    if (ZombiesNavigationTestReport.ENGINE.equals("layered")) {
                        NavigationScheduler scheduler = NavigationScheduler.existingForServer(helper.getLevel().getServer());
                        helper.assertTrue(scheduler == null || scheduler.roomCount() == 0 && scheduler.jobCount() == 0
                                        && scheduler.registeredRoomCount() == 0 && scheduler.timing().transientEntries() == 0,
                                "all isolated load rooms must release their global scheduler queues");
                    }
                    timer.close();
                    helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                for (LoadRun run : runs) {
                    if (!run.finished) {
                        try {
                            run.finish(false, "another room stopped the shared workload: " + failure.getMessage());
                        } catch (RuntimeException | Error siblingFailure) {
                            failure.addSuppressed(siblingFailure);
                        }
                    }
                }
                closeAfterFailure(runs, timer, failure);
                throw failure;
            }
        });
    }

    private static void closeAfterFailure(List<LoadRun> runs, ZombiesNavigationTestTiming.LoadClock timer, Throwable failure) {
        try {
            for (LoadRun run : runs) {
                try { run.close(); }
                catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
        } finally {
            try { timer.close(); }
            catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
        }
    }

    private static final class LoadRun implements AutoCloseable {
        private final GameTestHelper helper;
        private final int requestedCount;
        private final int roomIndex;
        private final int roomCount;
        private final ZombiesNavigationTestTiming.LoadClock timer;
        private final BlockPos origin;
        private final Map<UUID, Vec3> originalPositions = new LinkedHashMap<>();
        private final Map<UUID, Long> arrivalTicks = new LinkedHashMap<>();
        private final List<Long> coldTickNanos = new ArrayList<>();
        private final List<Long> initialPursuitTickNanos = new ArrayList<>();
        private final List<Long> sustainedPursuitTickNanos = new ArrayList<>();
        private final Map<UUID, WaitObservation> waiting = new LinkedHashMap<>();
        private final Map<UUID, PursuitObservation> pursuit = new LinkedHashMap<>();
        private final RoomId roomId = RoomId.of(BuiltInGameModes.ZOMBIES, "navigation-load-" + UUID.randomUUID());
        private final ModeEntityOwnershipRegistry ownership = ModeEntityOwnershipRegistry.instance();
        private final ZombiesActiveMobCounter counter = new ZombiesActiveMobCounter();
        private final ZombiesWaveRuntimeState waveState = new ZombiesWaveRuntimeState();
        private final List<Mob> mobs = new ArrayList<>();
        private final Set<UUID> completed = new HashSet<>();
        private final List<Long> tickNanos = new ArrayList<>();
        private final List<Long> vanillaTickNanos = new ArrayList<>();
        private final ServerPlayer player;
        private final ZombiesMobSpawnService spawnService;
        private final ZombiesMobLifecycleService lifecycle;
        private final ZombiesMobRecycleService recycler;
        private final Map<UUID, Map<String, Object>> recycleEvents = new LinkedHashMap<>();
        private int recyclerCalls;
        private int requeued;
        private int discarded;
        private ZombiesGroundNavigationService.SearchMetrics initialMetrics;
        private ZombiesGroundNavigationService.RuntimeMetrics initialRuntime;
        private ZombiesGroundNavigationService.SearchMetrics sustainedStartMetrics;
        private ZombiesGroundNavigationService.RuntimeMetrics sustainedStartRuntime;
        private long previousSearches;
        private long maxSearchesPerTick;
        private long previousGeometryChecks;
        private long maxGeometryChecksPerTick;
        private long previousExpansions;
        private long maxRoomExpansionsPerTick;
        private int maxServerExpansions;
        private int maxServerGeometry;
        private final List<Map<String, Object>> runtimeSamples = new ArrayList<>();
        private long firstArrivalTick = -1L;
        private long lastArrivalTick = -1L;
        private long motionStartedTick = -1L;
        private long playerMotionSamples;
        private long playerMotionSamplesBeforeDeadline;
        private double maxPlayerStep;
        private Double motionFirstStep;
        private boolean playerMotionWithinPlatform = true;
        private int lastSampledServerTick = -1;
        private long measurementStartedEpochMillis;
        private long measurementFinishedEpochMillis;
        private long lastMeasuredSchedulerTick = Long.MIN_VALUE;
        private long maxUncoveredSchedulerNanos;
        private int schedulerCoverageSamples;
        private boolean finished;

        private LoadRun(GameTestHelper helper, int requestedCount, int roomIndex, int roomCount, ZombiesNavigationTestTiming.LoadClock timer) {
            this.helper = helper;
            this.requestedCount = requestedCount;
            this.roomIndex = roomIndex;
            this.roomCount = roomCount;
            this.timer = timer;
            this.origin = new BlockPos(roomIndex * 24, 0, 0);
            buildStairRoom(helper, origin);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "navigation-load"));
            player.connection = new SilentPacketListener(player);
            player.setGameMode(GameType.SURVIVAL);
            player.invulnerableTime = 20;
            Vec3 target = helper.absoluteVec(new Vec3(origin.getX() + 7.5D, 6.0D, 5.5D));
            player.moveTo(target.x, target.y, target.z, 0.0F, 0.0F);
            spawnService = new ZombiesMobSpawnService(ownership, () -> List.of(player),
                    ZombiesRulesConfig.SpawnPointWeighting::new, counter);
            spawnService.configureNavigationContext(roomId, helper.getLevel(), new net.minecraft.world.phys.AABB(
                    helper.absolutePos(origin), helper.absolutePos(origin.offset(24, 12, 24))));
            lifecycle = new ZombiesMobLifecycleService(ownership, spawnService);
            recycler = new ZombiesMobRecycleService(ownership, lifecycle, () -> List.of(player));
        }

        private void spawn() {
            ZombiesWaveDefinition wave = new Gson().fromJson(
                    "{\"wave\":1,\"maxAlive\":" + requestedCount
                            + ",\"mobs\":[{\"entity\":\"minecraft:zombie\",\"count\":"
                            + requestedCount + "}]}", ZombiesWaveDefinition.class);
            wave.attachSource(null, 1, true);
            wave.applyDefaults(new ZombiesRulesConfig.Defaults());
            waveState.beginTargetWave(wave);
            List<BlockPos> positions = spawnPositions();
            for (int i = 0; i < requestedCount; i++) {
                BlockPos actualSpawn = helper.absolutePos(positions.get(i).offset(origin));
                helper.assertTrue(helper.getLevel().isPositionEntityTicking(actualSpawn)
                                && helper.getLevel().areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(actualSpawn)),
                        "load fixture must wait for actual entity-ticking and loaded entity sections before spawning: " + actualSpawn);
                ZombiesZombieSpawnData point = new ZombiesZombieSpawnData("load-spawn-" + i, 0, 1.0D,
                        helper.getLevel().dimension(), actualSpawn, 0.0F, 0.0F);
                ZombiesMapObjects objects = new ZombiesMapObjects(List.of(), List.of(point), List.of(),
                        List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of());
                ZombiesMobSpawnService.SpawnResult result = spawnService.spawnNext(roomId, helper.getLevel(),
                        objects, waveState, wave, Set.of(0));
                helper.assertTrue(result.spawned(), "load spawn " + i + " must succeed: " + result);
                Mob mob = result.entity().orElseThrow();
                mob.getRandom().setSeed(SEED + i);
                mobs.add(mob);
                originalPositions.put(mob.getUUID(), mob.position());
                waiting.put(mob.getUUID(), new WaitObservation());
            }
            helper.assertTrue(waveState.activeZombies() == requestedCount && waveState.remainingBudget() == 0,
                    "the shared room wave must account for every load-test spawn");
            previousSearches = spawnService.navigationMetrics().searches();
            previousGeometryChecks = spawnService.navigationMetrics().geometryChecks();
            initialMetrics = spawnService.navigationMetrics();
            initialRuntime = spawnService.navigationRuntimeMetrics();
            previousExpansions = initialRuntime.planning() == null ? 0 : initialRuntime.planning().expansions();
        }

        private void observe() {
            if (finished) {
                return;
            }
            long tick = helper.getTick();
            // Keep the stationary/moving test target alive while leaving native attacks eligible.
            player.setHealth(player.getMaxHealth());
            player.invulnerableTime = 20;
            collectCompletedServerTick(tick);
            Map<UUID, ZombiesGroundNavigationService.ProgressSnapshot> beforeRecycle = new LinkedHashMap<>();
            for (Mob mob : mobs) {
                if (tick % 80 == 0 && !mob.isRemoved()) {
                    beforeRecycle.put(mob.getUUID(), ZombiesGroundNavigationService.getProgress(mob));
                }
            }
            ZombiesMobRecycleService.RecycleSummary recycle = recycler.tick(roomId, helper.getLevel(), waveState, tick);
            recyclerCalls++;
            requeued += recycle.requeued();
            discarded += recycle.discarded();
            for (Mob mob : mobs) {
                if (beforeRecycle.containsKey(mob.getUUID()) && mob.isRemoved()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("tick", tick);
                    event.put("action", "RECYCLED_RETRY");
                    event.put("navigationBeforeRecycle", beforeRecycle.get(mob.getUUID()));
                    event.put("scanRequeued", recycle.requeued());
                    event.put("scanDiscarded", recycle.discarded());
                    event.put("remainingWaveBudgetAfterScan", waveState.remainingBudget());
                    recycleEvents.put(mob.getUUID(), event);
                }
            }
            if (requeued != 0 || discarded != 0 || !recycleEvents.isEmpty()) {
                finish(false, "the real recycler removed an original reachable load mob; requeued="
                        + requeued + ", discarded=" + discarded + ", entities=" + recycleEvents.keySet());
                return;
            }
            ZombiesGroundNavigationService.SearchMetrics metrics = spawnService.navigationMetrics();
            ZombiesGroundNavigationService.RuntimeMetrics runtime = spawnService.navigationRuntimeMetrics();
            if (tick == DEADLINE_TICKS) {
                sustainedStartMetrics = metrics;
                sustainedStartRuntime = runtime;
            }
            boolean layered = runtime.serverTick() != null;
            if (layered) {
                long expansionDelta = runtime.planning().expansions() - previousExpansions;
                previousExpansions = runtime.planning().expansions();
                maxRoomExpansionsPerTick = Math.max(maxRoomExpansionsPerTick, expansionDelta);
                if (expansionDelta < 0 || expansionDelta > 1024) {
                    finish(false, "layered room expansion budget exceeded: " + expansionDelta);
                    return;
                }
                maxServerExpansions = Math.max(maxServerExpansions, runtime.serverTick().expansions());
                maxServerGeometry = Math.max(maxServerGeometry, runtime.serverTick().geometry());
                if (runtime.serverTick().expansions() > 4096 || runtime.serverTick().geometry() > 1024) {
                    finish(false, "layered global work budget exceeded: " + runtime.serverTick());
                    return;
                }
                if (tick > 0 && tick % 100 == 0) {
                    runtimeSamples.add(Map.of("tick", tick, "planning", runtime.planning(),
                            "cache", runtime.cache(), "serverTick", runtime.serverTick(),
                            "globalSchedulerTiming", runtime.schedulerTiming()));
                }
            }
            long delta = metrics.searches() - previousSearches;
            previousSearches = metrics.searches();
            maxSearchesPerTick = Math.max(maxSearchesPerTick, delta);
            if (delta < 0L || (!layered && delta > 2L)) {
                finish(false, "extra recovery-search budget exceeded: " + delta + " searches in one room tick");
                return;
            }
            long segmentDelta = metrics.geometryChecks() - previousGeometryChecks;
            previousGeometryChecks = metrics.geometryChecks();
            maxGeometryChecksPerTick = Math.max(maxGeometryChecksPerTick, segmentDelta);
            if (segmentDelta < 0L || segmentDelta > (layered ? 256L : 32L)) {
                finish(false, "geometry-check budget exceeded: " + segmentDelta + " segments in one room tick");
                return;
            }
            for (Mob mob : mobs) {
                waiting.get(mob.getUUID()).observe(ZombiesGroundNavigationService.getProgress(mob), layered);
                if (!mob.isAlive() || mob.isRemoved() || helper.getLevel().getEntity(mob.getUUID()) != mob) {
                    finish(false, "a load mob disappeared before reaching the upper player");
                    return;
                }
                if (tick > 0 && tick % 100 == 0) {
                    pursuit.computeIfAbsent(mob.getUUID(), ignored -> new PursuitObservation()).observe(mob, player);
                }
                if (completed.contains(mob.getUUID())) {
                    continue;
                }
                double horizontal = Math.hypot(mob.getX() - player.getX(), mob.getZ() - player.getZ());
                if (mob.onGround() && Math.abs(mob.getY() - player.getY()) < 0.15D && horizontal < 4.0D
                        && mob.getTarget() == player && mob.getSensing().hasLineOfSight(player)) {
                    completed.add(mob.getUUID());
                    arrivalTicks.put(mob.getUUID(), tick);
                    if (firstArrivalTick < 0L) {
                        firstArrivalTick = tick;
                    }
                    lastArrivalTick = tick;
                    // Keep the same mob present: later arrivals must handle real congestion.
                }
            }
            if (waveState.activeZombies() != requestedCount || counter.roomCount(roomId) != requestedCount
                    || ownership.entitiesInRoom(roomId).size() != requestedCount || waveState.remainingBudget() != 0) {
                finish(false, "active count, ownership, or wave budget diverged during sustained pursuit");
                return;
            }
            if (tick >= DEADLINE_TICKS && completed.size() != requestedCount) {
                finish(false, "reachable stair workload exceeded the fixed " + DEADLINE_TICKS + " tick deadline");
                return;
            }
            if (tick >= OBSERVATION_TICKS) {
                finish(true, "all original mobs arrived and remained alive through 6000 ticks of pursuit");
            } else if (TARGET_MOTION.equals("after-first-arrival") && firstArrivalTick >= 0) {
                if (motionStartedTick < 0) {
                    motionStartedTick = tick;
                }
                Vec3 relative = referenceTargetPosition(tick - motionStartedTick)
                        .add(origin.getX(), origin.getY(), origin.getZ());
                Vec3 target = helper.absoluteVec(relative);
                double step = target.distanceTo(player.position());
                recordPlayerMotion(step, tick);
                double halfWidth = player.getBbWidth() / 2.0D;
                playerMotionWithinPlatform &= relative.x - halfWidth >= origin.getX() + 3.0D
                        && relative.x + halfWidth <= origin.getX() + 14.0D
                        && relative.z - halfWidth >= origin.getZ() + 3.0D
                        && relative.z + halfWidth <= origin.getZ() + 12.0D
                        && relative.y == origin.getY() + 6.0D;
                helper.assertTrue(Double.isFinite(step) && step <= 0.06D,
                        "reference player motion must remain continuous and at most 0.06 blocks per tick: " + step);
                helper.assertTrue(playerMotionSamples != 1 || step == 0.0D,
                        "reference player motion must start at the exact existing target position");
                helper.assertTrue(playerMotionWithinPlatform,
                        "reference player body must remain above the existing upper platform");
                player.moveTo(target.x, target.y, target.z, 0.0F, 0.0F);
            } else if (tick > DEADLINE_TICKS) {
                // Only the target moves, within the existing upper platform, at a bounded walking pace.
                double phase = (tick - DEADLINE_TICKS) * 0.012D;
                Vec3 target = helper.absoluteVec(new Vec3(origin.getX() + 8.0D + 2.0D * Math.sin(phase),
                        6.0D, 5.0D + Math.cos(phase)));
                if (motionStartedTick < 0) {
                    motionStartedTick = tick;
                }
                recordPlayerMotion(target.distanceTo(player.position()), tick);
                player.moveTo(target.x, target.y, target.z, 0.0F, 0.0F);
            }
        }

        private void recordPlayerMotion(double step, long tick) {
            if (playerMotionSamples == 0) {
                motionFirstStep = step;
            }
            playerMotionSamples++;
            if (tick <= DEADLINE_TICKS) {
                playerMotionSamplesBeforeDeadline++;
            }
            maxPlayerStep = Math.max(maxPlayerStep, step);
        }

        private void collectCompletedServerTick(long testTick) {
            MinecraftServer server = helper.getLevel().getServer();
            // This callback is inside the current tick: consume only the prior completed START..END span.
            ZombiesNavigationTestTiming.Snapshot completed = timer.snapshot();
            int completedTick = completed.serverTick();
            if (completedTick == lastSampledServerTick || completedTick < 0) {
                return;
            }
            lastSampledServerTick = completedTick;
            long elapsed = completed.nanos();
            lastMeasuredSchedulerTick = completed.schedulerTick();
            helper.assertTrue(lastMeasuredSchedulerTick == completedTick,
                    "inclusive measurement and scheduler must cover the same completed server tick; timer="
                            + completedTick + ", scheduler=" + lastMeasuredSchedulerTick);
            maxUncoveredSchedulerNanos = Math.max(maxUncoveredSchedulerNanos,
                    Math.max(0, completed.schedulerNanos() - elapsed));
            schedulerCoverageSamples++;
            helper.assertTrue(maxUncoveredSchedulerNanos == 0,
                    "the inclusive server tick must contain all END navigation scheduling; uncovered nanos="
                            + maxUncoveredSchedulerNanos);
            if (elapsed > 0L) {
                if (testTick > WARMUP_TICKS && measurementStartedEpochMillis == 0) {
                    measurementStartedEpochMillis = completed.startEpochMillis();
                }
                (testTick <= WARMUP_TICKS ? coldTickNanos : tickNanos).add(elapsed);
                if (testTick > WARMUP_TICKS) {
                    measurementFinishedEpochMillis = completed.endEpochMillis();
                    vanillaTickNanos.add(server.tickTimes[Math.floorMod(completedTick, server.tickTimes.length)]);
                    (testTick <= DEADLINE_TICKS ? initialPursuitTickNanos : sustainedPursuitTickNanos).add(elapsed);
                }
            }
        }

        private void finish(boolean success, String reason) {
            if (finished) {
                return;
            }
            finished = true;
            ZombiesGroundNavigationService.SearchMetrics metrics = spawnService.navigationMetrics();
            ZombiesGroundNavigationService.RuntimeMetrics runtime = spawnService.navigationRuntimeMetrics();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("engine", ZombiesNavigationTestReport.ENGINE);
            result.put("rooms", roomCount);
            result.put("roomIndex", roomIndex);
            result.put("roomId", roomId.toString());
            result.put("seed", SEED);
            result.put("mobType", "minecraft:zombie");
            result.put("spawned", requestedCount);
            result.put("completed", completed.size());
            result.put("completionPercent", completed.size() * 100.0D / requestedCount);
            result.put("elapsedTestTicks", helper.getTick());
            result.put("measurementStartedEpochMillis", measurementStartedEpochMillis == 0 ? null : measurementStartedEpochMillis);
            result.put("measurementFinishedEpochMillis", measurementFinishedEpochMillis == 0 ? null : measurementFinishedEpochMillis);
            result.put("serverTickMeasurement", "forge-start-highest-through-end-lowest");
            result.put("lastMeasuredCompletedServerTick", lastSampledServerTick);
            result.put("lastMeasuredSchedulerTick", lastMeasuredSchedulerTick);
            result.put("schedulerCoverageSamples", schedulerCoverageSamples);
            result.put("maxUncoveredSchedulerNanos", maxUncoveredSchedulerNanos);
            result.put("serverTickMeasurementScope", "One test-only timer shared by every observer on this server, registered by the test environment at ServerStarted and removed at ServerStopped. Load success/failure closes only its view. HIGHEST ServerTick.START through LOWEST ServerTick.END includes native/world work and the NORMAL END navigation scheduler. The next GameTest callback consumes the completed prior tick and its same-END scheduler snapshot; the view excludes its installation tick as before, and no sleep between ticks is counted.");
            Map<String, Object> vanillaTiming = new LinkedHashMap<>();
            vanillaTiming.put("scope", "vanilla tickTimes excludes Forge ServerTick.END; diagnostic only, never the performance gate");
            vanillaTiming.put("samples", vanillaTickNanos.size());
            vanillaTiming.put("p95Millis", percentileMillis(vanillaTickNanos, .95));
            vanillaTiming.put("p99Millis", percentileMillis(vanillaTickNanos, .99));
            vanillaTiming.put("maxMillis", percentileMillis(vanillaTickNanos, 1));
            result.put("vanillaTickTimesDiagnostics", vanillaTiming);
            result.put("firstArrivalTick", firstArrivalTick);
            result.put("lastArrivalTick", lastArrivalTick);
            result.put("targetMotionPolicy", TARGET_MOTION);
            result.put("motionStartedTick", motionStartedTick);
            result.put("motionPhaseOriginTick", TARGET_MOTION.equals("after-first-arrival")
                    ? motionStartedTick : DEADLINE_TICKS);
            result.put("maxPlayerStep", maxPlayerStep);
            result.put("motionFirstStep", motionFirstStep);
            result.put("playerMotionSamples", playerMotionSamples);
            result.put("playerMotionSamplesBeforeDeadline", playerMotionSamplesBeforeDeadline);
            result.put("playerMotionWithinPlatform", TARGET_MOTION.equals("after-first-arrival")
                    ? playerMotionWithinPlatform : null);
            result.put("targetMotionInputComparison", TARGET_MOTION.equals("after-first-arrival")
                    ? "same-event-relative-policy-not-identical-absolute-tick-input" : "same-fixed-tick-input");
            result.put("targetMotionDefinition", "Upper-platform ellipse: room-relative center (8,6,5), X/Z amplitudes (2,1), angular step 0.012 radians per tick. after-first-arrival starts on its first observed arrival frame at the original (7.5,6,5.5), linearly removing the (-0.5,0,-0.5) offset over 20 ticks; each room has its own observed start. after-deadline retains the original tick > 950 schedule and trajectory.");
            if (runtime.serverTick() == null) {
                result.put("extraRecoverySearches", metrics.searches());
                result.put("extraRecoverySearchMillis", metrics.searchNanos() / 1_000_000.0D);
                result.put("recoveryStarts", metrics.recoveries());
                result.put("maxExtraRecoverySearchesPerRoomTick", maxSearchesPerTick);
                result.put("geometryChecks", metrics.geometryChecks());
                result.put("localRoutes", metrics.localRoutes());
                result.put("maxGeometryChecksPerRoomTick", maxGeometryChecksPerTick);
            } else {
                result.put("planning", runtime.planning());
                result.put("graphCache", runtime.cache());
                result.put("maxGlobalExpansionsPerTick", maxServerExpansions);
                result.put("maxGlobalGeometryPerTick", maxServerGeometry);
                result.put("maxRoomExpansionsPerTick", maxRoomExpansionsPerTick);
                result.put("maxRoomGeometryPerTick", maxGeometryChecksPerTick);
                result.put("globalSchedulerTiming", runtime.schedulerTiming());
                result.put("runtimeSamplesEvery100Ticks", runtimeSamples);
            }
            result.put("nativePathSearchesMeasured", false);
            result.put("realRecyclerInstalled", true);
            result.put("recyclerCalls", recyclerCalls);
            result.put("requeued", requeued);
            result.put("discarded", discarded);
            result.put("recycleEvents", recycleEvents);
            result.put("discardSuccessfulArrivals", false);
            result.put("sustainedObservationTicks", OBSERVATION_TICKS);
            result.put("firstArrivalDeadlineTicks", DEADLINE_TICKS);
            result.put("firstArrivalEvidence", "Original living entity, on ground, same feet height within 0.15, horizontal distance under 4, exact eligible player target, and line of sight, all observed together before tick 950.");
            result.put("pursuitObservation", "Every 100 ticks records exact player target and line of sight for each original entity. These samples do not assert uninterrupted target ownership between samples.");
            result.put("startupServerTickSamples", coldTickNanos.size());
            result.put("startupServerTickP95Millis", percentileMillis(coldTickNanos, 0.95D));
            result.put("separatePrewarmedCacheComparisonMeasured", false);
            result.put("cachePhaseDefinitions", "coldInitialPursuit: the fixed initial arrival-deadline window, MSPT samples ticks 21..950; work counters include spawn through tick 950 so cold-start work in ticks 0..20 remains visible. Under after-first-arrival this window includes moving pursuit after the first arrival; it is not a wholly stationary phase. retainedCacheSustainedPursuit: the fixed post-deadline window, MSPT/work ticks 951..6000 with the same living entities and moving target. These are different workload phases, not an independent prewarmed replay; window boundaries do not follow the engine-dependent motion start.");
            result.put("coldInitialPursuit", phaseSummary(initialPursuitTickNanos, initialMetrics,
                    sustainedStartMetrics == null ? metrics : sustainedStartMetrics, initialRuntime,
                    sustainedStartRuntime == null ? runtime : sustainedStartRuntime));
            result.put("retainedCacheSustainedPursuit", sustainedStartRuntime == null || sustainedPursuitTickNanos.isEmpty() ? null
                    : phaseSummary(sustainedPursuitTickNanos, sustainedStartMetrics, metrics,
                            sustainedStartRuntime, runtime));
            result.put("entityWaitObservation", "per-tick planning/validation states with an active waiting clock; includes computation and chunk/resource waits, not isolated scheduler queue latency");
            result.put("schedulerQueueLatencyMeasured", false);
            result.put("nativePathSearchMillis", "unmeasured");
            result.put("entities", mobs.stream().map(mob -> {
                Map<String, Object> entity = new LinkedHashMap<>();
                entity.put("uuid", mob.getUUID().toString());
                entity.put("targetUuid", player.getUUID().toString());
                entity.put("start", originalPositions.get(mob.getUUID()).toString());
                entity.put("end", mob.position().toString());
                entity.put("arrivalTick", arrivalTicks.get(mob.getUUID()));
                entity.put("alive", mob.isAlive() && !mob.isRemoved());
                entity.put("sameEntity", helper.getLevel().getEntity(mob.getUUID()) == mob);
                entity.put("navigationWait", runtime.serverTick() == null ? null : waiting.get(mob.getUUID()).report());
                entity.put("pursuitSamples", pursuit.containsKey(mob.getUUID()) ? pursuit.get(mob.getUUID()).report() : null);
                entity.put("recycleAction", recycleEvents.containsKey(mob.getUUID()) ? "RECYCLED_RETRY" : "none observed");
                entity.put("recycleEvent", recycleEvents.get(mob.getUUID()));
                return entity;
            }).toList());
            result.put("warmupTicksExcluded", WARMUP_TICKS);
            result.put("serverTickSamples", tickNanos.size());
            result.put("serverTickP50Millis", percentileMillis(tickNanos, 0.50D));
            result.put("serverTickP95Millis", percentileMillis(tickNanos, 0.95D));
            result.put("serverTickP99Millis", percentileMillis(tickNanos, 0.99D));
            result.put("serverTickMaxMillis", percentileMillis(tickNanos, 1.0D));
            result.put("success", success);
            result.put("reason", reason);
            if (!success) {
                result.put("remainingMobs", mobs.stream().filter(mob -> !completed.contains(mob.getUUID()))
                        .map(mob -> mob.position().toString()).toList());
            }
            LayeredNavigationRuntime ownedRuntime = mobs.isEmpty() ? null : LayeredNavigationRuntime.of(mobs.get(0));
            close();
            ZombiesGroundNavigationService.RuntimeMetrics afterClose = spawnService.navigationRuntimeMetrics();
            boolean cleaned = afterClose.controllers() == 0 && (afterClose.planning() == null
                    || afterClose.planning().activeRequests() == 0);
            if (ownedRuntime != null) {
                // Retain a reference to inspect the closed object itself, not merely a cleared service field.
                cleaned &= ownedRuntime.controllerCount() == 0 && ownedRuntime.planningStats().activeRequests() == 0
                        && ownedRuntime.planningStats().searchRecords() == 0 && ownedRuntime.cacheStats().nodes() == 0
                        && ownedRuntime.cacheStats().edges() == 0 && ownedRuntime.cacheStats().pinnedTiles() == 0
                        && ownedRuntime.cacheStats().geometryCells() == 0 && ownedRuntime.cacheStats().portalPaths() == 0
                        && ownedRuntime.cacheStats().failures() == 0 && ownedRuntime.cacheStats().transientEntries() == 0;
                result.put("cacheAfterClose", ownedRuntime.cacheStats());
                result.put("planningAfterClose", ownedRuntime.planningStats());
            }
            result.put("navigationReferencesReleased", cleaned);
            result.put("success", success && cleaned && !tickNanos.isEmpty());
            ZombiesNavigationTestReport.write("load-" + requestedCount + "-room-" + roomIndex, result);
            helper.assertTrue(cleaned,
                    "room cleanup must release navigation controllers and active planning requests");
            helper.assertTrue(!tickNanos.isEmpty(), "the workload must collect actual completed server-tick samples");
            helper.assertTrue(success, reason + "; completion=" + completed.size() + "/" + requestedCount);
        }

        @Override
        public void close() {
            for (Mob mob : mobs) {
                if (!mob.isRemoved()) {
                    lifecycle.onCleanup(roomId, mob, waveState);
                    mob.discard();
                }
            }
            ownership.clearRoom(roomId);
            counter.clearRoom(roomId);
            recycler.reset();
            spawnService.resetNavigationRuntime();
            player.discard();
        }
    }

    private static final class PursuitObservation {
        private int samples, exactTargetSamples, lineOfSightSamples, exactTargetWithLineOfSightSamples;

        void observe(Mob mob, ServerPlayer player) {
            samples++;
            boolean exactTarget = mob.getTarget() == player;
            boolean lineOfSight = mob.getSensing().hasLineOfSight(player);
            if (exactTarget) exactTargetSamples++;
            if (lineOfSight) lineOfSightSamples++;
            if (exactTarget && lineOfSight) exactTargetWithLineOfSightSamples++;
        }

        Map<String, Object> report() {
            return Map.of("samples", samples, "exactTargetSamples", exactTargetSamples,
                    "lineOfSightSamples", lineOfSightSamples,
                    "exactTargetWithLineOfSightSamples", exactTargetWithLineOfSightSamples);
        }
    }

    private static final class WaitObservation {
        private final Map<String, Long> ticksByReason = new LinkedHashMap<>();
        private long total, continuous, maximumContinuous;

        void observe(ZombiesGroundNavigationService.ProgressSnapshot progress, boolean layered) {
            boolean pending = layered && progress != null && progress.planningWaitUntilGameTime() != 0
                    && !progress.planningReason().equals("NONE");
            if (pending) {
                total++;
                maximumContinuous = Math.max(maximumContinuous, ++continuous);
                ticksByReason.merge(progress.planningReason(), 1L, Long::sum);
            } else {
                continuous = 0;
            }
        }

        Map<String, Object> report() {
            return Map.of("totalPendingOrValidationTicks", total,
                    "maximumContinuousPendingOrValidationTicks", maximumContinuous,
                    "ticksByReason", ticksByReason);
        }
    }

    private static Map<String, Object> phaseSummary(List<Long> samples,
            ZombiesGroundNavigationService.SearchMetrics before,
            ZombiesGroundNavigationService.SearchMetrics after,
            ZombiesGroundNavigationService.RuntimeMetrics runtimeBefore,
            ZombiesGroundNavigationService.RuntimeMetrics runtimeAfter) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("serverTickSamples", samples.size());
        phase.put("serverTickP50Millis", percentileMillis(samples, 0.50));
        phase.put("serverTickP95Millis", percentileMillis(samples, 0.95));
        phase.put("serverTickP99Millis", percentileMillis(samples, 0.99));
        phase.put("serverTickMaxMillis", percentileMillis(samples, 1.0));
        Map<String, Object> work = new LinkedHashMap<>();
        if (runtimeAfter.planning() != null) {
            var start = runtimeBefore.planning();
            var end = runtimeAfter.planning();
            work.put("requests", end.requests() - start.requests());
            work.put("expansions", end.expansions() - start.expansions());
            work.put("geometry", end.geometry() - start.geometry());
            work.put("planningMillis", (end.nanos() - start.nanos()) / 1_000_000.0);
            work.put("cacheHits", end.cacheHits() - start.cacheHits());
            work.put("chunkWaitObservations", end.chunkWaits() - start.chunkWaits());
            work.put("resourceWaitObservations", end.resourceWaits() - start.resourceWaits());
            phase.put("cacheAtStart", runtimeBefore.cache());
            phase.put("cacheAtEnd", runtimeAfter.cache());
        } else {
            work.put("extraRecoverySearches", after.searches() - before.searches());
            work.put("extraRecoverySearchMillis", (after.searchNanos() - before.searchNanos()) / 1_000_000.0);
            work.put("geometryChecks", after.geometryChecks() - before.geometryChecks());
            work.put("recoveries", after.recoveries() - before.recoveries());
        }
        phase.put("workCounterDeltas", work);
        return phase;
    }

    private static Double percentileMillis(List<Long> samples, double percentile) {
        if (samples.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1)) / 1_000_000.0D;
    }

    private static List<BlockPos> spawnPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = 3; x <= 13; x++) {
            for (int z = 8; z <= 11; z++) {
                positions.add(new BlockPos(x, 1, z));
            }
        }
        for (int x = 3; x <= 9; x++) {
            for (int z = 12; z <= 15; z++) {
                positions.add(new BlockPos(x, 1, z));
            }
        }
        Collections.shuffle(positions, new Random(SEED));
        return positions;
    }

    private static void buildStairRoom(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= 21; z++) {
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE);
                helper.setBlock(origin.offset(x, 10, z), Blocks.STONE);
                if (x == 0 || x == 21 || z == 0 || z == 21) {
                    for (int y = 1; y < 10; y++) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        for (int x = 3; x <= 13; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(origin.offset(x, 5, z), Blocks.STONE);
            }
            for (int y = 1; y < 5; y++) {
                helper.setBlock(origin.offset(x, y, 7), Blocks.STONE);
            }
        }
        for (int step = 1; step <= 5; step++) {
            for (int x = 10; x <= 12; x++) {
                for (int y = 1; y <= step; y++) {
                    helper.setBlock(origin.offset(x, y, 17 - step), Blocks.STONE);
                }
            }
        }
    }

    private static final class SilentPacketListener extends ServerGamePacketListenerImpl {
        private SilentPacketListener(ServerPlayer player) {
            super(player.getServer(), new Connection(PacketFlow.SERVERBOUND), player);
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
        }
    }
}
