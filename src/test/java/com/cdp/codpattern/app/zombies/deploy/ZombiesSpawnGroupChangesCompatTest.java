package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveSpawnGroupService;
import com.cdp.codpattern.app.zombies.validation.ZombiesMapValidator;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ZombiesSpawnGroupChangesCompatTest {
    public static void main(String[] args) {
        var none = ZombiesSpawnGroupFields.parse(Map.of());
        check(none.equals(ZombiesSpawnGroupChanges.NONE), "both missing lists must be no action");
        check(ZombiesSpawnGroupFields.parse(Map.of("enableSpawnGroups", " ;，\n ", "disableSpawnGroups", ""))
                .equals(none), "empty placeholders must not become group zero");
        check(ZombiesSpawnGroupFields.parse("enableSpawnGroups", "3,1，2;3；0\r\n4")
                .equals(Set.of(0, 1, 2, 3, 4)), "paste separators and duplicate removal");
        check(ZombiesSpawnGroupFields.format(Set.of(3, 0, 1)).equals("0, 1, 3"), "stable sorted serialization");
        for (String invalid : List.of("-1", "1.2", "1-3", "*", "2147483648", "+1", "foo")) {
            try {
                ZombiesSpawnGroupFields.parse("disableSpawnGroups", invalid);
                throw new AssertionError("must reject " + invalid);
            } catch (ZombiesSpawnGroupFields.InvalidGroups error) {
                check(error.field().equals("disableSpawnGroups") && error.entry().equals(invalid), "error identifies input");
            }
        }
        try {
            ZombiesSpawnGroupFields.parse(Map.of("enableSpawnGroups", "1,2", "disableSpawnGroups", "2,3"));
            throw new AssertionError("overlap must fail");
        } catch (ZombiesSpawnGroupFields.InvalidGroups error) {
            check(error.conflict() && error.entry().equals("2"), "overlap names conflicting groups");
        }
        var state = new ZombiesActiveSpawnGroupService();
        check(state.snapshot().equals(Set.of(0)), "initial group zero");
        state.apply(none);
        check(state.snapshot().equals(Set.of(0)), "empty action preserves groups");
        state.apply(new ZombiesSpawnGroupChanges(Set.of(1, 2), Set.of()));
        check(state.snapshot().equals(Set.of(0, 1, 2)), "enable only");
        state.apply(new ZombiesSpawnGroupChanges(Set.of(), Set.of(0, 2)));
        check(state.snapshot().equals(Set.of(1)), "disable only");
        state.apply(new ZombiesSpawnGroupChanges(Set.of(0, 3), Set.of(1)));
        check(state.snapshot().equals(Set.of(0, 3)), "reenable zero and disable old region");
        state.apply(new ZombiesSpawnGroupChanges(Set.of(), Set.of(0, 3)));
        check(state.snapshot().isEmpty(), "all disabled remains empty");
        state.resetToInitial();
        check(state.snapshot().equals(Set.of(0)), "new round resets");
    }

    public static void runRuntime() {
        main(new String[0]);
        var first = edit(ZombiesMapObjects.EMPTY, ZombiesDeployObjectEditor.Operation.ADD, -1,
                Map.of("objectId", "door-a", "group", "10", "enableSpawnGroups", "2,1", "disableSpawnGroups", "0"));
        check(first.success(), "first barrier parses");
        var second = edit(first.objects(), ZombiesDeployObjectEditor.Operation.ADD, -1,
                Map.of("objectId", "door-b", "group", "10", "enableSpawnGroups", "3", "disableSpawnGroups", "0;2"));
        check(second.success(), "second barrier parses");
        var changes = new ZombiesSpawnGroupChanges(Set.of(3), Set.of(0, 2));
        check(second.objects().barriers().stream().allMatch(barrier -> barrier.spawnGroupChanges().equals(changes)),
                "one edit synchronizes the whole linked group");
        var clearFields = new LinkedHashMap<>(second.fields());
        clearFields.put("enableSpawnGroups", ""); clearFields.put("disableSpawnGroups", " ");
        var clear = edit(second.objects(), ZombiesDeployObjectEditor.Operation.UPDATE, 1, clearFields);
        check(clear.success() && clear.objects().barriers().stream().allMatch(barrier -> barrier.spawnGroupChanges().equals(ZombiesSpawnGroupChanges.NONE)),
                "explicit empty fields overwrite previous actions on the whole group");
        check(clear.fields().get("enableSpawnGroups").isEmpty(), "empty list survives field roundtrip");
        var movedFields = new LinkedHashMap<>(second.fields());
        movedFields.put("group", "20"); movedFields.put("enableSpawnGroups", "0"); movedFields.put("disableSpawnGroups", "3");
        var moved = edit(second.objects(), ZombiesDeployObjectEditor.Operation.UPDATE, 1, movedFields);
        check(moved.success() && moved.objects().barriers().get(0).spawnGroupChanges().equals(changes), "moving groups leaves old peers unchanged");
        var copied = edit(moved.objects(), ZombiesDeployObjectEditor.Operation.DUPLICATE, 1, Map.of());
        check(copied.success() && copied.objects().barriers().get(2).spawnGroupChanges().equals(moved.objects().barriers().get(1).spawnGroupChanges()),
                "copy preserves actions");
        var invalidFields = new LinkedHashMap<>(second.fields());
        invalidFields.put("disableSpawnGroups", "3");
        var invalid = edit(second.objects(), ZombiesDeployObjectEditor.Operation.UPDATE, 1, invalidFields);
        check(!invalid.success() && invalid.objects().equals(second.objects()), "conflict cannot partially mutate peers");

        var codec = ZombiesMapObjects.CODEC.codec();
        var json = codec.encodeStart(JsonOps.INSTANCE, second.objects()).result().orElseThrow();
        check(codec.parse(JsonOps.INSTANCE, json).result().orElseThrow().equals(second.objects()), "map JSON roundtrip preserves group actions");
        var barrierJson = ZombiesBarrierData.CODEC.encodeStart(JsonOps.INSTANCE, second.objects().barriers().get(0)).result().orElseThrow().getAsJsonObject();
        barrierJson.remove("spawnGroupChanges");
        check(ZombiesBarrierData.CODEC.parse(JsonOps.INSTANCE, barrierJson).result().orElseThrow().spawnGroupChanges().equals(ZombiesSpawnGroupChanges.NONE),
                "missing actions never imply same-number activation");
        var spawn = new ZombiesZombieSpawnData("s", 0, 1, Level.OVERWORLD, BlockPos.ZERO, 0, 0);
        var spawnJson = ZombiesZombieSpawnData.CODEC.encodeStart(JsonOps.INSTANCE, spawn).result().orElseThrow().getAsJsonObject();
        spawnJson.remove("group");
        check(ZombiesZombieSpawnData.CODEC.parse(JsonOps.INSTANCE, spawnJson).result().orElseThrow().group() == 0, "default spawn group zero");
        var snapshot = ZombiesMapSnapshot.fromMapObjects(RoomId.of("zombies", "group-validation"), "group-validation", false, second.objects());
        check(snapshot.barriers().get(0).spawnGroupChanges().equals(changes), "validation snapshot retains actions");
        var report = new ZombiesMapValidator().validate(snapshot);
        check(report.issues().stream().anyMatch(issue -> issue.code().key().equals("map.unknown_spawn_group")), "unknown targets are reported by map validation");
        var spawns = java.util.stream.IntStream.rangeClosed(0, 3)
                .mapToObj(group -> new ZombiesZombieSpawnData("spawn-" + group, group, group == 2 ? 0 : 1,
                        Level.OVERWORLD, new BlockPos(group, 64, 0), 0, 0)).toList();
        var validObjects = new ZombiesMapObjects(List.of(new com.cdp.codpattern.app.zombies.map.object.ZombiesInitialSpawnData(
                Level.OVERWORLD, new BlockPos(8, 64, 0), 0, 0)), spawns, second.objects().barriers(),
                List.of(), List.of(), List.of(), java.util.Optional.empty(), List.of(), List.of(), List.of(), List.of());
        var validReport = new ZombiesMapValidator().validate(ZombiesMapSnapshot.fromMapObjects(
                RoomId.of("zombies", "valid-groups"), "valid-groups", false, validObjects));
        check(validReport.valid(), "link group 10 may reference spawn groups 0, 2, 3 without same-number spawns");
        check(validReport.issues().stream().anyMatch(issue -> issue.code().key().equals("map.spawn_group_without_usable_spawn")), "zero-weight target warns");
        check(validReport.issues().stream().anyMatch(issue -> issue.code().key().equals("map.spawn_group_never_enabled")), "unreachable noninitial group warns");
        var inconsistent = new java.util.ArrayList<>(validObjects.barriers());
        inconsistent.set(1, inconsistent.get(1).withSpawnGroupChanges(ZombiesSpawnGroupChanges.NONE));
        var inconsistentObjects = new ZombiesMapObjects(validObjects.initialSpawns(), spawns, inconsistent,
                List.of(), List.of(), List.of(), java.util.Optional.empty(), List.of(), List.of(), List.of(), List.of());
        var inconsistentReport = new ZombiesMapValidator().validate(ZombiesMapSnapshot.fromMapObjects(
                RoomId.of("zombies", "inconsistent"), "inconsistent", false, inconsistentObjects));
        check(inconsistentReport.issues().stream().anyMatch(issue -> issue.code().key().equals("map.invalid_spawn_group_changes")), "manual inconsistent peer config fails validation");

    }

    private static ZombiesDeployObjectEditor.EditResult edit(ZombiesMapObjects objects, ZombiesDeployObjectEditor.Operation operation,
            int index, Map<String, String> overrides) {
        var fields = new LinkedHashMap<>(ZombiesDeployFieldSchema.defaultFields(ZombiesDeployFieldSchema.BARRIER));
        fields.putAll(overrides);
        return ZombiesDeployObjectEditor.edit(objects, operation, ZombiesDeployFieldSchema.BARRIER, index, fields);
    }

    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
