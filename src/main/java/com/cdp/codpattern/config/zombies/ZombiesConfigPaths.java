package com.cdp.codpattern.config.zombies;

import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/** Addon-owned filenames over the shared world-local map storage. */
public final class ZombiesConfigPaths {
    private static final String ZOMBIES_ROOM_FILE = "room.json";
    private static final String ZOMBIES_WEAPON_RULES_FILE = "weapon_rules.json";
    private static final String ZOMBIES_WEAPON_WALL_FILE = "weapon_wall.json";
    private static final String ZOMBIES_WAVES_DIRECTORY = "waves";
    // Kept as a source-compatible alias for the pre-v1 path.  New maps use
    // wave_text; callers of the old constant are not allowed to influence
    // v1 loading.
    private static final String ZOMBIES_WAVE_TEXT_DIRECTORY = "wavetext";
    private static final String ZOMBIES_WAVE_TEXT_DIRECTORY_V1 = "wave_text";
    private static final String ZOMBIES_WEAPON_FILTER_FILE = "weapon_filter.json";
    private static final String ZOMBIES_MYSTERY_BOX_FILE = "mystery_box.json";

    private ZombiesConfigPaths() {
    }

    public static Path zombiesMapRulesRoot(MinecraftServer server, String mapName) {
        var storage = com.cdp.codpattern.config.storage.ServerMapStorage.get(server);
        storage.requireAvailable("zombies");
        if (storage.migration().blocksMap("zombies", mapName)) throw new IllegalStateException("Zombies rules require migration");
        Path root = storage.paths().rules("zombies", mapName);
        try { com.cdp.codpattern.config.storage.StorageFiles.checkPath(root); }
        catch (java.io.IOException e) { throw new IllegalStateException("Unsafe Zombies rules directory", e); }
        return root;
    }

    public static Path zombiesMapRulesConfig(MinecraftServer server, String mapName) {
        // Legacy path.  It is intentionally kept for compatibility and is no
        // longer read by the unified repository.
        return zombiesMapRulesRoot(server, mapName).resolve("config.json");
    }

    public static Path zombiesMapRoom(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_ROOM_FILE);
    }

    public static Path zombiesMapWeaponRules(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_WEAPON_RULES_FILE);
    }

    public static Path zombiesMapWeaponWall(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_WEAPON_WALL_FILE);
    }

    public static Path zombiesMapWaves(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_WAVES_DIRECTORY);
    }

    public static Path zombiesMapWaveText(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_WAVE_TEXT_DIRECTORY_V1);
    }

    public static Path zombiesMapWeaponFilter(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_WEAPON_FILTER_FILE);
    }

    public static Path zombiesMapBarrierGroups(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZombiesBarrierGroupsConfig.FILE_NAME);
    }

    public static Path zombiesMapMysteryBox(MinecraftServer server, String mapName) {
        return zombiesMapRulesRoot(server, mapName).resolve(ZOMBIES_MYSTERY_BOX_FILE);
    }

    public static String safeMapConfigName(String mapName) {
        String value = mapName == null ? "" : mapName.trim();
        if (value.isEmpty()) {
            return "default";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < 32
                    || ch == '/'
                    || ch == '\\'
                    || ch == ':'
                    || ch == '*'
                    || ch == '?'
                    || ch == '"'
                    || ch == '<'
                    || ch == '>'
                    || ch == '|') {
                builder.append('_');
            } else {
                builder.append(ch);
            }
        }
        String sanitized = builder.toString().trim();
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "default";
        }
        return sanitized;
    }
}
