package com.cdp.codpattern.config.zombies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads the five v1 files independently. A bad file never invalidates its siblings. */
public final class ZombiesConfigRepository {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ZombiesServerConfig current;
    private static LoadResult lastResult;

    private ZombiesConfigRepository() {}

    public static ZombiesServerConfig loadOrCreate(MinecraftServer server, String mapName) {
        return loadResult(server, mapName).config();
    }

    public static ZombiesServerConfig loadOrCreate(Path root) { return loadResult(root).config(); }
    public static ZombiesServerConfig loadOrCreate(Path root, String mapName) { return loadResult(root, mapName).config(); }

    public static LoadResult loadResult(MinecraftServer server, String mapName) {
        if (server == null) return loadResult(Path.of("serverconfig", "codpattern", "zombies_rules", ZombiesConfigPaths.safeMapConfigName(mapName)), mapName);
        return loadResult(ZombiesConfigPaths.zombiesMapRulesRoot(server, mapName), mapName);
    }

    public static LoadResult loadResult(Path root) { return loadResult(root, root == null ? "default" : root.getFileName().toString()); }

    public static synchronized LoadResult loadResult(Path root, String mapName) {
        Path resolved = root == null ? Path.of("serverconfig", "codpattern", "zombies_rules", "default") : root;
        List<FileStatus> statuses = new ArrayList<>();
        ZombiesRoomConfig room = read(resolved.resolve("room.json"), ZombiesRoomConfig.class, ZombiesRoomConfig::defaults, statuses);
        ZombiesWeaponRulesConfig weaponRules = read(resolved.resolve("weapon_rules.json"), ZombiesWeaponRulesConfig.class, ZombiesWeaponRulesConfig::defaults, statuses);
        ZombiesWeaponWallConfig weaponWall = read(resolved.resolve("weapon_wall.json"), ZombiesWeaponWallConfig.class, ZombiesWeaponWallConfig::defaults, statuses);
        ZombiesMysteryBoxConfig mysteryBox = read(resolved.resolve("mystery_box.json"), ZombiesMysteryBoxConfig.class, ZombiesMysteryBoxConfig::defaults, statuses);
        ZombiesWeaponFilterConfig weaponFilter = read(resolved.resolve("weapon_filter.json"), ZombiesWeaponFilterConfig.class, ZombiesWeaponFilterConfig::defaults, statuses);
        List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> issues = new ArrayList<>(new ZombiesRulesValidator().validate(
                new ZombiesServerConfig(mapName, room, weaponRules, weaponWall, mysteryBox, weaponFilter, List.of()).legacyRulesConfig()));
        ZombiesServerConfig resultConfig = new ZombiesServerConfig(mapName, room, weaponRules, weaponWall, mysteryBox, weaponFilter, issues);
        lastResult = new LoadResult(resultConfig, List.copyOf(statuses)); current = resultConfig; return lastResult;
    }

    public static ZombiesServerConfig getConfig() { return current == null ? (current = ZombiesServerConfig.defaults("default")) : current; }
    public static void setConfig(ZombiesServerConfig config) { current = config; }
    public static LoadResult getLastResult() { return lastResult; }
    public static List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> getLastValidationIssues() {
        return current == null ? List.of() : current.validationIssues();
    }

    private interface Factory<T> { T create(); }
    private static <T> T read(Path path, Class<T> type, Factory<T> factory, List<FileStatus> statuses) {
        boolean rebuild = false; String reason = "loaded"; T value = null;
        try {
            if (!Files.exists(path)) { rebuild = true; reason = "missing"; }
            else {
                JsonElement tree = JsonParser.parseString(Files.readString(path));
                if (!tree.isJsonObject() || !tree.getAsJsonObject().has("schemaVersion") || tree.getAsJsonObject().get("schemaVersion").getAsInt() != 1) { rebuild = true; reason = "unsupported_schema"; }
                else { value = GSON.fromJson(tree, type); if (value == null) { rebuild = true; reason = "empty"; } }
            }
        } catch (Exception ex) { rebuild = true; reason = "corrupt"; LOGGER.warn("Failed to load Zombies config {}", path, ex); }
        if (rebuild) { value = factory.create(); try { Files.createDirectories(path.getParent()); Files.writeString(path, GSON.toJson(value)); } catch (IOException ex) { LOGGER.error("Failed to write Zombies config {}", path, ex); } }
        normalize(value); statuses.add(new FileStatus(path.getFileName().toString(), rebuild, reason)); return value;
    }
    private static void normalize(Object value) { if (value instanceof ZombiesRoomConfig v) v.normalize(); else if (value instanceof ZombiesWeaponRulesConfig v) v.normalize(); else if (value instanceof ZombiesWeaponWallConfig v) v.normalize(); else if (value instanceof ZombiesMysteryBoxConfig v) v.normalize(); else if (value instanceof ZombiesWeaponFilterConfig v) v.normalize(); }

    public record FileStatus(String fileName, boolean rebuilt, String reason) {}
    public record LoadResult(ZombiesServerConfig config, List<FileStatus> files) {
        public ZombiesServerConfig serverConfig() { return config; }
        public boolean anyRebuilt() { return files.stream().anyMatch(FileStatus::rebuilt); }
        public List<com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue> validationIssues() { return config.validationIssues(); }
    }
}
