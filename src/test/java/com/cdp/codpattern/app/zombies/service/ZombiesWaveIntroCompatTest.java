package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

/** Focused file parsing, sound asset and playback wiring checks; no client launch. */
public final class ZombiesWaveIntroCompatTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        Path temp = Files.createTempDirectory("zombies-wave-intro-");
        try {
            checkWave(temp, "omitted", "", true, false);
            checkWave(temp, "false", ",\"bossIntro\":false", true, false);
            checkWave(temp, "true", ",\"bossIntro\":true", true, true);
            checkWave(temp, "string", ",\"bossIntro\":\"true\"", false, false);
            checkWave(temp, "number", ",\"bossIntro\":1", false, false);
            checkWave(temp, "null", ",\"bossIntro\":null", false, false);
            checkWave(temp, "object", ",\"bossIntro\":{}", false, false);
            checkWave(temp, "array", ",\"bossIntro\":[]", false, false);
            var generated = new ZombiesWaveConfigRepository(temp.resolve("generated"), new ZombiesRulesConfig.Defaults()).load();
            check(generated.isValid() && !isBoss(generated.getWaves().get(0)), "generated default remains a normal wave");
            String map = Files.readString(root.resolve("src/main/java/com/cdp/codpattern/compat/fpsmatch/map/zombies/ZombiesMap.java"));
            check(!map.contains("SoundEvents.BELL_BLOCK") && !map.contains("playIntermissionBell"), "old wave bell removed");
            check(map.contains("playWaveIntro();") && map.contains("waveDirector.waveDefinition(runtimeState.waveState().targetWave())")
                    && map.contains("ZombiesSoundRegister.forWave(definition)"), "upcoming wave drives the existing intermission playback hook");
            String bootstrap = Files.readString(root.resolve("src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesBootstrap.java"));
            check(bootstrap.contains("ZombiesSoundRegister.SOUNDS.register(modEventBus)"), "sound events registered on mod bus");
            Path sounds = root.resolve("src/main/resources/assets/codpattern_zombies/sounds.json");
            if (Files.exists(sounds)) {
                var json = JsonParser.parseString(Files.readString(sounds)).getAsJsonObject();
                check(json.getAsJsonObject("wave_intro_normal").getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString().equals("codpattern_zombies:zombies/beginorm"), "normal event resolves supplied normal asset");
                check(json.getAsJsonObject("wave_intro_boss").getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString().equals("codpattern_zombies:zombies/beginboss1"), "boss event resolves supplied boss asset");
            } else {
                check(false, "normal and boss sound definitions exist");
            }
            Path registry = root.resolve("src/main/java/com/cdp/codpattern/common/sound/ZombiesSoundRegister.java");
            check(Files.exists(registry) && Files.readString(registry).contains("wave != null && wave.isBossIntro() ? BOSS_WAVE_INTRO : NORMAL_WAVE_INTRO"), "exactly one sound selected from bossIntro with normal fallback");
            checkAsset(root, "beginorm.ogg", "6c6a0e7ed804e6b1c3e0eda624dd7d3b1a52badcd946100d5b48dd02364348e3");
            checkAsset(root, "beginboss1.ogg", "3806a6ef74f0ec36acb93013f9e32a7489d2f73a893b987908b4afe8198d849c");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("WAVE_INTRO_CHECKS=" + checks + " FAILURES=" + failures);
        if (failures != 0) throw new AssertionError("Wave intro contract failures: " + failures);
    }

    private static void checkWave(Path temp, String name, String flag, boolean valid, boolean boss) throws Exception {
        Path dir = Files.createDirectory(temp.resolve(name));
        Path file = dir.resolve("wave_001.json");
        String original = "{\"wave\":1,\"mobs\":[]" + flag + "}";
        Files.writeString(file, original);
        var result = new ZombiesWaveConfigRepository(dir, new ZombiesRulesConfig.Defaults()).load();
        boolean actualBoss = result.getWaves().isEmpty() ? false : isBoss(result.getWaves().get(0));
        System.out.println("INPUT=" + name + " VALID=" + result.isValid() + " BOSS=" + actualBoss);
        check(result.isValid() == valid && (!valid || actualBoss == boss), "parse " + name);
        check(Files.readString(file).equals(original), "preserve input " + name);
    }

    private static boolean isBoss(ZombiesWaveDefinition wave) throws Exception {
        try { return (boolean) ZombiesWaveDefinition.class.getMethod("isBossIntro").invoke(wave); }
        catch (NoSuchMethodException baseline) { return false; }
    }

    private static void checkAsset(Path root, String name, String expected) throws Exception {
        Path file = root.resolve("src/main/resources/assets/codpattern_zombies/sounds/zombies/" + name);
        String hash = Files.exists(file) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) : "missing";
        check(expected.equals(hash), "supplied audio bytes unchanged: " + name);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures++;
        System.out.println((condition ? "PASS " : "FAIL ") + name);
    }
}
