package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesInitialSpawnData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesTeamNames;
import com.cdp.codpattern.app.zombies.runtime.ZombiesRoomRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveSpawnGroupService;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierService;
import com.cdp.codpattern.app.zombies.service.ZombiesEconomyService;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig;
import com.cdp.codpattern.config.zombies.ZombiesServerConfig;
import com.mojang.authlib.GameProfile;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Exercises the actual map/service/team wiring without running a wave or teleporting a player. */
public final class ZombiesPlayerSpawnRuntimeGameTestSupport {
    private ZombiesPlayerSpawnRuntimeGameTestSupport() {}

    public static void run(ServerLevel level) throws Exception {
        initializeJdkRandomProviders();
        String mapName = "player-spawn-runtime-" + UUID.randomUUID();
        ZombiesMap map = new ZombiesMap(level, mapName,
                new AreaData(new BlockPos(-16, 0, -16), new BlockPos(16, 128, 16)));
        var team = map.getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS).orElseThrow();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "spawn-runtime"));
        try {
            var rules = ZombiesBarrierGroupsConfig.parse("""
                    {"schemaVersion":2,"groups":{"10":{
                      "playerSpawnGroupChanges":{"enable":[2],"disable":[0]},
                      "entries":{"1":{"cost":100}}
                    }}}
                    """, Path.of("player-spawn-runtime/barrier_groups.json"));
            check(rules.errors().isEmpty(), "runtime fixture rules must parse");
            var config = map.serverConfig();
            field(map, "serverConfig").set(map, new ZombiesServerConfig(mapName,
                    config.getRoom(), config.getWeaponRules(), config.getWeaponWall(), config.getMysteryBox(),
                    config.getWeaponFilter(), rules, List.of()));
            BlockPos initial = new BlockPos(1, 64, 1);
            BlockPos unlocked = new BlockPos(9, 64, 1);
            var initialSpawn = new ZombiesInitialSpawnData(level.dimension(), initial, 0, 0, 0);
            var unlockedSpawn = new ZombiesInitialSpawnData(level.dimension(), unlocked, 90, 0, 2);
            var barrier = new ZombiesBarrierData("runtime-door", "", 10, 0, true, level.dimension(),
                    new BlockPos(5, 64, 1), new BlockPos(5, 65, 1), new BlockPos(4, 64, 1),
                    "", ZombiesSpawnGroupChanges.NONE, 1);
            var objects = objects(List.of(initialSpawn, unlockedSpawn), barrier, level);
            map.applyObjects(objects);
            checkPositions(points(map, "initialSpawnPoints"), List.of(initial), "startup uses only group zero");
            checkPositions(points(map, "runtimeInitialSpawnPoints"), List.of(initial), "initial runtime group zero");
            checkPositions(team.getSpawnPointsData(SpawnPointKind.INITIAL), List.of(initial), "team initially excludes locked points");

            team.join(player);
            map.isStart = true;
            var playerGroups = (ZombiesActiveSpawnGroupService) field(map, "activePlayerSpawnGroupService").get(map);
            var zombieGroups = (ZombiesActiveSpawnGroupService) field(map, "activeSpawnGroupService").get(map);
            playerGroups.activate(2);
            zombieGroups.activate(77);
            invoke(map, "freezeObjectsForRuntime", new Class<?>[]{int.class}, 3);
            check(playerGroups.snapshot().equals(Set.of(0)) && zombieGroups.snapshot().equals(Set.of(0)), "freeze resets both group states");
            checkPositions(team.getSpawnPointsData(SpawnPointKind.INITIAL), List.of(initial), "freeze refreshes team points");
            check(team.getPlayerData(player.getUUID()).orElseThrow().getSpawnPointsData().getPosition().equals(initial), "freeze refreshes cached player assignment");

            ((ZombiesRoomRuntimeState) field(map, "runtimeState").get(map)).transitionTo(ZombiesGamePhase.INTERMISSION);
            var economy = (ZombiesEconomyService) field(map, "economyService").get(map);
            economy.addPoints(player.getUUID(), 200);
            Object interactions = field(map, "objectInteractionService").get(map);
            var service = (ZombiesBarrierService) field(interactions, "barrierService").get(interactions);
            @SuppressWarnings("unchecked")
            var runtimeBarriers = (List<ZombiesBarrierData>) invoke(map, "runtimeBarriers", new Class<?>[0]);
            var purchase = service.purchase(player, runtimeBarriers.get(0));
            check(purchase.success(), "real map barrier purchase failed: " + purchase.code());
            check(playerGroups.snapshot().equals(Set.of(2)) && zombieGroups.snapshot().equals(Set.of(0)), "purchase changes only configured player groups");
            checkPositions(points(map, "initialSpawnPoints"), List.of(initial), "startup helper remains group zero after unlocking");
            checkPositions(points(map, "runtimeInitialSpawnPoints"), List.of(unlocked), "respawn helper follows purchased player groups");
            checkPositions(team.getSpawnPointsData(SpawnPointKind.INITIAL), List.of(unlocked), "purchase listener refreshes team points");
            check(team.getPlayerData(player.getUUID()).orElseThrow().getSpawnPointsData().getPosition().equals(unlocked), "purchase removes stale cached player assignment");

            BlockPos editedInitial = new BlockPos(2, 64, 2);
            map.applyObjects(objects(List.of(new ZombiesInitialSpawnData(level.dimension(), editedInitial, 0, 0, 0), unlockedSpawn), barrier, level));
            checkPositions(points(map, "runtimeInitialSpawnPoints"), List.of(unlocked), "editing does not replace frozen runtime points");
            checkPositions(team.getSpawnPointsData(SpawnPointKind.INITIAL), List.of(unlocked), "editing does not replace frozen team cache");
            zombieGroups.activate(77);
            invoke(map, "clearFrozenObjectsAndResetRuntime", new Class<?>[0]);
            check(playerGroups.snapshot().equals(Set.of(0)) && zombieGroups.snapshot().equals(Set.of(0)), "round cleanup resets both group states");
            checkPositions(points(map, "runtimeInitialSpawnPoints"), List.of(editedInitial), "cleanup restores current configured initial point");
            checkPositions(team.getSpawnPointsData(SpawnPointKind.INITIAL), List.of(editedInitial), "cleanup refreshes team points");
            check(team.getPlayerData(player.getUUID()).orElseThrow().getSpawnPointsData().getPosition().equals(editedInitial), "cleanup removes unlocked cached assignment");
            invoke(map, "freezeObjectsForRuntime", new Class<?>[]{int.class}, 3);
            checkPositions(points(map, "runtimeInitialSpawnPoints"), List.of(editedInitial), "next round starts with only group zero");
            System.out.println("PASS PLAYER_SPAWN_RUNTIME: map startup/respawn selection, real purchase listener, team cache, frozen edits and round reset");
        } finally {
            map.isStart = false;
            team.leave(player);
            invoke(map, "clearFrozenObjectsAndResetRuntime", new Class<?>[0]);
            map.getMapTeams().retireCreatedScoreboardTeams();
            player.getInventory().clearContent();
        }
    }

    private static void initializeJdkRandomProviders() {
        // JDK 17 caches ServiceLoader providers on first use. Forge's transforming
        // context loader cannot discover jdk.random, which belongs to the system loader.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
            RandomGenerator.getDefault();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static ZombiesMapObjects objects(List<ZombiesInitialSpawnData> players, ZombiesBarrierData barrier, ServerLevel level) {
        return new ZombiesMapObjects(players,
                List.of(new ZombiesZombieSpawnData("runtime-zombie", 0, 1, level.dimension(), new BlockPos(1, 64, 9), 0, 0)),
                List.of(barrier), List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<SpawnPointData> points(ZombiesMap map, String name) throws Exception {
        return (List<SpawnPointData>) invoke(map, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void checkPositions(List<SpawnPointData> actual, List<BlockPos> expected, String message) {
        check(actual.stream().map(SpawnPointData::getPosition).toList().equals(expected), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
