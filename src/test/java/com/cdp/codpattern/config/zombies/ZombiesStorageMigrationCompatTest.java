package com.cdp.codpattern.config.zombies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Migration validation must never call the default-writing runtime repositories. */
public final class ZombiesStorageMigrationCompatTest {
    private static final List<String> FILES = List.of("room.json", "weapon_rules.json", "weapon_wall.json", "mystery_box.json");
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zombies-migration-validation-");
        try {
            for (String file : FILES) Files.writeString(root.resolve(file), "{\"schemaVersion\":1}");
            ZombiesStorageMigration.validate(root);
            require(Files.readString(root.resolve("room.json")).equals("{\"schemaVersion\":1}"), "validation rewrote source");
            require(!Files.exists(root.resolve("backpack.json")), "preflight must not generate a missing backpack file");
            require(!Files.exists(root.resolve("weapon_filter.json")), "preflight must not require or generate a filter");
            for (String retired : List.of("weapon_filter.json", "zombies_weapon_filter.json")) {
                Files.writeString(root.resolve(retired), "{broken retired filter");
            }
            ZombiesStorageMigration.validate(root);
            for (String retired : List.of("weapon_filter.json", "zombies_weapon_filter.json")) {
                require(Files.readString(root.resolve(retired)).equals("{broken retired filter"),
                        "ignored legacy filter must remain byte-for-byte unchanged");
            }
            Path backpack = root.resolve("backpack.json");
            Files.writeString(backpack, ZombiesBackpackConfig.DEFAULT_TEMPLATE);
            ZombiesStorageMigration.validate(root);
            require(Files.readString(backpack).equals(ZombiesBackpackConfig.DEFAULT_TEMPLATE), "backpack validation rewrote source");
            Files.writeString(backpack, ZombiesBackpackConfig.DEFAULT_TEMPLATE.replace("\"count\": 1", "\"count\": 0"));
            rejects(root);
            require(Files.readString(backpack).contains("\"count\": 0"), "invalid backpack must be preserved");
            Files.writeString(backpack, ZombiesBackpackConfig.DEFAULT_TEMPLATE
                    .replace("tacz:modern_kinetic_gun", "Not A Resource ID")
                    .replace("\"attachmentPreset\": \"\"", "\"attachmentPreset\": \"not SNBT\""));
            ZombiesStorageMigration.validate(root);
            Files.writeString(backpack, "{\"schemaVersion\":1}");
            rejects(root);
            Files.delete(backpack);
            Files.writeString(root.resolve("room.json"), "{\"schemaVersion\":1,\"room\":\"invalid\"}");
            rejects(root);
            require(Files.readString(root.resolve("room.json")).contains("invalid"), "malformed source replaced");
            Files.delete(root.resolve("room.json")); rejects(root);
            require(!Files.exists(root.resolve("room.json")), "missing source replaced with default");
            Files.writeString(root.resolve("room.json"), "{\"schemaVersion\":2}"); rejects(root);
            Files.writeString(root.resolve("room.json"), "{\"schemaVersion\":1}");
            Files.createDirectory(root.resolve("wavetext")); rejects(root); Files.delete(root.resolve("wavetext"));
            Files.createDirectory(root.resolve("waves"));
            Files.writeString(root.resolve("waves/wave_001.json"), "[]"); rejects(root);
            System.out.println("PASS Zombies migration validation (read-only, optional backpack, ignored filters, numeric rules, active files, waves)");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private static void rejects(Path root) {
        try { ZombiesStorageMigration.validate(root); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError("Invalid legacy rules were accepted");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
