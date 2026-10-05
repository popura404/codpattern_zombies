package com.cdp.codpattern.app.zombies.validation;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.editor.ModeObjectData;
import com.cdp.codpattern.app.match.persistence.CommonModeMapData;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesInitialSpawnData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ZombiesMapValidatorMvp2Mvp3CompatTest {
    private static final RoomId ROOM_ID = RoomId.of("zombies", "validator_mvp2_mvp3_compat");
    private static final String MAP_DIMENSION = "minecraft:overworld";
    private static final String OTHER_DIMENSION = "minecraft:the_nether";
    private static final ZombiesMapSnapshot.BoundsSnapshot MAP_BOUNDS =
            new ZombiesMapSnapshot.BoundsSnapshot(new BlockPos(0, 0, 0), new BlockPos(20, 20, 20));

    private ZombiesMapValidatorMvp2Mvp3CompatTest() {
    }

    public static void main(String[] args) {
        mvp2WeaponWallOnlyRequiresLocation();
        mvp3RequiresPowerSodaWithoutPowerSwitchFails();
        mvp3RequiresPowerUltimateWithoutPowerSwitchFails();
        mvp3NoPowerSwitchPasses();
        zombieSpawnGroupWithoutEnablerWarns();
        orphanBarrierGroupIsNonBlocking();
        mvp3MultiplePowerSwitchesPass();
        mvp3MissingSodaMachinePasses();
        mvp3MissingUltimateMachinePasses();
        mvp3InvalidSodaBuffFails();
        mvp3InvalidPowerSwitchIdentifierFails();
        playerInitialSpawnsMoreThanFourPass();
        playerSpawnGroupsRequireGroupZero();
        negativePlayerSpawnGroupFails();
        playerAndZombieSpawnGroupsAreIndependent();
        playerSpawnGroupActionsRejectConflictsAndUnknownTargets();
        playerSpawnGroupActionsMustMatchWithinBarrierGroup();
        playerSpawnGroupValidationDoesNotAssumeDoorPurchaseOrder();
        snapshotsRetainPlayerSpawnGroupsAndActions();
        contributorSnapshotsReadPlayerSpawnGroupsAndActions();
        legacyBarrierSnapshotHasNoPlayerSpawnChanges();
        mvp3UltimateMapLevelFieldsAreIgnored();
        mvp3SpawnMissingLocationFails();
        mvp3RequiredObjectMissingLocationFails();
        mvp3RequiredObjectCrossDimensionFails();
        mvp3RequiredObjectOutOfBoundsFails();
        mvp3SpawnOutOfBoundsFails();
        mvp3BarrierAreaOutOfBoundsFails();
        mvp3DiagonalBarrierAreaFails();
        mvp3BarrierLengthHeightAndCellLimitsFail();
        mvp3LinkedBarrierEntriesAllowDifferentCosts();
        mvp3BarrierOverlappingCellsFail();
        mvp3BarrierBlocksPlayersOnlyFalseFails();
        invalidBarrierRequiredItemFailsMapValidation();
        mvp3FullInitialSnapshotSucceeds();
    }

    private static void mvp2WeaponWallOnlyRequiresLocation() {
        ZombiesMapSnapshot.WeaponWallSnapshot wall = new ZombiesMapSnapshot.WeaponWallSnapshot(
                "wall-1",
                "weaponWall",
                MAP_DIMENSION,
                new BlockPos(7, 1, 7));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP2_PURCHASES,
                snapshot(List.of(wall), List.of(), List.of(), List.of()));

        require(report.valid(), "MVP2 weapon wall should no longer require embedded sale fields: " + issueCodes(report));
        requireNoIssue(report, "map.invalid_weapon_wall");
        requireNoIssue(report, "map.weapon_wall_missing_top_rarity_candidate");
    }

    private static void mvp3RequiresPowerSodaWithoutPowerSwitchFails() {
        ZombiesMapSnapshot.SodaMachineSnapshot soda = validSoda();

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(), List.of(soda), List.of(validUltimate())));

        require(report.hasErrors(), "powered soda without a power switch should fail: " + issueCodes(report));
        requireIssue(report, "map.missing_power_switch");
    }

    private static void mvp3RequiresPowerUltimateWithoutPowerSwitchFails() {
        ZombiesMapSnapshot.UltimateMachineSnapshot ultimate = validUltimate();

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(), List.of(validSoda()), List.of(ultimate)));

        require(report.hasErrors(), "powered ultimate without a power switch should fail: " + issueCodes(report));
        requireIssue(report, "map.missing_power_switch");
    }

    private static void mvp3NoPowerSwitchPasses() {
        ZombiesMapSnapshot.SodaMachineSnapshot soda = new ZombiesMapSnapshot.SodaMachineSnapshot(
                "soda-1", "sodaMachine", "double_health", 1500, false, MAP_DIMENSION, new BlockPos(4, 1, 4));
        ZombiesMapSnapshot.UltimateMachineSnapshot ultimate = new ZombiesMapSnapshot.UltimateMachineSnapshot(
                "ultimate-1", "ultimateMachine", 3, Map.of(), false, MAP_DIMENSION, new BlockPos(5, 1, 5));
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(), List.of(soda), List.of(ultimate)));

        require(report.valid(), "MVP3 map without power switch should pass: " + issueCodes(report));
        requireNoIssue(report, "map.missing_power_switch");
    }

    private static void zombieSpawnGroupWithoutEnablerWarns() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP1_MINIMAL,
                snapshot(
                        List.of(initialSpawn(), zombieSpawn(), zombieSpawn("zombie-2", new BlockPos(2, 1, 2), 2)),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        require(report.valid(), "unreferenced groups should warn without preventing editing/startup");
        requireIssue(report, "map.spawn_group_never_enabled");
    }

    private static void orphanBarrierGroupIsNonBlocking() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP1_MINIMAL,
                snapshot(
                        List.of(initialSpawn(), zombieSpawn()),
                        List.of(new ZombiesMapSnapshot.BarrierSnapshot(
                                "barrier-9", "barrier", 9, 0, true, MAP_DIMENSION,
                                new BlockPos(6, 1, 6), new BlockPos(6, 1, 6), new BlockPos(6, 2, 6),
                                "", ZombiesSpawnGroupChanges.NONE, 1)),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        require(report.valid(), "orphan barrier groups should remain non-blocking: " + issueCodes(report));
    }

    private static void mvp3MultiplePowerSwitchesPass() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(),
                        List.of(powerSwitch("power-1"), powerSwitch("power-2")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.valid(), "MVP3 map with multiple power switches should pass: " + issueCodes(report));
        requireNoIssue(report, "map.multiple_power_switches");
    }

    private static void mvp3MissingSodaMachinePasses() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch("power-1")), List.of(), List.of(validUltimate())));

        require(report.valid(), "missing soda machine should be optional: " + issueCodes(report));
        requireNoIssue(report, "map.missing_soda_machine");
    }

    private static void mvp3MissingUltimateMachinePasses() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch("power-1")), List.of(validSoda()), List.of()));

        require(report.valid(), "missing ultimate machine should be optional: " + issueCodes(report));
        requireNoIssue(report, "map.missing_ultimate_machine");
    }

    private static void mvp3InvalidSodaBuffFails() {
        ZombiesMapSnapshot.SodaMachineSnapshot soda = new ZombiesMapSnapshot.SodaMachineSnapshot(
                "soda-1",
                "sodaMachine",
                "quick_revive",
                1500,
                true);
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch("power-1")), List.of(soda), List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 soda machine with unsupported buff should fail");
        requireIssue(report, "map.invalid_soda_machine");
    }

    private static void mvp3InvalidPowerSwitchIdentifierFails() {
        ZombiesMapSnapshot.PowerSwitchSnapshot powerSwitch = new ZombiesMapSnapshot.PowerSwitchSnapshot(
                "power-1",
                "lever",
                0,
                "minecraft:lever");
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch), List.of(validSoda()), List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 power switch with invalid feature/block identifier should fail");
        requireIssue(report, "map.invalid_power_switch");
    }

    private static void playerInitialSpawnsMoreThanFourPass() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(
                                initialSpawn("initial-1", new BlockPos(1, 1, 1)),
                                initialSpawn("initial-2", new BlockPos(1, 1, 2)),
                                initialSpawn("initial-3", new BlockPos(1, 1, 3)),
                                initialSpawn("initial-4", new BlockPos(1, 1, 4)),
                                initialSpawn("initial-5", new BlockPos(1, 1, 5)),
                                zombieSpawn()),
                        List.of(),
                        List.of(powerSwitch("power-1")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.valid(), "more than four INITIAL player spawns should pass: " + issueCodes(report));
        requireNoIssue(report, "map.too_many_initial_player_spawns");
    }

    private static void playerSpawnGroupsRequireGroupZero() {
        ZombiesMapValidationReport report = validatePlayerGroups(
                List.of(playerSpawn(2), zombieSpawn()), List.of());
        requireIssue(report, "map.missing_initial_spawn");
    }

    private static void negativePlayerSpawnGroupFails() {
        ZombiesMapValidationReport report = validatePlayerGroups(
                List.of(playerSpawn(0), playerSpawn(-1), zombieSpawn()), List.of());
        requireIssue(report, "map.invalid_player_spawn");
    }

    private static void playerAndZombieSpawnGroupsAreIndependent() {
        var zombieChanges = new ZombiesSpawnGroupChanges(Set.of(5), Set.of(0));
        var playerChanges = new ZombiesSpawnGroupChanges(Set.of(2), Set.of(0));
        List<ZombiesMapSnapshot.SpawnSnapshot> spawns = List.of(playerSpawn(0), playerSpawn(2),
                zombieSpawn(), zombieSpawn("zombie-5", new BlockPos(2, 1, 5), 5));
        ZombiesMapValidationReport valid = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 7, zombieChanges, playerChanges)));
        require(valid.valid(), "independent player/zombie groups should pass: " + issueCodes(valid));

        ZombiesMapValidationReport playerTarget = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 7, ZombiesSpawnGroupChanges.NONE,
                        new ZombiesSpawnGroupChanges(Set.of(5), Set.of()))));
        requireIssue(playerTarget, "map.unknown_player_spawn_group");
        requireNoIssue(playerTarget, "map.unknown_spawn_group");

        ZombiesMapValidationReport zombieTarget = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 7, new ZombiesSpawnGroupChanges(Set.of(2), Set.of()),
                        ZombiesSpawnGroupChanges.NONE)));
        requireIssue(zombieTarget, "map.unknown_spawn_group");
        requireNoIssue(zombieTarget, "map.unknown_player_spawn_group");
    }

    private static void playerSpawnGroupActionsRejectConflictsAndUnknownTargets() {
        List<ZombiesMapSnapshot.SpawnSnapshot> spawns = List.of(playerSpawn(0), playerSpawn(2), zombieSpawn());
        ZombiesMapValidationReport conflict = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 1, ZombiesSpawnGroupChanges.NONE,
                        new ZombiesSpawnGroupChanges(Set.of(2), Set.of(2)))));
        requireIssue(conflict, "map.invalid_player_spawn_group_changes");
        ZombiesMapValidationReport negative = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 1, ZombiesSpawnGroupChanges.NONE,
                        new ZombiesSpawnGroupChanges(Set.of(-1), Set.of()))));
        requireIssue(negative, "map.invalid_player_spawn_group_changes");
        ZombiesMapValidationReport unknown = validatePlayerGroups(spawns,
                List.of(groupBarrier("gate", 1, ZombiesSpawnGroupChanges.NONE,
                        new ZombiesSpawnGroupChanges(Set.of(), Set.of(99)))));
        requireIssue(unknown, "map.unknown_player_spawn_group");
    }

    private static void playerSpawnGroupActionsMustMatchWithinBarrierGroup() {
        var changes = new ZombiesSpawnGroupChanges(Set.of(2), Set.of(0));
        List<ZombiesMapSnapshot.SpawnSnapshot> spawns = List.of(playerSpawn(0), playerSpawn(2), zombieSpawn());
        var first = groupBarrier("gate-a", 7, ZombiesSpawnGroupChanges.NONE, changes);
        ZombiesMapValidationReport mismatch = validatePlayerGroups(spawns,
                List.of(first, groupBarrier("gate-b", 7, ZombiesSpawnGroupChanges.NONE, ZombiesSpawnGroupChanges.NONE)));
        requireIssue(mismatch, "map.invalid_player_spawn_group_changes");
        ZombiesMapValidationReport matching = validatePlayerGroups(spawns,
                List.of(first, groupBarrier("gate-b", 7, ZombiesSpawnGroupChanges.NONE, changes)));
        require(matching.valid(), "matching linked barriers should pass: " + issueCodes(matching));
    }

    private static void playerSpawnGroupValidationDoesNotAssumeDoorPurchaseOrder() {
        var first = groupBarrier("gate-a", 1, ZombiesSpawnGroupChanges.NONE,
                new ZombiesSpawnGroupChanges(Set.of(1), Set.of(0)));
        var second = groupBarrier("gate-b", 2, ZombiesSpawnGroupChanges.NONE,
                new ZombiesSpawnGroupChanges(Set.of(2), Set.of(1)));
        ZombiesMapValidationReport report = validatePlayerGroups(
                List.of(playerSpawn(0), playerSpawn(1), playerSpawn(2), zombieSpawn()), List.of(second, first));
        require(report.valid(), "barrier list order must not determine valid player spawn transitions: " + issueCodes(report));
    }

    private static void snapshotsRetainPlayerSpawnGroupsAndActions() {
        var changes = new ZombiesSpawnGroupChanges(Set.of(2), Set.of(0));
        var initial = new ZombiesInitialSpawnData(Level.OVERWORLD, new BlockPos(1, 1, 1), 0, 0, 2);
        var barrier = new ZombiesBarrierData("gate", "", 7, 0, true, Level.OVERWORLD,
                new BlockPos(6, 1, 6), new BlockPos(6, 2, 6), new BlockPos(6, 1, 6),
                "", ZombiesSpawnGroupChanges.NONE, 3, changes);
        ZombiesMapObjects objects = new ZombiesMapObjects(List.of(initial), List.of(), List.of(barrier),
                List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of());
        ZombiesMapSnapshot snapshot = ZombiesMapSnapshot.fromMapObjects(ROOM_ID, ROOM_ID.mapName(), true, objects);
        require(snapshot.spawns().get(0).group() == 2, "map-object snapshot must retain player group");
        require(snapshot.barriers().get(0).playerSpawnGroupChanges().equals(changes), "snapshot must retain player actions");
        require(snapshot.barriers().get(0).spawnGroupChanges().equals(ZombiesSpawnGroupChanges.NONE), "player actions must not become zombie actions");
        require(snapshot.barriers().get(0).entryId() == 3, "snapshot must retain barrier rule entry");
    }

    private static void contributorSnapshotsReadPlayerSpawnGroupsAndActions() {
        var changes = new ZombiesSpawnGroupChanges(Set.of(2), Set.of(0));
        CompoundTag spawnPayload = new CompoundTag();
        spawnPayload.putInt("group", 2);
        CompoundTag barrierPayload = new CompoundTag();
        barrierPayload.putInt("group", 7);
        barrierPayload.putInt("entryId", 3);
        barrierPayload.put("playerSpawnGroupChanges", changes.toTag());
        CommonModeMapData common = new CommonModeMapData(1, "zombies", ROOM_ID.mapName(), MAP_DIMENSION,
                new AreaData(BlockPos.ZERO, new BlockPos(20, 20, 20)), Optional.empty());
        var context = new ZombiesMapValidationContributor.ZombiesMapValidationContext(ROOM_ID, common,
                List.of(new ModeObjectData("initialSpawn", Level.OVERWORLD, new BlockPos(1, 1, 1), 0, 0, spawnPayload),
                        new ModeObjectData("barrier", Level.OVERWORLD, new BlockPos(6, 1, 6), 0, 0, barrierPayload)), "");
        ZombiesMapSnapshot snapshot = ZombiesMapSnapshot.fromContributorContext(context);
        require(snapshot.spawns().get(0).group() == 2, "payload snapshot must retain player group");
        require(snapshot.barriers().get(0).playerSpawnGroupChanges().equals(changes), "payload snapshot must read player actions");
        require(snapshot.barriers().get(0).entryId() == 3, "payload snapshot must read barrier rule entry");
    }

    private static void legacyBarrierSnapshotHasNoPlayerSpawnChanges() {
        var legacy = new ZombiesMapSnapshot.BarrierSnapshot("legacy", "barrier", 1, 0);
        require(legacy.entryId() == 0, "legacy snapshot must keep unselected rule entry");
        require(legacy.playerSpawnGroupChanges().equals(ZombiesSpawnGroupChanges.NONE), "legacy snapshot must leave player groups unchanged");
    }

    private static ZombiesMapValidationReport validatePlayerGroups(List<ZombiesMapSnapshot.SpawnSnapshot> spawns,
            List<ZombiesMapSnapshot.BarrierSnapshot> barriers) {
        return validate(ZombiesMapValidationProfile.MVP1_MINIMAL,
                ZombiesMapSnapshot.of(ROOM_ID, ROOM_ID.mapName(), true, spawns, barriers));
    }

    private static ZombiesMapSnapshot.SpawnSnapshot playerSpawn(int group) {
        return new ZombiesMapSnapshot.SpawnSnapshot("player-" + group, "initialSpawn", "INITIAL",
                group, 0, false, MAP_DIMENSION, new BlockPos(1, 1, 1));
    }

    private static ZombiesMapSnapshot.BarrierSnapshot groupBarrier(String id, int group,
            ZombiesSpawnGroupChanges zombieChanges, ZombiesSpawnGroupChanges playerChanges) {
        return new ZombiesMapSnapshot.BarrierSnapshot(id, "barrier", group, 0, true, MAP_DIMENSION,
                new BlockPos(6, 1, 6), new BlockPos(6, 1, 6), new BlockPos(6, 2, 6), "", zombieChanges, 1, playerChanges);
    }

    private static void mvp3UltimateMapLevelFieldsAreIgnored() {
        ZombiesMapSnapshot.UltimateMachineSnapshot ultimate = new ZombiesMapSnapshot.UltimateMachineSnapshot(
                "ultimate-1",
                "ultimateMachine",
                3,
                Map.of(
                        "1", new ZombiesMapSnapshot.UltimateLevelSnapshot(1200, 1.25D),
                        "3", new ZombiesMapSnapshot.UltimateLevelSnapshot(5000, Double.NaN)),
                true,
                MAP_DIMENSION,
                new BlockPos(5, 1, 5));
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch("power-1")), List.of(validSoda()), List.of(ultimate)));

        require(report.valid(),
                "MVP3 ultimate machine map-object level fields should be ignored in favor of serverconfig rules: "
                        + issueCodes(report));
        requireNoIssue(report, "map.invalid_ultimate_machine");
    }

    private static void mvp3SpawnMissingLocationFails() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(
                                new ZombiesMapSnapshot.SpawnSnapshot(
                                        "initial-1", "spawn", "INITIAL", 0, 0.0D, false),
                                zombieSpawn()),
                        List.of(),
                        List.of(powerSwitch("power-1")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 spawn without dimension/position should fail");
        requireIssue(report, "map.object_missing_location");
    }

    private static void mvp3RequiredObjectMissingLocationFails() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(),
                        List.of(new ZombiesMapSnapshot.PowerSwitchSnapshot(
                                "power-1",
                                "powerSwitch",
                                0,
                                "codpattern:zombies_power_switch")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 required object without dimension/position should fail");
        requireIssue(report, "map.object_missing_location");
    }

    private static void mvp3RequiredObjectCrossDimensionFails() {
        ZombiesMapSnapshot.SodaMachineSnapshot soda = new ZombiesMapSnapshot.SodaMachineSnapshot(
                "soda-1",
                "sodaMachine",
                "double_health",
                1500,
                true,
                OTHER_DIMENSION,
                new BlockPos(4, 1, 4));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch("power-1")), List.of(soda), List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 required object in another dimension should fail");
        requireIssue(report, "map.object_dimension_mismatch");
    }

    private static void mvp3RequiredObjectOutOfBoundsFails() {
        ZombiesMapSnapshot.PowerSwitchSnapshot powerSwitch = new ZombiesMapSnapshot.PowerSwitchSnapshot(
                "power-1",
                "powerSwitch",
                0,
                "codpattern:zombies_power_switch",
                MAP_DIMENSION,
                new BlockPos(99, 1, 3));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(List.of(), List.of(powerSwitch), List.of(validSoda()), List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 required object outside map bounds should fail");
        requireIssue(report, "map.object_out_of_bounds");
    }

    private static void mvp3SpawnOutOfBoundsFails() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(initialSpawn(), zombieSpawn("zombie-1", new BlockPos(2, 1, 99))),
                        List.of(),
                        List.of(powerSwitch("power-1")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 spawn outside map bounds should fail");
        requireIssue(report, "map.object_out_of_bounds");
    }

    private static void mvp3BarrierAreaOutOfBoundsFails() {
        ZombiesMapSnapshot.BarrierSnapshot barrier = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-1",
                "barrier",
                1,
                0,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(99, 1, 6));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(initialSpawn(), zombieSpawn()),
                        List.of(barrier),
                        List.of(validWeaponWall()),
                        List.of(validAmmoBox()),
                        List.of(validArmorStation()),
                        List.of(powerSwitch("power-1")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.hasErrors(), "MVP3 barrier area outside map bounds should fail");
        requireIssue(report, "map.object_out_of_bounds");
    }

    private static void invalidBarrierRequiredItemFailsMapValidation() {
        ZombiesMapSnapshot.BarrierSnapshot barrier = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-invalid-required-item",
                "barrier",
                1,
                0,
                true,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(6, 2, 6),
                "minecraft:diamond{broken");

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(barrier)));

        require(report.hasErrors(), "invalid non-empty barrier requiredItem should fail map validation");
        requireIssue(report, "map.invalid_barrier");
    }

    private static void mvp3DiagonalBarrierAreaFails() {
        ZombiesMapSnapshot.BarrierSnapshot barrier = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-1",
                "barrier",
                1,
                0,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(7, 2, 8));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(barrier)));

        require(report.hasErrors(), "MVP3 diagonal barrier area should fail");
        requireIssue(report, "map.invalid_barrier");
    }

    private static void mvp3BarrierLengthHeightAndCellLimitsFail() {
        ZombiesMapSnapshot.BarrierSnapshot tooLong = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-long",
                "barrier",
                1,
                0,
                MAP_DIMENSION,
                new BlockPos(0, 1, 0),
                new BlockPos(0, 1, 0),
                new BlockPos(0, 2, 33));
        ZombiesMapSnapshot.BarrierSnapshot tooTall = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-tall",
                "barrier",
                2,
                100,
                MAP_DIMENSION,
                new BlockPos(1, 1, 0),
                new BlockPos(1, 1, 0),
                new BlockPos(1, 9, 0));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(tooLong, tooTall)));

        require(report.hasErrors(), "MVP3 over-limit barrier dimensions should fail");
        requireIssue(report, "map.invalid_barrier");
    }

    private static void mvp3LinkedBarrierEntriesAllowDifferentCosts() {
        ZombiesMapSnapshot.BarrierSnapshot first = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-2-a",
                "barrier",
                2,
                750,
                true,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(6, 2, 6),
                "", ZombiesSpawnGroupChanges.NONE, 1);
        ZombiesMapSnapshot.BarrierSnapshot second = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-2-b",
                "barrier",
                2,
                1000,
                true,
                MAP_DIMENSION,
                new BlockPos(7, 1, 7),
                new BlockPos(7, 1, 7),
                new BlockPos(7, 2, 7),
                "", ZombiesSpawnGroupChanges.NONE, 2);

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(first, second)));

        require(report.valid(), "different rule entries within one linked barrier group may have different costs: " + issueCodes(report));
        requireNoIssue(report, "map.invalid_barrier");
    }

    private static void mvp3BarrierOverlappingCellsFail() {
        ZombiesMapSnapshot.BarrierSnapshot first = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-overlap-a",
                "barrier",
                1,
                0,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(6, 2, 6));
        ZombiesMapSnapshot.BarrierSnapshot second = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-overlap-b",
                "barrier",
                2,
                1000,
                MAP_DIMENSION,
                new BlockPos(6, 2, 6),
                new BlockPos(6, 2, 6),
                new BlockPos(6, 3, 6));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(first, second)));

        require(report.hasErrors(), "MVP3 overlapping barrier cells should fail");
        requireIssue(report, "map.invalid_barrier");
    }

    private static void mvp3BarrierBlocksPlayersOnlyFalseFails() {
        ZombiesMapSnapshot.BarrierSnapshot barrier = new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-1",
                "barrier",
                1,
                0,
                false,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(6, 2, 6));

        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshotWithBarriers(List.of(barrier)));

        require(report.hasErrors(), "MVP3 barrier with blocksPlayersOnly=false should fail");
        requireIssue(report, "map.invalid_barrier");
    }

    private static void mvp3FullInitialSnapshotSucceeds() {
        ZombiesMapValidationReport report = validate(
                ZombiesMapValidationProfile.MVP3_FULL_INITIAL,
                snapshot(
                        List.of(),
                        List.of(powerSwitch("power-1")),
                        List.of(validSoda()),
                        List.of(validUltimate())));

        require(report.valid(), "MVP3 full initial snapshot should pass: " + issueCodes(report));
    }

    private static ZombiesMapValidationReport validate(
            ZombiesMapValidationProfile profile,
            ZombiesMapSnapshot snapshot
    ) {
        return new ZombiesMapValidator(profile).validate(snapshot);
    }

    private static ZombiesMapSnapshot snapshot(
            List<ZombiesMapSnapshot.WeaponWallSnapshot> weaponWalls,
            List<ZombiesMapSnapshot.PowerSwitchSnapshot> powerSwitches,
            List<ZombiesMapSnapshot.SodaMachineSnapshot> sodaMachines,
            List<ZombiesMapSnapshot.UltimateMachineSnapshot> ultimateMachines
    ) {
        return snapshot(
                List.of(initialSpawn(), zombieSpawn()),
                weaponWalls,
                powerSwitches,
                sodaMachines,
                ultimateMachines);
    }

    private static ZombiesMapSnapshot snapshot(
            List<ZombiesMapSnapshot.SpawnSnapshot> spawns,
            List<ZombiesMapSnapshot.BarrierSnapshot> barriers,
            List<ZombiesMapSnapshot.WeaponWallSnapshot> weaponWalls,
            List<ZombiesMapSnapshot.AmmoBoxSnapshot> ammoBoxes,
            List<ZombiesMapSnapshot.ArmorStationSnapshot> armorStations,
            List<ZombiesMapSnapshot.PowerSwitchSnapshot> powerSwitches,
            List<ZombiesMapSnapshot.SodaMachineSnapshot> sodaMachines,
            List<ZombiesMapSnapshot.UltimateMachineSnapshot> ultimateMachines
    ) {
        return ZombiesMapSnapshot.of(
                ROOM_ID,
                ROOM_ID.mapName(),
                true,
                MAP_DIMENSION,
                MAP_BOUNDS,
                spawns,
                barriers,
                weaponWalls,
                ammoBoxes,
                armorStations,
                powerSwitches,
                sodaMachines,
                ultimateMachines,
                List.of());
    }

    private static ZombiesMapSnapshot snapshotWithBarriers(List<ZombiesMapSnapshot.BarrierSnapshot> barriers) {
        return snapshot(
                List.of(initialSpawn(), zombieSpawn()),
                barriers,
                List.of(validWeaponWall()),
                List.of(validAmmoBox()),
                List.of(validArmorStation()),
                List.of(powerSwitch("power-1")),
                List.of(validSoda()),
                List.of(validUltimate()));
    }

    private static ZombiesMapSnapshot snapshot(
            List<ZombiesMapSnapshot.SpawnSnapshot> spawns,
            List<ZombiesMapSnapshot.WeaponWallSnapshot> weaponWalls,
            List<ZombiesMapSnapshot.PowerSwitchSnapshot> powerSwitches,
            List<ZombiesMapSnapshot.SodaMachineSnapshot> sodaMachines,
            List<ZombiesMapSnapshot.UltimateMachineSnapshot> ultimateMachines
    ) {
        List<ZombiesMapSnapshot.WeaponWallSnapshot> resolvedWeaponWalls = weaponWalls == null || weaponWalls.isEmpty()
                ? List.of(validWeaponWall())
                : weaponWalls;
        return ZombiesMapSnapshot.of(
                ROOM_ID,
                ROOM_ID.mapName(),
                true,
                MAP_DIMENSION,
                MAP_BOUNDS,
                spawns,
                List.of(validBarrier()),
                resolvedWeaponWalls,
                List.of(validAmmoBox()),
                List.of(validArmorStation()),
                powerSwitches,
                sodaMachines,
                ultimateMachines,
                List.of());
    }

    private static ZombiesMapSnapshot.SpawnSnapshot initialSpawn() {
        return initialSpawn("initial-1", new BlockPos(1, 1, 1));
    }

    private static ZombiesMapSnapshot.SpawnSnapshot initialSpawn(String objectId, BlockPos pos) {
        return new ZombiesMapSnapshot.SpawnSnapshot(
                objectId,
                "spawn",
                "INITIAL",
                0,
                0.0D,
                false,
                MAP_DIMENSION,
                pos);
    }

    private static ZombiesMapSnapshot.SpawnSnapshot zombieSpawn() {
        return zombieSpawn("zombie-1", new BlockPos(2, 1, 2));
    }

    private static ZombiesMapSnapshot.SpawnSnapshot zombieSpawn(String objectId, BlockPos pos) {
        return zombieSpawn(objectId, pos, 0);
    }

    private static ZombiesMapSnapshot.SpawnSnapshot zombieSpawn(String objectId, BlockPos pos, int group) {
        return new ZombiesMapSnapshot.SpawnSnapshot(
                objectId,
                "zombieSpawn",
                "",
                group,
                1.0D,
                true,
                MAP_DIMENSION,
                pos);
    }

    private static ZombiesMapSnapshot.PowerSwitchSnapshot powerSwitch(String objectId) {
        return new ZombiesMapSnapshot.PowerSwitchSnapshot(
                objectId,
                "powerSwitch",
                0,
                "codpattern:zombies_power_switch",
                MAP_DIMENSION,
                new BlockPos(3, 1, 3));
    }

    private static ZombiesMapSnapshot.BarrierSnapshot validBarrier() {
        return new ZombiesMapSnapshot.BarrierSnapshot(
                "barrier-1",
                "barrier",
                1,
                0,
                true,
                MAP_DIMENSION,
                new BlockPos(6, 1, 6),
                new BlockPos(6, 1, 6),
                new BlockPos(6, 2, 6),
                "", ZombiesSpawnGroupChanges.NONE, 1);
    }

    private static ZombiesMapSnapshot.WeaponWallSnapshot validWeaponWall() {
        return new ZombiesMapSnapshot.WeaponWallSnapshot(
                "wall-1",
                "weaponWall",
                MAP_DIMENSION,
                new BlockPos(7, 1, 7));
    }

    private static ZombiesMapSnapshot.AmmoBoxSnapshot validAmmoBox() {
        return new ZombiesMapSnapshot.AmmoBoxSnapshot(
                "ammo-1",
                "ammoBox",
                Map.of("1", 0),
                MAP_DIMENSION,
                new BlockPos(8, 1, 8));
    }

    private static ZombiesMapSnapshot.ArmorStationSnapshot validArmorStation() {
        return new ZombiesMapSnapshot.ArmorStationSnapshot(
                "armor-1",
                "armorStation",
                1,
                500,
                0.9D,
                MAP_DIMENSION,
                new BlockPos(9, 1, 9));
    }

    private static ZombiesMapSnapshot.SodaMachineSnapshot validSoda() {
        return new ZombiesMapSnapshot.SodaMachineSnapshot(
                "soda-1",
                "sodaMachine",
                "double_health",
                1500,
                true,
                MAP_DIMENSION,
                new BlockPos(4, 1, 4));
    }

    private static ZombiesMapSnapshot.UltimateMachineSnapshot validUltimate() {
        return new ZombiesMapSnapshot.UltimateMachineSnapshot(
                "ultimate-1",
                "ultimateMachine",
                3,
                Map.of(
                        "1", new ZombiesMapSnapshot.UltimateLevelSnapshot(1200, 1.25D),
                        "2", new ZombiesMapSnapshot.UltimateLevelSnapshot(2500, 1.5D),
                        "3", new ZombiesMapSnapshot.UltimateLevelSnapshot(5000, 2.0D)),
                true,
                MAP_DIMENSION,
                new BlockPos(5, 1, 5));
    }

    private static void requireIssue(ZombiesMapValidationReport report, String code) {
        require(report.issues().stream().anyMatch(issue -> code.equals(issue.code().key())),
                "expected issue " + code + ", got " + issueCodes(report));
    }

    private static void requireNoIssue(ZombiesMapValidationReport report, String code) {
        require(report.issues().stream().noneMatch(issue -> code.equals(issue.code().key())),
                "expected no issue " + code + ", got " + issueCodes(report));
    }

    private static String issueCodes(ZombiesMapValidationReport report) {
        return report.issues().stream()
                .map(issue -> issue.code().key())
                .toList()
                .toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
