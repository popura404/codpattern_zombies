package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.*;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.runtime.ZombiesWaveRuntimeState;
import com.cdp.codpattern.app.zombies.service.*;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesSpawnGroupGameTests {
    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 80, required = true)
    public static void purchaseAndDisabledSpawnBudget(GameTestHelper helper) {
        RoomId room = RoomId.of("zombies", "spawn-groups-" + UUID.randomUUID());
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var players = new ZombiesPlayerStateService();
        var economy = new ZombiesEconomyService(players);
        economy.addPoints(player.getUUID(), 1_000);
        var groups = new ZombiesActiveSpawnGroupService();
        var store = new ZombiesObjectStateStore();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        var spawns = List.of(
                new ZombiesZombieSpawnData("spawn-zero", 0, 1, helper.getLevel().dimension(), pos, 0, 0),
                new ZombiesZombieSpawnData("spawn-one", 1, 1, helper.getLevel().dimension(), pos, 0, 0),
                new ZombiesZombieSpawnData("spawn-two-disabled", 2, 0, helper.getLevel().dimension(), pos, 0, 0));
        var switchGroups = new ZombiesSpawnGroupChanges(Set.of(1, 2), Set.of(0));
        var first = new ZombiesBarrierData("a", 10, 100, true, helper.getLevel().dimension(), pos, pos.above(), pos).withSpawnGroupChanges(switchGroups);
        var peer = new ZombiesBarrierData("b", 10, 100, true, helper.getLevel().dimension(), pos, pos.above(), pos).withSpawnGroupChanges(switchGroups);
        var restore = new ZombiesBarrierData("restore", 20, 100, true, helper.getLevel().dimension(), pos, pos.above(), pos)
                .withSpawnGroupChanges(new ZombiesSpawnGroupChanges(Set.of(0), Set.of(1, 2)));
        var expensive = new ZombiesBarrierData("expensive", 30, 5_000, true, helper.getLevel().dimension(), pos, pos.above(), pos).withSpawnGroupChanges(switchGroups);
        var unknown = new ZombiesBarrierData("unknown", 40, 100, true, helper.getLevel().dimension(), pos, pos.above(), pos)
                .withSpawnGroupChanges(new ZombiesSpawnGroupChanges(Set.of(99), Set.of()));
        var plain = new ZombiesBarrierData("plain", 50, 100, true, helper.getLevel().dimension(), pos, pos.above(), pos);
        var barriers = List.of(first, peer, restore, expensive, unknown, plain);
        store.resetBarriers(barriers);
        int[] notifications = {0};
        var service = new ZombiesBarrierService(room, () -> barriers, economy, store, groups, id -> true,
                () -> ZombiesGamePhase.WAVE_ACTIVE, result -> {
                    notifications[0]++;
                    check(players.get(player.getUUID()).orElseThrow().displayPoints() == 1_000 - notifications[0] * 100,
                            "notifications must run after deducting points");
                }, () -> spawns);
        var entities = new ArrayList<Mob>();
        try {
            check(!service.purchase(player, expensive).success(), "insufficient funds reject purchase");
            check(!service.purchase(player, unknown).success(), "unknown targets reject purchase before mutations");
            check(groups.snapshot().equals(Set.of(0)) && !store.isBarrierCleared(unknown), "failed purchase leaves runtime intact");
            check(service.purchase(player, peer).success(), "either linked entrance may open group");
            check(store.isBarrierCleared(first) && store.isBarrierCleared(peer), "all linked barriers open");
            check(groups.snapshot().equals(Set.of(1, 2)), "enable multiple groups and disable zero");
            check(!service.purchase(player, first).success() && notifications[0] == 1, "repeat purchase does not replay");
            check(service.purchase(player, restore).success() && groups.snapshot().equals(Set.of(0)), "another door may reenable zero");
            check(service.purchase(player, plain).success() && groups.snapshot().equals(Set.of(0)), "empty actions never enable barrier number");

            var objects = new ZombiesMapObjects(List.of(), spawns, barriers, List.of(), List.of(), List.of(),
                    Optional.empty(), List.of(), List.of(), List.of(), List.of());
            var wave = new Gson().fromJson("{\"wave\":1,\"maxAlive\":10,\"mobs\":[{\"entity\":\"minecraft:zombie\",\"count\":5}]}", ZombiesWaveDefinition.class);
            wave.attachSource(null, 1, true);
            wave.applyDefaults(new ZombiesRulesConfig.Defaults());
            var state = new ZombiesWaveRuntimeState();
            state.beginTargetWave(wave);
            var spawnService = new ZombiesMobSpawnService(ModeEntityOwnershipRegistry.instance(), null, ZombiesRulesConfig.SpawnPointWeighting::new);
            var spawned = spawnService.spawnNext(room, helper.getLevel(), objects, state, wave, groups.snapshot());
            check(spawned.spawned() && spawned.spawnObjectId().orElseThrow().equals("spawn-zero"), "initial group produces a real mob");
            entities.add(spawned.entity().orElseThrow());
            int budget = state.remainingBudget();
            groups.apply(new ZombiesSpawnGroupChanges(Set.of(), Set.of(0)));
            var disabled = spawnService.spawnNext(room, helper.getLevel(), objects, state, wave, groups.snapshot());
            check(disabled.failureReason().orElseThrow() == ZombiesMobSpawnService.SpawnFailureReason.NO_AVAILABLE_SPAWN, "empty active groups must not fall back");
            check(state.remainingBudget() == budget && !state.isWaveComplete() && state.activeZombies() == 1,
                    "disabling preserves budget and existing mob accounting");
            check(!entities.get(0).isRemoved(), "disabling must not discard existing zombies");
            groups.apply(new ZombiesSpawnGroupChanges(Set.of(2), Set.of()));
            check(!spawnService.spawnNext(room, helper.getLevel(), objects, state, wave, groups.snapshot()).spawned(), "zero-weight point remains disabled");
            groups.apply(new ZombiesSpawnGroupChanges(Set.of(1), Set.of(2)));
            var resumed = spawnService.spawnNext(room, helper.getLevel(), objects, state, wave, groups.snapshot());
            check(resumed.spawned() && resumed.spawnObjectId().orElseThrow().equals("spawn-one"), "reenabling resumes at selected group");
            entities.add(resumed.entity().orElseThrow());
            state.beginTargetWave(wave);
            check(groups.snapshot().equals(Set.of(1)), "wave change preserves groups");
            groups.resetToInitial();
            check(groups.snapshot().equals(Set.of(0)), "new round restores zero");
            helper.succeed();
        } finally {
            entities.forEach(Mob::discard);
            ModeEntityOwnershipRegistry.instance().clearRoom(room);
            ZombiesActiveMobCounter.instance().clearRoom(room);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
