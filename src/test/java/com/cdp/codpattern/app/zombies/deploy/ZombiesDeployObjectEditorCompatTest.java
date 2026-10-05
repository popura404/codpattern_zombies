package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesAmmoBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesArmorStationData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesInitialSpawnData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesMysteryBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesPowerSwitchData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSodaMachineData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesUltimateMachineData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesWeaponWallData;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ZombiesDeployObjectEditorCompatTest {
    private ZombiesDeployObjectEditorCompatTest() {
    }

    public static void main(String[] args) {
        if (!bootstrapMinecraft()) {
            return;
        }
        runAll();
    }

    public static void runAll() {
        initialSpawnsSupportGroupsAndPersistence();
        barrierAreaFieldsParseUpdateAndDuplicate();
        ammoBoxPricesByWeaponLevelParseAndUpdateFromListField();
        armorStationFieldsParseAndUpdate();
        armorStationRejectsInvalidLevel();
        powerSwitchSingleObjectCrudKeepsFieldValues();
        sodaMachineFieldsParseAndDuplicate();
        ultimateMachineRuleFieldsIgnoredAndRemovedFromEditorFields();
        duplicateDeleteAndClearKeepSelectionAndCountsStable();
        failurePathsKeepOriginalObjects();
        weaponWallDeprecatedFieldsIgnoredAndRemovedFromEditorFields();
        weaponWallDuplicateCreatesNonConflictingObjectId();
        boxFacingSurvivesEditingDuplicationAndPersistence();
    }

    private static void boxFacingSurvivesEditingDuplicationAndPersistence() {
        Map<String, String> collectionKeys = Map.of(
                ZombiesDeployFieldSchema.WEAPON_WALL, "weaponWalls",
                ZombiesDeployFieldSchema.AMMO_BOX, "ammoBoxes",
                ZombiesDeployFieldSchema.ARMOR_STATION, "armorStations",
                ZombiesDeployFieldSchema.SODA_MACHINE, "sodaMachines",
                ZombiesDeployFieldSchema.ULTIMATE_MACHINE, "ultimateMachines",
                ZombiesDeployFieldSchema.MYSTERY_BOX, "mysteryBoxes");
        var codec = ZombiesMapObjects.CODEC.codec();
        for (var entry : collectionKeys.entrySet()) {
            String type = entry.getKey();
            var current = edit(ZombiesMapObjects.EMPTY, ZombiesDeployObjectEditor.Operation.ADD, type, -1,
                    fields(type, "facing", "east"));
            requireSuccess(current, type + " directional add");
            require(boxFacing(current.objects(), type, 0) == Direction.EAST, type + " add must keep facing");
            for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                var changed = new LinkedHashMap<>(current.fields());
                changed.put("facing", direction.getName());
                current = edit(current.objects(), ZombiesDeployObjectEditor.Operation.UPDATE, type, 0, changed);
                requireSuccess(current, type + " directional update");
                require(boxFacing(current.objects(), type, 0) == direction, type + " update must keep facing");
                require(direction.getName().equals(current.fields().get("facing")), type + " fields must export facing");
                var encoded = codec.encodeStart(JsonOps.INSTANCE, current.objects()).result().orElseThrow();
                require(codec.parse(JsonOps.INSTANCE, encoded).result().orElseThrow().equals(current.objects()),
                        type + " map JSON must preserve " + direction);
            }
            var duplicate = edit(current.objects(), ZombiesDeployObjectEditor.Operation.DUPLICATE, type, 0, Map.of());
            requireSuccess(duplicate, type + " directional duplicate");
            require(boxFacing(duplicate.objects(), type, 1) == Direction.WEST, type + " copy must retain facing");

            var legacy = codec.encodeStart(JsonOps.INSTANCE, current.objects()).result().orElseThrow().getAsJsonObject();
            legacy.getAsJsonArray(entry.getValue()).get(0).getAsJsonObject().remove("facing");
            require(boxFacing(codec.parse(JsonOps.INSTANCE, legacy).result().orElseThrow(), type, 0) == Direction.NORTH,
                    type + " old maps without facing must remain north-facing");
            var legacyObject = legacy.getAsJsonArray(entry.getValue()).get(0).getAsJsonObject();
            require(boxCodec(type).parse(JsonOps.INSTANCE, legacyObject).result().isPresent(),
                    type + " object codec must accept legacy missing facing");
            for (JsonPrimitive invalid : List.of(new JsonPrimitive("up"), new JsonPrimitive("down"),
                    new JsonPrimitive("diagonal"), new JsonPrimitive(1))) {
                var invalidObject = legacyObject.deepCopy();
                invalidObject.add("facing", invalid);
                require(boxCodec(type).parse(JsonOps.INSTANCE, invalidObject).error().isPresent(),
                        type + " object codec must reject explicit invalid facing: " + invalid);
            }
            for (String invalid : List.of("up", "down", "diagonal", "")) {
                var invalidFields = new LinkedHashMap<>(current.fields());
                invalidFields.put("facing", invalid);
                var rejected = edit(current.objects(), ZombiesDeployObjectEditor.Operation.UPDATE, type, 0, invalidFields);
                requireFailure(rejected, "field.invalid_facing", type + " must reject " + invalid);
                require(rejected.objects() == current.objects(), "invalid facing must preserve all object data");
            }
        }
    }

    private static Direction boxFacing(ZombiesMapObjects objects, String type, int index) {
        return switch (type) {
            case ZombiesDeployFieldSchema.WEAPON_WALL -> objects.weaponWalls().get(index).facing();
            case ZombiesDeployFieldSchema.AMMO_BOX -> objects.ammoBoxes().get(index).facing();
            case ZombiesDeployFieldSchema.ARMOR_STATION -> objects.armorStations().get(index).facing();
            case ZombiesDeployFieldSchema.SODA_MACHINE -> objects.sodaMachines().get(index).facing();
            case ZombiesDeployFieldSchema.ULTIMATE_MACHINE -> objects.ultimateMachines().get(index).facing();
            case ZombiesDeployFieldSchema.MYSTERY_BOX -> objects.mysteryBoxes().get(index).facing();
            default -> throw new AssertionError("Unexpected box type " + type);
        };
    }

    private static Codec<?> boxCodec(String type) {
        return switch (type) {
            case ZombiesDeployFieldSchema.WEAPON_WALL -> ZombiesWeaponWallData.CODEC;
            case ZombiesDeployFieldSchema.AMMO_BOX -> ZombiesAmmoBoxData.CODEC;
            case ZombiesDeployFieldSchema.ARMOR_STATION -> ZombiesArmorStationData.CODEC;
            case ZombiesDeployFieldSchema.SODA_MACHINE -> ZombiesSodaMachineData.CODEC;
            case ZombiesDeployFieldSchema.ULTIMATE_MACHINE -> ZombiesUltimateMachineData.CODEC;
            case ZombiesDeployFieldSchema.MYSTERY_BOX -> ZombiesMysteryBoxData.CODEC;
            default -> throw new AssertionError("Unexpected box type " + type);
        };
    }

    public static void initialSpawnsSupportGroupsAndPersistence() {
        ZombiesMapObjects objects = ZombiesMapObjects.EMPTY;
        for (int i = 0; i < 12; i++) {
            ZombiesDeployObjectEditor.EditResult add = edit(
                    objects,
                    ZombiesDeployObjectEditor.Operation.ADD,
                    ZombiesDeployFieldSchema.INITIAL,
                    -1,
                    fields(ZombiesDeployFieldSchema.INITIAL,
                            "group", Integer.toString(i / 4),
                            "posX", Integer.toString(i + 1),
                            "posY", "64",
                            "posZ", "1"));
            requireSuccess(add, "INITIAL add " + (i + 1) + " should succeed");
            require(add.objects().initialSpawns().size() == i + 1,
                    "INITIAL add should support points throughout multiple areas");
            require(add.selectedIndex() == i, "INITIAL add should select the inserted point");
            ZombiesInitialSpawnData added = add.objects().initialSpawns().get(i);
            require(added.pos().equals(new BlockPos(i + 1, 64, 1)), "INITIAL position should parse");
            require(added.group() == i / 4, "INITIAL must preserve the selected player spawn group");
            require(Integer.toString(i / 4).equals(add.fields().get("group")), "INITIAL fields must round-trip group");
            objects = add.objects();
        }

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                objects,
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.INITIAL,
                8,
                Map.of());
        requireSuccess(duplicate, "INITIAL duplicate above four total points should succeed");
        require(duplicate.objects().initialSpawns().size() == 13, "INITIAL duplicate should append a point");
        require(duplicate.objects().initialSpawns().get(12).group() == 2, "INITIAL duplication must retain group");
        require("2".equals(ZombiesDeployObjectEditor.fieldsForSnapshotSelection(
                duplicate.objects(), ZombiesDeployFieldSchema.INITIAL, 12).get("group")),
                "selection snapshots must retain the player spawn group");

        Map<String, String> changed = new LinkedHashMap<>(duplicate.fields());
        changed.put("group", "7");
        var updated = edit(duplicate.objects(), ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.INITIAL, 12, changed);
        requireSuccess(updated, "INITIAL group must be editable");
        require(updated.objects().initialSpawns().get(12).group() == 7, "INITIAL update must change group");
        var codec = ZombiesMapObjects.CODEC.codec();
        var encoded = codec.encodeStart(JsonOps.INSTANCE, updated.objects()).result().orElseThrow();
        require(codec.parse(JsonOps.INSTANCE, encoded).result().orElseThrow().equals(updated.objects()),
                "player groups must survive map JSON persistence");
        var legacy = ZombiesInitialSpawnData.CODEC.encodeStart(JsonOps.INSTANCE,
                updated.objects().initialSpawns().get(12)).result().orElseThrow().getAsJsonObject();
        legacy.remove("group");
        var oldPoint = ZombiesInitialSpawnData.CODEC.parse(JsonOps.INSTANCE, legacy).result().orElseThrow();
        require(oldPoint.group() == 0, "legacy player spawn JSON must default to group zero");
        require(new ZombiesInitialSpawnData(oldPoint.dimension(), oldPoint.pos(), oldPoint.yaw(), oldPoint.pitch()).group() == 0,
                "legacy constructor must default to group zero");

        for (String invalid : List.of("-1", "1.5", "invalid", "")) {
            changed.put("group", invalid);
            var rejected = edit(updated.objects(), ZombiesDeployObjectEditor.Operation.UPDATE,
                    ZombiesDeployFieldSchema.INITIAL, 12, changed);
            requireFailure(rejected, "field.invalid_integer", "INITIAL must reject invalid player group: " + invalid);
            require(rejected.objects() == updated.objects(), "invalid group must preserve the original points");
        }
        for (String ruleField : List.of("playerSpawnGroupChanges", "enablePlayerSpawnGroups", "disablePlayerSpawnGroups")) {
            var barrierFields = fields(ZombiesDeployFieldSchema.BARRIER, ruleField, "2");
            var rejected = edit(updated.objects(), ZombiesDeployObjectEditor.Operation.ADD,
                    ZombiesDeployFieldSchema.BARRIER, -1, barrierFields);
            requireFailure(rejected, "field.barrier_rules_file_only", "player spawn actions must remain file-only");
            require(rejected.objects() == updated.objects(), "rejected barrier rule fields must preserve objects");
        }
    }

    private static boolean bootstrapMinecraft() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            return true;
        } catch (Throwable throwable) {
            System.err.println("Skipping ZombiesDeployObjectEditorCompatTest outside a bootstrapped Minecraft runtime: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private static void barrierAreaFieldsParseUpdateAndDuplicate() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.BARRIER,
                -1,
                fields(ZombiesDeployFieldSchema.BARRIER,
                        "objectId", "barrier-2-a",
                        "group", "2",
                        "cost", "750",
                        "blocksPlayersOnly", "true",
                        "requiredItem", "minecraft:tripwire_hook{CustomModelData:1}",
                        "areaFromX", "5",
                        "areaFromY", "64",
                        "areaFromZ", "5",
                        "areaToX", "7",
                        "areaToY", "66",
                        "areaToZ", "5",
                        "interactionX", "6",
                        "interactionY", "65",
                        "interactionZ", "4"));

        requireSuccess(add, "barrier add should succeed");
        require(add.selectedIndex() == 0, "barrier add should select inserted object");
        ZombiesBarrierData added = only(add.objects().barriers(), "added barrier");
        requireBarrier(added, "barrier-2-a", 2, 750, true,
                new BlockPos(5, 64, 5), new BlockPos(7, 66, 5), new BlockPos(6, 65, 4));
        require(added.requiredItem().contains("id:\"minecraft:tripwire_hook\"")
                        && added.requiredItem().contains("Count:1b")
                        && added.requiredItem().contains("CustomModelData:1"),
                "barrier add should normalize required item to count-one ItemStack SNBT");

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("objectId", "barrier-3-a");
        updateFields.put("group", "3");
        updateFields.put("cost", "950");
        updateFields.put("blocksPlayersOnly", "false");
        updateFields.put("areaToZ", "8");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.BARRIER,
                0,
                updateFields);

        requireSuccess(update, "barrier update should succeed");
        ZombiesBarrierData updated = only(update.objects().barriers(), "updated barrier");
        requireBarrier(updated, "barrier-3-a", 3, 950, false,
                new BlockPos(5, 64, 5), new BlockPos(7, 66, 8), new BlockPos(6, 65, 4));
        require(added.requiredItem().equals(updated.requiredItem()),
                "barrier update should preserve normalized required item");

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                update.objects(),
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.BARRIER,
                0,
                Map.of());

        requireSuccess(duplicate, "barrier duplicate should succeed");
        require(duplicate.objects().barriers().size() == 2, "barrier duplicate should append one object");
        require("barrier-3-a_copy".equals(duplicate.objects().barriers().get(1).objectId()),
                "barrier duplicate should generate non-conflicting object id");
        require(duplicate.objects().barriers().get(1).areaTo().equals(new BlockPos(7, 66, 8)),
                "barrier duplicate should preserve area bounds");
        require(updated.requiredItem().equals(duplicate.objects().barriers().get(1).requiredItem()),
                "barrier duplicate should preserve required item");

        Map<String, String> invalidFields = new LinkedHashMap<>(add.fields());
        invalidFields.put("requiredItem", "minecraft:not_a_real_item");
        ZombiesDeployObjectEditor.EditResult invalid = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.BARRIER,
                0,
                invalidFields);
        requireFailure(invalid, "field.invalid_item", "invalid barrier required item should fail save");
        require(invalid.objects() == add.objects(), "invalid required item should not mutate objects");
    }

    private static void ammoBoxPricesByWeaponLevelParseAndUpdateFromListField() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.AMMO_BOX,
                -1,
                fields(ZombiesDeployFieldSchema.AMMO_BOX,
                        "objectId", "ammo-1",
                        "pricesByWeaponLevel", "1=0;2=250,3=500",
                        "posX", "2",
                        "posY", "64",
                        "posZ", "8"));

        requireSuccess(add, "ammo_box add should succeed");
        require(add.selectedIndex() == 0, "ammo_box add should select the inserted object");
        ZombiesAmmoBoxData added = only(add.objects().ammoBoxes(), "added ammo box");
        require("ammo-1".equals(added.objectId()), "ammo_box objectId should parse");
        requireMapValue(added.pricesByWeaponLevel(), "1", 0, "ammo_box level 1 price should parse");
        requireMapValue(added.pricesByWeaponLevel(), "2", 250, "ammo_box level 2 price should parse");
        requireMapValue(added.pricesByWeaponLevel(), "3", 500, "ammo_box level 3 price should parse");

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("pricesByWeaponLevel", "1=0;2=300;4=900");
        updateFields.put("posZ", "9");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.AMMO_BOX,
                0,
                updateFields);

        requireSuccess(update, "ammo_box update should succeed");
        require(update.selectedIndex() == 0, "ammo_box update should keep selected index");
        ZombiesAmmoBoxData updated = only(update.objects().ammoBoxes(), "updated ammo box");
        require("ammo-1".equals(updated.objectId()), "ammo_box update should retain unchanged objectId");
        requireMapValue(updated.pricesByWeaponLevel(), "2", 300, "ammo_box update should change level 2 price");
        requireMapValue(updated.pricesByWeaponLevel(), "4", 900, "ammo_box update should add level 4 price");
        require(updated.pos().getZ() == 9, "ammo_box update should change requested position field");
    }

    private static void armorStationFieldsParseAndUpdate() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.ARMOR_STATION,
                -1,
                fields(ZombiesDeployFieldSchema.ARMOR_STATION,
                        "objectId", "armor-2",
                        "armorLevel", "2",
                        "buyCost", "750",
                        "posX", "4",
                        "posY", "64",
                        "posZ", "9"));

        requireSuccess(add, "armor_station add should succeed");
        ZombiesArmorStationData added = only(add.objects().armorStations(), "added armor station");
        requireArmorStation(added, "armor-2", 2, 750, 1.0D, new BlockPos(4, 64, 9));

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("armorLevel", "3");
        updateFields.put("buyCost", "1200");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.ARMOR_STATION,
                0,
                updateFields);

        requireSuccess(update, "armor_station update should succeed");
        ZombiesArmorStationData updated = only(update.objects().armorStations(), "updated armor station");
        requireArmorStation(updated, "armor-2", 3, 1200, 1.0D, new BlockPos(4, 64, 9));
    }

    private static void armorStationRejectsInvalidLevel() {
        ZombiesDeployObjectEditor.EditResult invalidLevel = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.ARMOR_STATION,
                -1,
                fields(ZombiesDeployFieldSchema.ARMOR_STATION,
                        "objectId", "armor-4",
                        "armorLevel", "4",
                        "buyCost", "750"));

        requireFailure(invalidLevel, "field.invalid_armor_level", "armor_station invalid armorLevel should fail");
        require(invalidLevel.objects().armorStations().isEmpty(), "invalid armorLevel should not append armor station");
    }

    private static void powerSwitchSingleObjectCrudKeepsFieldValues() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.POWER_SWITCH,
                -1,
                fields(ZombiesDeployFieldSchema.POWER_SWITCH,
                        "objectId", "power-main",
                        "block", "codpattern:zombies_power_switch",
                        "cost", "1000",
                        "posX", "8",
                        "posY", "64",
                        "posZ", "8"));

        requireSuccess(add, "power_switch add should succeed");
        ZombiesPowerSwitchData power = add.objects().powerSwitch()
                .orElseThrow(() -> new AssertionError("power_switch should be present"));
        requirePowerSwitch(power, "power-main", "codpattern:zombies_power_switch", 1000, new BlockPos(8, 64, 8));

        ZombiesDeployObjectEditor.EditResult duplicateAdd = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.POWER_SWITCH,
                -1,
                fields(ZombiesDeployFieldSchema.POWER_SWITCH, "objectId", "power-other"));
        requireFailure(duplicateAdd, "object.single_exists", "second power_switch add should fail");
        require(duplicateAdd.objects() == add.objects(), "second power_switch failure should keep original objects");

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("cost", "1250");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.POWER_SWITCH,
                0,
                updateFields);

        requireSuccess(update, "power_switch update should succeed");
        requirePowerSwitch(update.objects().powerSwitch().orElseThrow(), "power-main",
                "codpattern:zombies_power_switch", 1250, new BlockPos(8, 64, 8));

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                update.objects(),
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.POWER_SWITCH,
                0,
                Map.of());
        requireFailure(duplicate, "object.single_type", "power_switch duplicate should fail");

        ZombiesDeployObjectEditor.EditResult delete = edit(
                update.objects(),
                ZombiesDeployObjectEditor.Operation.DELETE,
                ZombiesDeployFieldSchema.POWER_SWITCH,
                0,
                Map.of());
        requireSuccess(delete, "power_switch delete should succeed");
        require(delete.objects().powerSwitch().isEmpty(), "power_switch delete should remove single object");
        require(delete.selectedIndex() == -1, "power_switch delete should clear selected index");
    }

    private static void sodaMachineFieldsParseAndDuplicate() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.SODA_MACHINE,
                -1,
                fields(ZombiesDeployFieldSchema.SODA_MACHINE,
                        "objectId", "soda-health",
                        "buffId", "double_health",
                        "cost", "1500",
                        "requiresPower", "true",
                        "posX", "10",
                        "posY", "64",
                        "posZ", "8"));

        requireSuccess(add, "soda_machine add should succeed");
        ZombiesSodaMachineData added = only(add.objects().sodaMachines(), "added soda machine");
        requireSodaMachine(added, "soda-health", "double_health", 1500, true, new BlockPos(10, 64, 8));

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("buffId", "score_multiplier");
        updateFields.put("cost", "2000");
        updateFields.put("requiresPower", "false");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.SODA_MACHINE,
                0,
                updateFields);

        requireSuccess(update, "soda_machine update should succeed");
        ZombiesSodaMachineData updated = only(update.objects().sodaMachines(), "updated soda machine");
        requireSodaMachine(updated, "soda-health", "score_multiplier", 2000, false, new BlockPos(10, 64, 8));

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                update.objects(),
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.SODA_MACHINE,
                0,
                Map.of());

        requireSuccess(duplicate, "soda_machine duplicate should succeed");
        require(duplicate.objects().sodaMachines().size() == 2, "soda_machine duplicate should append one object");
        require("soda-health_copy".equals(duplicate.objects().sodaMachines().get(1).objectId()),
                "soda_machine duplicate should generate non-conflicting object id");
    }

    private static void ultimateMachineRuleFieldsIgnoredAndRemovedFromEditorFields() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.ULTIMATE_MACHINE,
                -1,
                fields(ZombiesDeployFieldSchema.ULTIMATE_MACHINE,
                        "objectId", "ultimate-1",
                        "maxUpgradeLevel", "3",
                        "levels", "1=2500:1.5;2=5000:2.0,3=7500:2.5",
                        "requiresPower", "false"));

        requireSuccess(add, "ultimate_machine add should succeed");
        var ultimate = only(add.objects().ultimateMachines(), "ultimate machine");
        require("ultimate-1".equals(ultimate.objectId()), "ultimate_machine objectId should parse");
        require(!ultimate.requiresPower(), "ultimate_machine requiresPower should parse");
        require(ultimate.maxUpgradeLevel() == 0,
                "ultimate_machine maxUpgradeLevel should stay serverconfig-owned");
        require(ultimate.levels().isEmpty(),
                "ultimate_machine levels should stay serverconfig-owned");
        require(!add.fields().containsKey("maxUpgradeLevel"),
                "ultimate_machine editor fields should not expose serverconfig maxUpgradeLevel");
        require(!add.fields().containsKey("levels"),
                "ultimate_machine editor fields should not expose serverconfig levels");
    }

    private static void duplicateDeleteAndClearKeepSelectionAndCountsStable() {
        ZombiesDeployObjectEditor.EditResult first = addAmmoBox(ZombiesMapObjects.EMPTY, "ammo-1");
        ZombiesDeployObjectEditor.EditResult second = addAmmoBox(first.objects(), "ammo-1_copy");
        ZombiesMapObjects objects = second.objects();

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                objects,
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.AMMO_BOX,
                0,
                Map.of());

        requireSuccess(duplicate, "ammo_box duplicate should succeed");
        require(duplicate.objects().ammoBoxes().size() == 3, "ammo_box duplicate should append one object");
        require(duplicate.selectedIndex() == 2, "ammo_box duplicate should select the copy");
        require("ammo-1_copy_2".equals(duplicate.objects().ammoBoxes().get(2).objectId()),
                "ammo_box duplicate should generate a non-conflicting objectId");

        ZombiesDeployObjectEditor.EditResult deleteMiddle = edit(
                duplicate.objects(),
                ZombiesDeployObjectEditor.Operation.DELETE,
                ZombiesDeployFieldSchema.AMMO_BOX,
                1,
                Map.of());

        requireSuccess(deleteMiddle, "ammo_box delete should succeed");
        require(deleteMiddle.objects().ammoBoxes().size() == 2, "ammo_box delete should remove one object");
        require(deleteMiddle.selectedIndex() == 1, "ammo_box delete should select the next object at the same index");
        require("ammo-1_copy_2".equals(deleteMiddle.objects().ammoBoxes().get(1).objectId()),
                "ammo_box delete should keep the next object selected");

        ZombiesDeployObjectEditor.EditResult deleteTail = edit(
                deleteMiddle.objects(),
                ZombiesDeployObjectEditor.Operation.DELETE,
                ZombiesDeployFieldSchema.AMMO_BOX,
                1,
                Map.of());

        requireSuccess(deleteTail, "ammo_box tail delete should succeed");
        require(deleteTail.objects().ammoBoxes().size() == 1, "ammo_box tail delete should remove one object");
        require(deleteTail.selectedIndex() == 0, "ammo_box tail delete should select previous object");

        ZombiesDeployObjectEditor.EditResult clear = edit(
                deleteTail.objects(),
                ZombiesDeployObjectEditor.Operation.CLEAR,
                ZombiesDeployFieldSchema.AMMO_BOX,
                0,
                Map.of());

        requireSuccess(clear, "ammo_box clear should succeed");
        require(clear.objects().ammoBoxes().isEmpty(), "ammo_box clear should remove all objects");
        require(clear.selectedIndex() == -1, "ammo_box clear should clear selected index");
        require(clear.affectedCount() == 1, "ammo_box clear should report removed object count");
    }

    private static void failurePathsKeepOriginalObjects() {
        ZombiesDeployObjectEditor.EditResult add = addAmmoBox(ZombiesMapObjects.EMPTY, "ammo-1");
        ZombiesMapObjects original = add.objects();

        ZombiesDeployObjectEditor.EditResult duplicateId = edit(
                original,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.AMMO_BOX,
                -1,
                fields(ZombiesDeployFieldSchema.AMMO_BOX,
                        "objectId", "ammo-1",
                        "pricesByWeaponLevel", "1=0"));

        requireFailure(duplicateId, "object.duplicate_id", "duplicate objectId should fail");
        require(duplicateId.objects() == original, "duplicate objectId failure should return original objects");
        require(original.ammoBoxes().size() == 1, "duplicate objectId failure should not append objects");
        require("ammo-1".equals(original.ammoBoxes().get(0).objectId()),
                "duplicate objectId failure should not modify existing objectId");

        Map<String, String> badListFields = new LinkedHashMap<>(add.fields());
        badListFields.put("pricesByWeaponLevel", "1=0;2=not_an_integer");
        ZombiesDeployObjectEditor.EditResult invalidList = edit(
                original,
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.AMMO_BOX,
                0,
                badListFields);

        requireFailure(invalidList, "field.invalid_integer", "invalid pricesByWeaponLevel list should fail");
        require(invalidList.objects() == original, "invalid LIST failure should return original objects");
        requireMapValue(original.ammoBoxes().get(0).pricesByWeaponLevel(), "1", 0,
                "invalid LIST failure should keep original parsed value");
        require(!original.ammoBoxes().get(0).pricesByWeaponLevel().containsKey("2"),
                "invalid LIST failure should not apply partially parsed entries");
    }

    private static void weaponWallDeprecatedFieldsIgnoredAndRemovedFromEditorFields() {
        ZombiesDeployObjectEditor.EditResult add = edit(
                ZombiesMapObjects.EMPTY,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.WEAPON_WALL,
                -1,
                fields(ZombiesDeployFieldSchema.WEAPON_WALL,
                        "objectId", "wall-1",
                        "weaponLevel", "2",
                        "levelDamageMultiplier", "1.25",
                        "price", "650",
                        "maxReserveAmmo", "210",
                        "refreshWaves", "1,4;7",
                        "rarityPools", "common=1,10.0,0.0;rare=2,1.5,0.25",
                        "weapons", "codpattern:pistol|common=1.0;codpattern:rifle|common=0.5,rare=2.0",
                        "posX", "5",
                        "posY", "64",
                        "posZ", "6"));

        requireSuccess(add, "weapon_wall add should succeed");
        require(add.selectedIndex() == 0, "weapon_wall add should select the inserted object");
        ZombiesWeaponWallData added = only(add.objects().weaponWalls(), "added weapon wall");
        requireWeaponWallBase(
                added,
                "wall-1",
                new BlockPos(5, 64, 6),
                new BlockPos(0, 64, 0));
        requireOldWeaponWallFieldsAbsent(add.fields());

        Map<String, String> updateFields = new LinkedHashMap<>(add.fields());
        updateFields.put("price", "725");
        updateFields.put("rarityPools", "common=1,9.0,0.0;epic=4,0.0,3.5");
        ZombiesDeployObjectEditor.EditResult update = edit(
                add.objects(),
                ZombiesDeployObjectEditor.Operation.UPDATE,
                ZombiesDeployFieldSchema.WEAPON_WALL,
                0,
                updateFields);

        requireSuccess(update, "weapon_wall update should succeed");
        ZombiesWeaponWallData updated = only(update.objects().weaponWalls(), "updated weapon wall");
        requireWeaponWallBase(
                updated,
                "wall-1",
                new BlockPos(5, 64, 6),
                new BlockPos(0, 64, 0));
        requireOldWeaponWallFieldsAbsent(update.fields());
    }

    private static void weaponWallDuplicateCreatesNonConflictingObjectId() {
        ZombiesDeployObjectEditor.EditResult first = addWeaponWall(ZombiesMapObjects.EMPTY, "wall-1");
        ZombiesDeployObjectEditor.EditResult second = addWeaponWall(first.objects(), "wall-1_copy");

        ZombiesDeployObjectEditor.EditResult duplicate = edit(
                second.objects(),
                ZombiesDeployObjectEditor.Operation.DUPLICATE,
                ZombiesDeployFieldSchema.WEAPON_WALL,
                0,
                Map.of());

        requireSuccess(duplicate, "weapon_wall duplicate should succeed");
        require(duplicate.objects().weaponWalls().size() == 3,
                "weapon_wall duplicate should append one object");
        require(duplicate.selectedIndex() == 2, "weapon_wall duplicate should select the copy");
        ZombiesWeaponWallData copy = duplicate.objects().weaponWalls().get(2);
        require("wall-1_copy_2".equals(copy.objectId()),
                "weapon_wall duplicate should generate a non-conflicting objectId");
        requireWeaponWallBase(
                copy,
                "wall-1_copy_2",
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0));
    }

    private static ZombiesDeployObjectEditor.EditResult addAmmoBox(ZombiesMapObjects objects, String objectId) {
        ZombiesDeployObjectEditor.EditResult result = edit(
                objects,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.AMMO_BOX,
                -1,
                fields(ZombiesDeployFieldSchema.AMMO_BOX,
                        "objectId", objectId,
                        "pricesByWeaponLevel", "1=0"));
        requireSuccess(result, "setup ammo_box should add " + objectId);
        return result;
    }

    private static ZombiesDeployObjectEditor.EditResult addWeaponWall(ZombiesMapObjects objects, String objectId) {
        ZombiesDeployObjectEditor.EditResult result = edit(
                objects,
                ZombiesDeployObjectEditor.Operation.ADD,
                ZombiesDeployFieldSchema.WEAPON_WALL,
                -1,
                fields(ZombiesDeployFieldSchema.WEAPON_WALL,
                        "objectId", objectId));
        requireSuccess(result, "setup weapon_wall should add " + objectId);
        return result;
    }

    private static ZombiesDeployObjectEditor.EditResult edit(
            ZombiesMapObjects objects,
            ZombiesDeployObjectEditor.Operation operation,
            String objectType,
            int selectedIndex,
            Map<String, String> fields
    ) {
        return ZombiesDeployObjectEditor.edit(objects, operation, objectType, selectedIndex, fields);
    }

    private static Map<String, String> fields(String objectType, String... pairs) {
        require(pairs.length % 2 == 0, "fields should be key/value pairs");
        Map<String, String> fields = new LinkedHashMap<>(ZombiesDeployFieldSchema.defaultFields(objectType));
        for (int index = 0; index < pairs.length; index += 2) {
            fields.put(pairs[index], pairs[index + 1]);
        }
        return fields;
    }

    private static <T> T only(List<T> values, String label) {
        require(values.size() == 1, label + " should have exactly one entry but had " + values.size());
        return values.get(0);
    }

    private static void requireWeaponWallBase(
            ZombiesWeaponWallData wall,
            String objectId,
            BlockPos pos,
            BlockPos interactionPos
    ) {
        require(objectId.equals(wall.objectId()), "weapon_wall objectId should match");
        require(pos.equals(wall.pos()), "weapon_wall pos should match");
        require(wall.interactionPos().orElse(wall.pos()).equals(interactionPos),
                "weapon_wall interaction position should match");
    }

    private static void requireOldWeaponWallFieldsAbsent(Map<String, String> fields) {
        for (String key : List.of(
                "weaponLevel",
                "levelDamageMultiplier",
                "price",
                "maxReserveAmmo",
                "refreshWaves",
                "rarityPools",
                "weapons")) {
            require(!fields.containsKey(key), "weapon_wall fields should not expose deprecated " + key);
        }
    }

    private static void requireBarrier(
            ZombiesBarrierData barrier,
            String objectId,
            int group,
            int cost,
            boolean blocksPlayersOnly,
            BlockPos areaFrom,
            BlockPos areaTo,
            BlockPos interaction
    ) {
        require(objectId.equals(barrier.objectId()), "barrier objectId should match");
        require(barrier.group() == group, "barrier group should match");
        require(barrier.cost() == cost, "barrier cost should match");
        require(barrier.blocksPlayersOnly() == blocksPlayersOnly, "barrier blocksPlayersOnly should match");
        require(areaFrom.equals(barrier.areaFrom()), "barrier areaFrom should match");
        require(areaTo.equals(barrier.areaTo()), "barrier areaTo should match");
        require(interaction.equals(barrier.interactionPos()), "barrier interaction position should match");
    }

    private static void requireArmorStation(
            ZombiesArmorStationData station,
            String objectId,
            int armorLevel,
            int buyCost,
            double damageTakenMultiplier,
            BlockPos pos
    ) {
        require(objectId.equals(station.objectId()), "armor_station objectId should match");
        require(station.armorLevel() == armorLevel, "armor_station armorLevel should match");
        require(station.buyCost() == buyCost, "armor_station buyCost should match");
        requireClose(station.damageTakenMultiplier(), damageTakenMultiplier,
                "armor_station damageTakenMultiplier should match");
        require(pos.equals(station.pos()), "armor_station position should match");
    }

    private static void requirePowerSwitch(
            ZombiesPowerSwitchData power,
            String objectId,
            String block,
            int cost,
            BlockPos pos
    ) {
        require(objectId.equals(power.objectId()), "power_switch objectId should match");
        require(block.equals(power.block()), "power_switch block should match");
        require(power.cost() == cost, "power_switch cost should match");
        require(pos.equals(power.pos()), "power_switch position should match");
    }

    private static void requireSodaMachine(
            ZombiesSodaMachineData soda,
            String objectId,
            String buffId,
            int cost,
            boolean requiresPower,
            BlockPos pos
    ) {
        require(objectId.equals(soda.objectId()), "soda_machine objectId should match");
        require(buffId.equals(soda.buffId()), "soda_machine buffId should match");
        require(soda.cost() == cost, "soda_machine cost should match");
        require(soda.requiresPower() == requiresPower, "soda_machine requiresPower should match");
        require(pos.equals(soda.pos()), "soda_machine position should match");
    }

    private static void requireMapValue(Map<String, Integer> values, String key, int expected, String message) {
        Integer actual = values.get(key);
        require(actual != null, message + ": missing key " + key);
        require(actual == expected, message + ": expected " + expected + " but was " + actual);
    }

    private static void requireSuccess(ZombiesDeployObjectEditor.EditResult result, String message) {
        require(result.success(), message + ": " + result.code() + " " + result.detail());
    }

    private static void requireFailure(ZombiesDeployObjectEditor.EditResult result, String expectedCode, String message) {
        require(!result.success(), message + ": expected failure");
        require(expectedCode.equals(result.code()),
                message + ": expected " + expectedCode + " but was " + result.code());
    }

    private static void requireClose(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 0.000001D,
                message + ": expected " + expected + " but was " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
