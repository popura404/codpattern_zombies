package com.cdp.codpattern.config.zombies;

import com.google.gson.JsonParser;
import com.cdp.codpattern.config.storage.StorageFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Addon-only preflight: relocating files never silently upgrades their schema. */
public final class ZombiesStorageMigration {
    private static final Map<String, Class<?>> RULE_TYPES = Map.of(
            "room.json", ZombiesRoomConfig.class,
            "weapon_rules.json", ZombiesWeaponRulesConfig.class,
            "weapon_wall.json", ZombiesWeaponWallConfig.class,
            "mystery_box.json", ZombiesMysteryBoxConfig.class);
    private ZombiesStorageMigration() {}
    public static void validate(Path root) {
        try {
            for (String name : RULE_TYPES.keySet()) {
                Path file = root.resolve(name);
                StorageFiles.checkPath(file);
                if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing v1 Zombies rules: " + file);
                var object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!object.has("schemaVersion") || object.get("schemaVersion").getAsInt() != 1) {
                    throw new IllegalStateException("Unsupported Zombies schema: " + file);
                }
                // Reject malformed field types before the runtime loader could replace them with defaults.
                new com.google.gson.Gson().fromJson(object, RULE_TYPES.get(name));
            }
            // Older map directories have no backpack rules. Existing files are checked without generating defaults.
            Path backpack = root.resolve(ZombiesBackpackConfig.FILE_NAME);
            StorageFiles.checkPath(backpack);
            if (Files.exists(backpack)) {
                var parsed = ZombiesBackpackConfig.parse(Files.readString(backpack), backpack);
                if (!parsed.errors().isEmpty()) throw new IllegalStateException("Invalid backpack rules " + backpack + ": " + parsed.errors());
            }
            // Missing new rules are configured manually after relocation; never infer legacy object values.
            Path barrierGroups = root.resolve(ZombiesBarrierGroupsConfig.FILE_NAME);
            StorageFiles.checkPath(barrierGroups);
            if (Files.exists(barrierGroups)) {
                var parsed = ZombiesBarrierGroupsConfig.parse(Files.readString(barrierGroups), barrierGroups);
                if (!parsed.errors().isEmpty()) throw new IllegalStateException("Invalid barrier group rules " + barrierGroups + ": " + parsed.errors());
            }
            // Retired filters are never read, including when they contain broken JSON.
            // Keep path checks even for these ignored files.
            try (var paths = Files.walk(root)) {
                for (Path file : paths.toList()) {
                    StorageFiles.checkPath(file);
                    if (file.equals(root.resolve("weapon_filter.json"))
                            || file.equals(root.resolve("zombies_weapon_filter.json"))) continue;
                    if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".json")) {
                        if (!JsonParser.parseString(Files.readString(file)).isJsonObject()) {
                            throw new IllegalStateException("Invalid Zombies JSON object: " + file);
                        }
                    }
                }
            }
            if (Files.exists(root.resolve("wavetext"))) {
                throw new IllegalStateException("Legacy wavetext requires manual conversion; expected wave_text: " + root);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot validate Zombies rules " + root, e); }
    }
}
