package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextDefinition;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ZombiesWaveTextCompatTest {
    private ZombiesWaveTextCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        missingDirectoryCreatesOnlyEmptyWaveTextDirectory();
        repositoryLoadsUtf8EscapesAndPreservesMessageOrder();
        repositorySkipsMalformedFilesAndBadEntries();
        repositoryRequiresFileAndJsonWaveToMatch();
        repositoryKeepsSortedFirstDuplicateWave();
        formatterBuildsStructuredLegacyStyles();
        schedulerUsesRelativeTicksAndDrainsSameTickMessages();
        schedulersAreRoomIsolatedAndCancellationDropsPendingMessages();
        mapLifecycleOwnsReloadBroadcastAndCancellationIntegration();
    }

    private static void missingDirectoryCreatesOnlyEmptyWaveTextDirectory() throws IOException {
        Path root = Files.createTempDirectory("zombies-wavetext-missing-");
        try {
            Path directory = root.resolve("wavetext");
            ZombiesWaveTextRepository.LoadResult result = new ZombiesWaveTextRepository(directory).load();

            require(Files.isDirectory(directory), "missing wavetext directory should be created");
            try (Stream<Path> children = Files.list(directory)) {
                require(children.findAny().isEmpty(), "wavetext directory must not receive an auto-sending example");
            }
            require(result.definitions().isEmpty(), "missing optional config should load as no wave text");
            require(result.issues().isEmpty(), "creating a missing directory should not be an error");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void repositoryLoadsUtf8EscapesAndPreservesMessageOrder() throws IOException {
        Path root = Files.createTempDirectory("zombies-wavetext-utf8-");
        try {
            Path directory = Files.createDirectories(root.resolve("wavetext"));
            Files.writeString(directory.resolve("wave_001.json"), """
                    {
                      "wave": 1,
                      "messages": [
                        {"delayTicks": 0, "text": "§6做好准备。"},
                        {"delayTicks": 40, "text": "\\u00a7c它们来了！\\u00a7r"}
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            ZombiesWaveTextRepository.LoadResult result = new ZombiesWaveTextRepository(directory).load();

            ZombiesWaveTextDefinition definition = result.definition(1).orElseThrow();
            require(result.issues().isEmpty(), "valid UTF-8 wave text should not have issues");
            require(definition.messages().size() == 2, "valid messages should retain configured order");
            require(definition.messages().get(0).delayTicks() == 0, "first delay should load");
            require("§6做好准备。".equals(definition.messages().get(0).text()),
                    "direct UTF-8 section sign should load");
            require(definition.messages().get(1).delayTicks() == 40, "second relative delay should load");
            require("§c它们来了！§r".equals(definition.messages().get(1).text()),
                    "JSON unicode escapes should decode to section signs");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void repositorySkipsMalformedFilesAndBadEntries() throws IOException {
        Path root = Files.createTempDirectory("zombies-wavetext-invalid-");
        try {
            Path directory = Files.createDirectories(root.resolve("wavetext"));
            Path malformed = directory.resolve("wave_001.json");
            Files.writeString(malformed, "{not json", StandardCharsets.UTF_8);
            Path mixed = directory.resolve("wave_002.json");
            Files.writeString(mixed, """
                    {
                      "wave": 2,
                      "messages": [
                        {"delayTicks": -1, "text": "negative"},
                        {"delayTicks": 1, "text": "  "},
                        {"delayTicks": 1.5, "text": "fractional"},
                        {"delayTicks": 3, "text": "valid"}
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            ZombiesWaveTextRepository.LoadResult result = new ZombiesWaveTextRepository(directory).load();

            require(result.definition(1).isEmpty(), "malformed file should be skipped");
            ZombiesWaveTextDefinition definition = result.definition(2).orElseThrow();
            require(definition.messages().equals(List.of(new ZombiesWaveTextMessage(3, "valid"))),
                    "invalid entries should be skipped without dropping the valid entry");
            require(result.issues().size() == 4, "malformed file and three bad entries should each report an issue");
            require(result.issues().stream().allMatch(issue -> issue.path() != null),
                    "every repository warning should carry its source path");
            require(result.issues().stream().anyMatch(issue -> mixed.equals(issue.path())
                            && issue.message().contains("messages[0]")),
                    "bad entry warnings should identify the entry index and path");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void repositoryRequiresFileAndJsonWaveToMatch() throws IOException {
        Path root = Files.createTempDirectory("zombies-wavetext-mismatch-");
        try {
            Path directory = Files.createDirectories(root.resolve("wavetext"));
            Path mismatch = directory.resolve("wave_003.json");
            Files.writeString(mismatch,
                    "{\"wave\":4,\"messages\":[{\"delayTicks\":0,\"text\":\"wrong\"}]}",
                    StandardCharsets.UTF_8);

            ZombiesWaveTextRepository.LoadResult result = new ZombiesWaveTextRepository(directory).load();

            require(result.definitions().isEmpty(), "mismatched file/JSON wave should be skipped");
            require(result.issues().size() == 1 && mismatch.equals(result.issues().get(0).path()),
                    "mismatch warning should identify the exact file path");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void repositoryKeepsSortedFirstDuplicateWave() throws IOException {
        Path root = Files.createTempDirectory("zombies-wavetext-duplicate-");
        try {
            Path directory = Files.createDirectories(root.resolve("wavetext"));
            Files.writeString(directory.resolve("wave_001_b.json"), jsonLine(1, "second"), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("wave_001_a.json"), jsonLine(1, "first"), StandardCharsets.UTF_8);

            ZombiesWaveTextRepository.LoadResult result = new ZombiesWaveTextRepository(directory).load();

            ZombiesWaveTextDefinition retained = result.definition(1).orElseThrow();
            require("first".equals(retained.messages().get(0).text()),
                    "lexically sorted first duplicate should be deterministic");
            require(result.definitions().size() == 1, "duplicate wave should retain only one definition");
            require(result.issues().size() == 1 && result.issues().get(0).message().contains("Duplicate wave 1"),
                    "discarded duplicate should emit a warning");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void formatterBuildsStructuredLegacyStyles() {
        Component component = ZombiesLegacyTextFormatter.parse("§6准备§l战斗§R结束");
        List<Component> segments = component.getSiblings();

        require("准备战斗结束".equals(component.getString()), "formatter should retain ordinary Chinese text");
        require(!component.getString().contains("§"), "section-sign control codes must not leak into component text");
        require(segments.size() == 3, "style changes should produce three structured segments");
        requireStyle(segments.get(0).getStyle(), ChatFormatting.GOLD, false, "gold segment");
        requireStyle(segments.get(1).getStyle(), ChatFormatting.GOLD, true, "gold bold segment");
        require(segments.get(2).getStyle().getColor() == null && !segments.get(2).getStyle().isBold(),
                "uppercase reset should clear color and bold");

        Component uppercase = ZombiesLegacyTextFormatter.parse("§C红§L粗§o斜§n下划线§m删除§k乱");
        List<Component> uppercaseSegments = uppercase.getSiblings();
        requireStyle(uppercaseSegments.get(0).getStyle(), ChatFormatting.RED, false, "uppercase red segment");
        Style finalStyle = uppercaseSegments.get(5).getStyle();
        require(finalStyle.isBold() && finalStyle.isItalic() && finalStyle.isUnderlined()
                        && finalStyle.isStrikethrough() && finalStyle.isObfuscated(),
                "uppercase k-o style codes should combine");

        Component colorReset = ZombiesLegacyTextFormatter.parse("§l粗§a绿");
        require(colorReset.getSiblings().get(0).getStyle().isBold(), "bold should apply before a color code");
        require(!colorReset.getSiblings().get(1).getStyle().isBold(),
                "a vanilla color code should reset preceding decorations");
    }

    private static void schedulerUsesRelativeTicksAndDrainsSameTickMessages() {
        ZombiesWaveTextDefinition definition = new ZombiesWaveTextDefinition(1, List.of(
                new ZombiesWaveTextMessage(0, "zero"),
                new ZombiesWaveTextMessage(2, "two-a"),
                new ZombiesWaveTextMessage(0, "two-b"),
                new ZombiesWaveTextMessage(1, "three")));
        ZombiesWaveTextScheduler scheduler = new ZombiesWaveTextScheduler(List.of(definition));
        List<String> sent = new ArrayList<>();

        scheduler.startWave(1, sent::add);
        require(sent.equals(List.of("zero")), "zero-tick first line should send immediately");
        scheduler.tick(sent::add);
        require(sent.equals(List.of("zero")), "relative tick one should not send the two-tick line");
        scheduler.tick(sent::add);
        require(sent.equals(List.of("zero", "two-a", "two-b")),
                "same-tick messages should all send in config order");
        scheduler.tick(sent::add);
        require(sent.equals(List.of("zero", "two-a", "two-b", "three")),
                "later delay should remain relative to the previous line");
        require(!scheduler.isActive(), "scheduler should deactivate after its final line");
    }

    private static void schedulersAreRoomIsolatedAndCancellationDropsPendingMessages() {
        ZombiesWaveTextDefinition definition = new ZombiesWaveTextDefinition(1, List.of(
                new ZombiesWaveTextMessage(1, "one"),
                new ZombiesWaveTextMessage(2, "three")));
        ZombiesWaveTextScheduler roomA = new ZombiesWaveTextScheduler(List.of(definition));
        ZombiesWaveTextScheduler roomB = new ZombiesWaveTextScheduler(List.of(definition));
        List<String> sentA = new ArrayList<>();
        List<String> sentB = new ArrayList<>();

        roomA.startWave(1, sentA::add);
        roomB.startWave(1, sentB::add);
        roomA.tick(sentA::add);
        require(sentA.equals(List.of("one")) && sentB.isEmpty(),
                "ticking one room scheduler must not advance another room");
        roomA.cancel();
        roomA.tick(sentA::add);
        roomA.tick(sentA::add);
        require(sentA.equals(List.of("one")), "cancellation should discard pending lines");
        roomB.tick(sentB::add);
        require(sentB.equals(List.of("one")), "other room should remain independently active");

        roomB.replaceDefinitions(List.of(new ZombiesWaveTextDefinition(2,
                List.of(new ZombiesWaveTextMessage(0, "reloaded")))));
        roomB.startWave(1, sentB::add);
        require(sentB.equals(List.of("one")), "replacing frozen definitions should remove the prior game config");
        roomB.startWave(2, sentB::add);
        require(sentB.equals(List.of("one", "reloaded")), "next-game definitions should take effect after reload");
    }

    private static void mapLifecycleOwnsReloadBroadcastAndCancellationIntegration() throws IOException {
        Path mapPath = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/compat/fpsmatch/map/zombies/ZombiesMap.java");
        Path pathsPath = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/config/zombies/ZombiesConfigPaths.java");
        String map = Files.readString(mapPath);
        String paths = Files.readString(pathsPath);

        require(paths.contains("zombiesMapWaveText(MinecraftServer server, String mapName)"),
                "map-scoped wavetext path API should exist in the addon");
        require(paths.contains("ZOMBIES_WAVE_TEXT_DIRECTORY = \"wavetext\""),
                "wavetext should be a sibling directory of waves");
        require(map.contains("loadStartupConfigs(server);\n        loadWaveTextConfig(server);"),
                "every game start should reload wave text after validating a real server/member snapshot");
        require(map.contains("waveTextScheduler.startWave(runtimeState.waveState().targetWave()"),
                "INTERMISSION entry should start text for targetWave");
        require(map.contains("if (runtimeState.phase() == ZombiesGamePhase.INTERMISSION) {\n                waveTextScheduler.tick"),
                "only INTERMISSION ticks should advance pending text");
        require(map.contains("if (\"INTERMISSION\".equals(context.previousPhase()))"),
                "leaving INTERMISSION should cancel pending text");
        require(map.contains("for (ServerPlayer player : survivorPlayers()) {\n            player.sendSystemMessage(message);"),
                "delivery should resolve current online room survivors at send time and use system chat");
        require(map.contains("ZombiesLegacyTextFormatter.parse(text)"),
                "delivery should parse legacy codes into a structured component");
    }

    private static String jsonLine(int wave, String text) {
        return "{\"wave\":" + wave + ",\"messages\":[{\"delayTicks\":0,\"text\":\"" + text + "\"}]}";
    }

    private static void requireStyle(Style style, ChatFormatting color, boolean bold, String context) {
        require(TextColor.fromLegacyFormat(color).equals(style.getColor()), context + " should keep expected color");
        require(style.isBold() == bold, context + " should keep expected bold state");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
