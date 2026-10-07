package com.cdp.codpattern.app.zombies.gametest;

import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-readable observations; absence of a measurement is never represented as zero. */
public final class ZombiesNavigationTestReport {
    public static final long SEED = Long.getLong("codpattern.zombies.navigationSeed", 1701L);
    public static final String ENGINE = System.getProperty("codpattern.zombies.navigationEngine", "layered");

    private ZombiesNavigationTestReport() { }

    public static void write(String scenario, Map<String, Object> observations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("engine", ENGINE);
        result.put("seed", SEED);
        result.put("java", System.getProperty("java.version"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("namespace", System.getProperty("forge.enabledGameTestNamespaces", "unreported"));
        result.putAll(observations);
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(result);
        System.out.println("NAVIGATION_RESULT " + scenario + " " + json);
        Path directory = Path.of(System.getProperty("codpattern.zombies.navigationResultsDirectory",
                "build/verification/navigation/" + ENGINE + "-" + scenario + "-" + SEED));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(scenario + ".json"), json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot archive navigation observations to " + directory, failure);
        }
    }
}
