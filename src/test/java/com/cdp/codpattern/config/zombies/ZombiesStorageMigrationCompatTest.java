package com.cdp.codpattern.config.zombies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Migration validation must never call the default-writing runtime repositories. */
public final class ZombiesStorageMigrationCompatTest {
    private static final List<String> FILES = List.of("room.json", "weapon_rules.json", "weapon_wall.json", "weapon_filter.json", "mystery_box.json");
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zombies-migration-validation-");
        try {
            for (String file : FILES) Files.writeString(root.resolve(file), "{\"schemaVersion\":1}");
            ZombiesStorageMigration.validate(root);
            require(Files.readString(root.resolve("room.json")).equals("{\"schemaVersion\":1}"), "validation rewrote source");
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
            System.out.println("PASS Zombies migration validation (read-only, schema, field types, missing files, legacy text, waves)");
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
