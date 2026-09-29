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
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
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

/** Isolated after-change load measurements. Run only one of the three namespaces per server process. */
public final class ZombiesNavigationLoadGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final long SEED = 1701L;
    private static final int DEADLINE_TICKS = 950;
    private static final int WARMUP_TICKS = 20;

    private ZombiesNavigationLoadGameTests() {
    }

    @GameTestHolder("codpattern_navigation_load8")
    @PrefixGameTestTemplate(false)
    public static final class Load8 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load8", timeoutTicks = 1000)
        public static void eightZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 8);
        }
    }

    @GameTestHolder("codpattern_navigation_load32")
    @PrefixGameTestTemplate(false)
    public static final class Load32 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load32", timeoutTicks = 1000)
        public static void thirtyTwoZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 32);
        }
    }

    @GameTestHolder("codpattern_navigation_load64")
    @PrefixGameTestTemplate(false)
    public static final class Load64 {
        @GameTest(template = TEMPLATE, batch = "zombies_navigation_load64", timeoutTicks = 1000)
        public static void sixtyFourZombiesReachTheUpperFloorWithinTheRoomBudget(GameTestHelper helper) {
            run(helper, 64);
        }
    }

    private static void run(GameTestHelper helper, int count) {
        LoadRun run = new LoadRun(helper, count);
        try {
            run.spawn();
        } catch (RuntimeException | Error failure) {
            run.close();
            throw failure;
        }
        helper.onEachTick(() -> {
            try {
                run.observe();
            } catch (RuntimeException | Error failure) {
                run.close();
                throw failure;
            }
        });
    }

    private static final class LoadRun implements AutoCloseable {
        private final GameTestHelper helper;
        private final int requestedCount;
        private final RoomId roomId = RoomId.of(BuiltInGameModes.ZOMBIES, "navigation-load-" + UUID.randomUUID());
        private final ModeEntityOwnershipRegistry ownership = ModeEntityOwnershipRegistry.instance();
        private final ZombiesActiveMobCounter counter = new ZombiesActiveMobCounter();
        private final ZombiesWaveRuntimeState waveState = new ZombiesWaveRuntimeState();
        private final List<Mob> mobs = new ArrayList<>();
        private final Set<UUID> completed = new HashSet<>();
        private final List<Long> tickNanos = new ArrayList<>();
        private final ServerPlayer player;
        private final ZombiesMobSpawnService spawnService;
        private final ZombiesMobLifecycleService lifecycle;
        private long previousSearches;
        private long maxSearchesPerTick;
        private long firstArrivalTick = -1L;
        private long lastArrivalTick = -1L;
        private int lastSampledServerTick = -1;
        private boolean finished;

        private LoadRun(GameTestHelper helper, int requestedCount) {
            this.helper = helper;
            this.requestedCount = requestedCount;
            buildStairRoom(helper);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "navigation-load"));
            player.connection = new SilentPacketListener(player);
            player.setGameMode(GameType.SURVIVAL);
            Vec3 target = helper.absoluteVec(new Vec3(7.5D, 6.0D, 5.5D));
            player.moveTo(target.x, target.y, target.z, 0.0F, 0.0F);
            spawnService = new ZombiesMobSpawnService(ownership, () -> List.of(player),
                    ZombiesRulesConfig.SpawnPointWeighting::new, counter);
            lifecycle = new ZombiesMobLifecycleService(ownership, spawnService);
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
                ZombiesZombieSpawnData point = new ZombiesZombieSpawnData("load-spawn-" + i, 0, 1.0D,
                        helper.getLevel().dimension(), helper.absolutePos(positions.get(i)), 0.0F, 0.0F);
                ZombiesMapObjects objects = new ZombiesMapObjects(List.of(), List.of(point), List.of(),
                        List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of());
                ZombiesMobSpawnService.SpawnResult result = spawnService.spawnNext(roomId, helper.getLevel(),
                        objects, waveState, wave, Set.of(0));
                helper.assertTrue(result.spawned(), "load spawn " + i + " must succeed: " + result);
                Mob mob = result.entity().orElseThrow();
                mob.getRandom().setSeed(SEED + i);
                mobs.add(mob);
            }
            helper.assertTrue(waveState.activeZombies() == requestedCount && waveState.remainingBudget() == 0,
                    "the shared room wave must account for every load-test spawn");
            previousSearches = spawnService.navigationMetrics().searches();
        }

        private void observe() {
            if (finished) {
                return;
            }
            long tick = helper.getTick();
            collectCompletedServerTick(tick);
            ZombiesGroundNavigationService.SearchMetrics metrics = spawnService.navigationMetrics();
            long delta = metrics.searches() - previousSearches;
            previousSearches = metrics.searches();
            maxSearchesPerTick = Math.max(maxSearchesPerTick, delta);
            if (delta < 0L || delta > 2L) {
                finish(false, "extra recovery-search budget exceeded: " + delta + " searches in one room tick");
                return;
            }
            for (Mob mob : mobs) {
                if (completed.contains(mob.getUUID())) {
                    continue;
                }
                if (!mob.isAlive() || mob.isRemoved()) {
                    finish(false, "a load mob disappeared before reaching the upper player");
                    return;
                }
                double horizontal = Math.hypot(mob.getX() - player.getX(), mob.getZ() - player.getZ());
                if (mob.getY() >= player.getY() - 0.15D && horizontal < 4.0D) {
                    completed.add(mob.getUUID());
                    if (firstArrivalTick < 0L) {
                        firstArrivalTick = tick;
                    }
                    lastArrivalTick = tick;
                    // Remove successful mobs so this measures stair throughput, not a permanently full destination.
                    lifecycle.onCleanup(roomId, mob, waveState);
                    mob.discard();
                }
            }
            if (completed.size() == requestedCount) {
                finish(true, "all mobs reached the elevated player");
            } else if (tick >= DEADLINE_TICKS) {
                finish(false, "reachable stair workload exceeded the fixed " + DEADLINE_TICKS + " tick deadline");
            }
        }

        private void collectCompletedServerTick(long testTick) {
            if (testTick <= WARMUP_TICKS) {
                return;
            }
            MinecraftServer server = helper.getLevel().getServer();
            // GameTest callbacks run inside tickChildren; the current tick's duration is written afterwards.
            int completedTick = server.getTickCount() - 1;
            if (completedTick == lastSampledServerTick || completedTick < 0) {
                return;
            }
            lastSampledServerTick = completedTick;
            long elapsed = server.tickTimes[Math.floorMod(completedTick, server.tickTimes.length)];
            if (elapsed > 0L) {
                tickNanos.add(elapsed);
            }
        }

        private void finish(boolean success, String reason) {
            if (finished) {
                return;
            }
            finished = true;
            ZombiesGroundNavigationService.SearchMetrics metrics = spawnService.navigationMetrics();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("variant", "after");
            result.put("seed", SEED);
            result.put("mobType", "minecraft:zombie");
            result.put("spawned", requestedCount);
            result.put("completed", completed.size());
            result.put("completionPercent", completed.size() * 100.0D / requestedCount);
            result.put("elapsedTestTicks", helper.getTick());
            result.put("firstArrivalTick", firstArrivalTick);
            result.put("lastArrivalTick", lastArrivalTick);
            result.put("extraRecoverySearches", metrics.searches());
            result.put("extraRecoverySearchMillis", metrics.searchNanos() / 1_000_000.0D);
            result.put("recoveryStarts", metrics.recoveries());
            result.put("maxExtraRecoverySearchesPerRoomTick", maxSearchesPerTick);
            result.put("nativePathSearchesMeasured", false);
            result.put("discardSuccessfulArrivals", true);
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
            System.out.println("NAVIGATION_LOAD_RESULT " + new Gson().toJson(result));
            close();
            helper.assertTrue(!tickNanos.isEmpty(), "the workload must collect actual completed server-tick samples");
            helper.assertTrue(success, reason + "; completion=" + completed.size() + "/" + requestedCount);
            helper.succeed();
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
            spawnService.resetNavigationRuntime();
            player.discard();
        }
    }

    private static double percentileMillis(List<Long> samples, double percentile) {
        if (samples.isEmpty()) {
            return 0.0D;
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

    private static void buildStairRoom(GameTestHelper helper) {
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= 21; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 10, z), Blocks.STONE);
                if (x == 0 || x == 21 || z == 0 || z == 21) {
                    for (int y = 1; y < 10; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        for (int x = 3; x <= 13; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
            for (int y = 1; y < 5; y++) {
                helper.setBlock(new BlockPos(x, y, 7), Blocks.STONE);
            }
        }
        for (int step = 1; step <= 5; step++) {
            for (int x = 10; x <= 12; x++) {
                for (int y = 1; y <= step; y++) {
                    helper.setBlock(new BlockPos(x, y, 17 - step), Blocks.STONE);
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
