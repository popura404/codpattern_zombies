package com.cdp.codpattern.config.zombies;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZombiesMysteryBoxRepository {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    private static ZombiesMysteryBoxConfig config;

    private ZombiesMysteryBoxRepository() { }

    public static ZombiesMysteryBoxConfig loadOrCreate(MinecraftServer server, String mapName) {
        return loadOrCreate(ZombiesConfigPaths.zombiesMapMysteryBox(server, mapName));
    }

    public static ZombiesMysteryBoxConfig loadOrCreate(Path path) {
        configPath = path;
        try {
            if (Files.exists(path)) {
                ZombiesMysteryBoxConfig loaded = GSON.fromJson(Files.readString(path), ZombiesMysteryBoxConfig.class);
                config = loaded == null ? ZombiesMysteryBoxConfig.defaults() : loaded;
                config.normalize();
                save(config);
                return config;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to load Zombies mystery box config: {}", path, e);
        }
        config = ZombiesMysteryBoxConfig.defaults();
        save(config);
        return config;
    }

    public static ZombiesMysteryBoxConfig getConfig() {
        if (config == null) config = ZombiesMysteryBoxConfig.defaults();
        return config;
    }

    public static void setConfig(ZombiesMysteryBoxConfig value) {
        config = value == null ? ZombiesMysteryBoxConfig.defaults() : value;
        config.normalize();
    }

    public static JsonSaveResult save(ZombiesMysteryBoxConfig value) {
        if (value == null || configPath == null) return JsonSaveResult.skipped(configPath, "Mystery box config path or value is missing.");
        try {
            value.normalize();
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(value));
            config = value;
            return JsonSaveResult.success(configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save Zombies mystery box config: {}", configPath, e);
            return JsonSaveResult.failure(configPath, "Failed to save Zombies mystery box config.", e);
        }
    }
}
