package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.ForgeGameTestHooks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Verifies the actual synthetic test world before any navigation observations are accepted. */
@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesNavigationTestEnvironment {
    private ZombiesNavigationTestEnvironment() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void verify(ServerStartedEvent event) {
        if (!ForgeGameTestHooks.isGametestServer()
                || !System.getProperty("forge.enabledGameTestNamespaces", "").startsWith("codpattern_navigation")) {
            return;
        }
        MinecraftServer server = event.getServer();
        ZombiesNavigationTestTiming.install(server);
        long actualSeed = server.overworld().getSeed();
        var generator = server.overworld().getChunkSource().getGenerator();
        boolean structures = server.getWorldData().worldGenOptions().generateStructures();
        String actualDifficulty = server.overworld().getDifficulty().name().toLowerCase(Locale.ROOT);
        String expectedDifficulty = System.getProperty("codpattern.zombies.navigationDifficulty", "easy");
        Map<String, String> manifest = readManifest();
        Map<String, Object> navigationClass = classEvidence(ZombiesGroundNavigationService.class, manifest);
        Map<String, Object> spawnClass = classEvidence(ZombiesMobSpawnService.class, manifest);
        boolean compiledClassesVerified = Boolean.TRUE.equals(navigationClass.get("matchesManifest"))
                && Boolean.TRUE.equals(spawnClass.get("matchesManifest"));
        boolean verified = actualSeed == ZombiesNavigationTestReport.SEED
                && generator instanceof FlatLevelSource && !structures && compiledClassesVerified
                && actualDifficulty.equals(expectedDifficulty);
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("actualWorldSeed", actualSeed);
        observations.put("actualOverworldGenerator", generator.getClass().getName());
        observations.put("generateStructures", structures);
        observations.put("actualWorldDifficulty", actualDifficulty);
        observations.put("expectedWorldDifficulty", expectedDifficulty);
        observations.put("difficultyScope", "Minecraft server Difficulty; separate from Zombies wave/rules difficulty");
        observations.put("expectedWorldSeed", ZombiesNavigationTestReport.SEED);
        observations.put("expectedOverworldGenerator", FlatLevelSource.class.getName());
        observations.put("verified", verified);
        observations.put("compiledClassesVerified", compiledClassesVerified);
        observations.put("loadedBusinessClassEvidence", Map.of(
                ZombiesGroundNavigationService.class.getName(), navigationClass,
                ZombiesMobSpawnService.class.getName(), spawnClass));
        observations.put("verificationPoint", "ServerStartedEvent HIGHEST, actual loaded ServerLevel and WorldData");
        ZombiesNavigationTestReport.write("world-environment", observations);
        if (!verified) {
            throw new IllegalStateException("Navigation GameTests require the requested seeded flat world without structures: "
                    + observations);
        }
    }

    private static Map<String, String> readManifest() {
        String location = System.getProperty("codpattern.zombies.navigationClassManifest");
        if (location == null) throw new IllegalStateException("Navigation verification requires its compiled class manifest");
        Map<String, String> hashes = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of(location))) {
                int separator = line.indexOf("  ");
                if (separator > 0) hashes.put(line.substring(separator + 2).replace('\\', '/'), line.substring(0, separator));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read navigation class manifest " + location, failure);
        }
        return hashes;
    }

    private static Map<String, Object> classEvidence(Class<?> type, Map<String, String> manifest) {
        String resource = type.getName().replace('.', '/') + ".class";
        String actual;
        try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (stream == null) throw new IllegalStateException("Cannot read loaded class resource " + resource);
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot hash loaded class resource " + resource, failure);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("expectedSha256", manifest.get(resource));
        evidence.put("actualResourceSha256", actual);
        evidence.put("matchesManifest", actual.equals(manifest.get(resource)));
        evidence.put("resourceLocation", String.valueOf(type.getResource(type.getSimpleName() + ".class")));
        var codeSource = type.getProtectionDomain().getCodeSource();
        evidence.put("codeSource", codeSource == null ? null : String.valueOf(codeSource.getLocation()));
        evidence.put("scope", "Untransformed class resource read through the actual loaded class; no reflection or extra class initialization");
        return evidence;
    }
}
