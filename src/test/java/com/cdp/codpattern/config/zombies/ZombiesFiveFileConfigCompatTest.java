package com.cdp.codpattern.config.zombies;

import java.nio.file.Files;
import java.nio.file.Path;

/** Executable compatibility checks for the map configuration, including the replacement backpack file. */
public final class ZombiesFiveFileConfigCompatTest {
    public static void main(String[] args) throws Exception {
        generatesFiveVersionedFiles();
        rebuildsOnlyTheBrokenFile();
        isolatesMapInstancesAndSharesRarityDamage();
    }

    private static void generatesFiveVersionedFiles() throws Exception {
        Path root = Files.createTempDirectory("zombies-v1-five-");
        ZombiesServerConfig config = ZombiesConfigRepository.loadOrCreate(root, "alpha");
        require(config.getRoom().getSchemaVersion() == 1, "room schema");
        for (String name : new String[]{"room.json", "weapon_rules.json", "weapon_wall.json", "mystery_box.json", "backpack.json"}) {
            require(Files.readString(root.resolve(name)).contains("\"schemaVersion\": 1"), name + " schemaVersion");
        }
        require(!Files.exists(root.resolve("config.json")), "legacy config.json must not be generated");
        require(!Files.exists(root.resolve("zombies_weapon_filter.json")), "legacy filter must not be generated");
        require(!Files.exists(root.resolve("weapon_filter.json")), "retired filter must not be generated");
        require(!Files.readString(root.resolve("weapon_wall.json")).contains("damageMultiplier"), "wall damage must be shared");
        require(!Files.readString(root.resolve("mystery_box.json")).contains("damageMultiplier"), "box damage must be shared");
        String weaponRules = Files.readString(root.resolve("weapon_rules.json"));
        require(!weaponRules.contains("starterWeapon") && !weaponRules.contains("ammunition"),
                "weapon_rules must not regenerate retired equipment fields");
        String backpack = Files.readString(root.resolve("backpack.json"));
        require(!backpack.contains("Magazine") && !backpack.contains("blocked") && !backpack.contains("weaponTabs"),
                "backpack template must only describe starting equipment and absolute reserve limits");
    }

    private static void rebuildsOnlyTheBrokenFile() throws Exception {
        Path root = Files.createTempDirectory("zombies-v1-corrupt-");
        ZombiesConfigRepository.loadOrCreate(root, "alpha");
        Path room = root.resolve("room.json"); Path wall = root.resolve("weapon_wall.json");
        String roomBefore = Files.readString(room); Files.writeString(wall, "{broken");
        ZombiesConfigRepository.LoadResult result = ZombiesConfigRepository.loadResult(root, "alpha");
        require(Files.readString(room).equals(roomBefore), "healthy sibling must not be rewritten");
        require(result.files().stream().filter(ZombiesConfigRepository.FileStatus::rebuilt).map(ZombiesConfigRepository.FileStatus::fileName).toList().equals(java.util.List.of("weapon_wall.json")), "only wall should rebuild");
    }

    private static void isolatesMapInstancesAndSharesRarityDamage() throws Exception {
        Path rootA = Files.createTempDirectory("zombies-v1-map-a-"); Path rootB = Files.createTempDirectory("zombies-v1-map-b-");
        ZombiesServerConfig a = ZombiesConfigRepository.loadOrCreate(rootA, "a"); ZombiesServerConfig b = ZombiesConfigRepository.loadOrCreate(rootB, "b");
        a.getWeaponRules().getRarities().stream().filter(r -> "common".equals(r.getId())).findFirst().orElseThrow().setDamageMultiplier(9.0);
        require(a.getWeaponRules().damageMultiplier("common").orElseThrow() == 9.0, "map A custom rarity");
        require(b.getWeaponRules().damageMultiplier("common").orElseThrow() == 1.0, "map B must remain default");
        require(a.getWeaponWall() != b.getWeaponWall(), "wall pools must be per-map objects");
        require(a.getMysteryBox() != b.getMysteryBox(), "box pools must be per-map objects");
        require(a.getBackpack() != b.getBackpack(), "backpack rules must be per-map objects");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private ZombiesFiveFileConfigCompatTest() {}
}
