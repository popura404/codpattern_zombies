package com.cdp.codpattern.app.zombies.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZombiesMapScopedConfigStaticContractCompatTest {
    private static final Path CONFIG_PATH =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/config/zombies/ZombiesConfigPaths.java");
    private static final Path RULES_REPOSITORY =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/config/zombies/ZombiesRulesRepository.java");
    private static final Path CONFIG_REPOSITORY =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/config/zombies/ZombiesConfigRepository.java");
    private static final Path RULES_CONFIG =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/config/zombies/ZombiesRulesConfig.java");
    private static final Path ZOMBIES_MAP =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/compat/fpsmatch/map/zombies/ZombiesMap.java");
    private static final Path STARTUP_VALIDATION =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesStartupValidationService.java");
    private static final Path STARTER_KIT =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesStarterKitDistributor.java");
    private static final Path SPAWN_SERVICE =
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesMobSpawnService.java");

    private ZombiesMapScopedConfigStaticContractCompatTest() {
    }

    public static void main(String[] args) throws IOException {
        String configPath = read(CONFIG_PATH);
        String rulesRepository = read(RULES_REPOSITORY);
        String configRepository = read(CONFIG_REPOSITORY);
        String rulesConfig = read(RULES_CONFIG);
        String zombiesMap = read(ZOMBIES_MAP);
        String startupValidation = read(STARTUP_VALIDATION);
        String starterKit = read(STARTER_KIT);
        String spawnService = read(SPAWN_SERVICE);

        requireContains(configPath,
                "storage.paths().rules(\"zombies\", mapName)",
                "map-scoped zombies configs must use the shared map rules path");
        requireContains(configPath,
                "public static Path zombiesMapRulesConfig(MinecraftServer server, String mapName)",
                "rules config path must be map-scoped");
        requireContains(configPath,
                "public static Path zombiesMapWaves(MinecraftServer server, String mapName)",
                "wave directory path must be map-scoped");
        requireContains(configPath,
                "public static Path zombiesMapBackpack(MinecraftServer server, String mapName)",
                "backpack config must use the map-scoped rules directory");
        requireAbsent(configPath,
                "zombies_backpack_config.json",
                "the retired global backpack filename must not be generated");
        requireContains(configPath,
                "storage.paths().rules(\"zombies\", mapName)",
                "map-scoped config paths must use the common name encoding");
        requireAbsent(configPath,
                "SERVER_ZOMBIES_RULES_CONFIG",
                "old global zombies rules config path must not remain");
        requireAbsent(configPath,
                "SERVER_ZOMBIES_WAVES",
                "old global zombies waves path must not remain");
        requireAbsent(configPath,
                "SERVER_ZOMBIES_BACKPACK",
                "old global zombies backpack path must not remain");
        requireAbsent(configPath,
                "SERVER_ZOMBIES_FILTER",
                "old global zombies weapon-filter path must not remain");

        requireContains(rulesRepository,
                "loadOrCreate(ZombiesConfigPaths.zombiesMapRulesConfig(server, mapName))",
                "rules repository must support map-scoped config loading");
        requireAbsent(rulesRepository,
                "loadOrCreate(MinecraftServer server) {\n        return loadOrCreate(ZombiesConfigPaths.",
                "rules repository must not retain the old global server loader");
        requireAbsent(rulesConfig, "getStarterWeapon()",
                "aggregate rules must not retain the obsolete starter weapon source");
        requireAbsent(rulesConfig, "AmmunitionPerMagazineMultiple",
                "aggregate rules must not retain legacy magazine multipliers");
        requireContains(configRepository, "ZombiesBackpackConfig.load(",
                "unified loader must load backpack rules through their preserving loader");
        requireAbsent(configRepository, "ZombiesWeaponFilter",
                "unified loader must not read retired filters");

        requireContains(zombiesMap,
                "loadStartupConfigs(serverLevel == null ? null : serverLevel.getServer());",
                "map instance construction must bootstrap map-scoped config files");
        requireContains(zombiesMap,
                "ZombiesConfigRepository.loadResult(server, mapName)",
                "map startup must load map-scoped rules");
        requireAbsent(zombiesMap, "ZombiesWeaponFilter",
                "map startup must not depend on retired filters");
        requireContains(zombiesMap,
                "ZombiesConfigPaths.zombiesMapWaves(server, mapName)",
                "map startup must generate/load map-scoped wave files");
        requireContains(zombiesMap,
                "new ZombiesStartupValidationService(\n                        ZombiesConfigPaths.zombiesMapWaves(server, getMapName()),\n                        this::rulesConfig,\n                        this::rulesValidationIssues)",
                "startup validation must read map-scoped waves and rules");
        requireContains(zombiesMap,
                "new ZombiesStarterKitDistributor(this::backpackConfig)",
                "starter weapon rules must use the map instance backpack");
        requireContains(zombiesMap,
                "() -> serverConfig().getWeaponWall()",
                "weapon wall offers must use the map instance rules");
        requireContains(zombiesMap,
                "() -> serverConfig().getWeaponRules()",
                "rarity damage must continue using map-scoped weapon rules");
        requireContains(zombiesMap,
                "() -> rulesConfig().getSpawnPointWeighting()",
                "spawn point weighting must use the map instance rules");
        requireContains(zombiesMap,
                "backpackConfig()",
                "map must expose its own backpack snapshot");

        requireContains(startupValidation,
                "Supplier<ZombiesRulesConfig> rulesSupplier",
                "startup validation must be able to use map-scoped wave defaults");
        requireContains(startupValidation,
                "Supplier<List<ZombiesValidationIssue>> rulesIssuesSupplier",
                "startup validation must be able to use map-scoped rules issues");
        requireContains(starterKit,
                "Supplier<ZombiesBackpackConfig>",
                "starter kit distributor must use injected map-scoped backpack rules");
        requireContains(starterKit,
                "backpackConfig().getStarterWeapon()",
                "starter kit distributor must read the new backpack starter weapon");
        requireContains(spawnService,
                "private final Supplier<ZombiesRulesConfig.SpawnPointWeighting> spawnPointWeightingSupplier;",
                "spawn service must use injected map-scoped spawn weighting");

        System.out.println("PASS zombies map-scoped config static contract compat");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static void requireContains(String text, String expected, String message) {
        if (!text.contains(expected)) {
            throw new AssertionError(message + ": missing `" + expected + "`");
        }
    }

    private static void requireAbsent(String text, String unexpected, String message) {
        if (text.contains(unexpected)) {
            throw new AssertionError(message + ": found `" + unexpected + "`");
        }
    }
}
