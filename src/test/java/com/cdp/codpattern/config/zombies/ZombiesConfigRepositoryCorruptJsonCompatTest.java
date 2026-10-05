package com.cdp.codpattern.config.zombies;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ZombiesConfigRepositoryCorruptJsonCompatTest {
    public static void main(String[] args) throws Exception {
        malformedRulesConfigFallsBackToGeneratedDefault();
        rulesConfigMissingStarterWeaponDoesNotBackfill();
        malformedRetiredFiltersAreIgnoredAndPreserved();
    }

    private static void malformedRulesConfigFallsBackToGeneratedDefault() throws Exception {
        Path path = tempFile("zombies-corrupt-rules-", "config.json");
        Files.writeString(path, "{\"defaults\": \"bad-shape\"}");

        ZombiesRulesConfig config = ZombiesRulesRepository.loadOrCreate(path);

        require(config.getDefaults() != null, "corrupt rules config should fall back to defaults");
        require(config.getArmor() != null, "corrupt rules config should include armor defaults");
        require(
                Files.readString(path).contains("\"armor\""),
                "corrupt rules config should be replaced with generated JSON");
        require(
                !Files.readString(path).contains("\"starterWeapon\"")
                        && !Files.readString(path).contains("\"weaponRules\""),
                "legacy config generation must not include retired starting equipment or ammo rules");
    }

    private static void rulesConfigMissingStarterWeaponDoesNotBackfill() throws Exception {
        Path path = tempFile("zombies-rules-missing-starter-", "config.json");
        Files.writeString(path, """
                {
                  "room": {
                    "intermissionSeconds": 5
                  }
                }
                """);

        String before = Files.readString(path);
        ZombiesRulesConfig config = ZombiesRulesRepository.loadOrCreate(path);
        require(config.getRoom().getIntermissionSeconds() == 5, "active legacy rules should remain readable");
        require(before.equals(Files.readString(path)), "legacy rules must not backfill starterWeapon");
    }

    private static void malformedRetiredFiltersAreIgnoredAndPreserved() throws Exception {
        Path root = Files.createTempDirectory("zombies-corrupt-retired-filters-");
        String corrupt = "{not valid JSON";
        for (String name : new String[]{"weapon_filter.json", "zombies_weapon_filter.json"}) {
            Files.writeString(root.resolve(name), corrupt);
        }
        ZombiesConfigRepository.LoadResult result = ZombiesConfigRepository.loadResult(root);
        require(result.config().getBackpack().errors().isEmpty(), "retired filters must not affect new backpack rules");
        for (String name : new String[]{"weapon_filter.json", "zombies_weapon_filter.json"}) {
            require(corrupt.equals(Files.readString(root.resolve(name))), "retired filters must remain untouched");
            require(result.files().stream().noneMatch(file -> file.fileName().equals(name)),
                    "retired filters must not be loaded or reported as rebuilt");
        }
    }

    private static Path tempFile(String prefix, String fileName) throws Exception {
        Path dir = Files.createTempDirectory(prefix);
        return dir.resolve(fileName);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private ZombiesConfigRepositoryCorruptJsonCompatTest() {
    }
}
