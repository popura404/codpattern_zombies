package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextDefinition;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveTextMessage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads optional map-scoped wave chat without making it part of startup validation. */
public final class ZombiesWaveTextRepository {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Pattern WAVE_TEXT_FILE_PATTERN = Pattern.compile("^wave_(\\d+).*\\.json$");
    private static final String DEFAULT_WAVE_TEXT_FILE_NAME = "wave_001.json";
    private static final String DEFAULT_WAVE_TEXT_JSON = """
            {
              "wave": 1,
              "messages": [
                {
                  "delayTicks": 0,
                  "text": "text1"
                },
                {
                  "delayTicks": 40,
                  "text": "text2"
                }
              ]
            }
            """;

    private final Path waveTextDirectory;

    public ZombiesWaveTextRepository(Path waveTextDirectory) {
        this.waveTextDirectory = waveTextDirectory;
    }

    public LoadResult load() {
        List<LoadIssue> issues = new ArrayList<>();
        if (waveTextDirectory == null) {
            addIssue(issues, null, "Wave text directory is not configured");
            return new LoadResult(List.of(), issues);
        }

        try {
            Files.createDirectories(waveTextDirectory);
        } catch (IOException | SecurityException exception) {
            addIssue(issues, waveTextDirectory,
                    "Wave text directory could not be created: " + exception.getMessage());
            return new LoadResult(List.of(), issues);
        }

        List<Path> files;
        try (var paths = Files.list(waveTextDirectory)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> WAVE_TEXT_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException | SecurityException exception) {
            addIssue(issues, waveTextDirectory,
                    "Wave text directory could not be scanned: " + exception.getMessage());
            return new LoadResult(List.of(), issues);
        }

        if (files.isEmpty() && ensureDefaultExample(issues)) {
            files = List.of(waveTextDirectory.resolve(DEFAULT_WAVE_TEXT_FILE_NAME));
        }

        Map<Integer, LoadedDefinition> definitions = new LinkedHashMap<>();
        for (Path file : files) {
            Optional<ZombiesWaveTextDefinition> definition = readDefinition(file, issues);
            if (definition.isEmpty()) {
                continue;
            }
            int wave = definition.get().wave();
            LoadedDefinition retained = definitions.get(wave);
            if (retained != null) {
                addIssue(issues, file, "Duplicate wave " + wave + "; retaining sorted first file "
                        + retained.source().getFileName());
                continue;
            }
            definitions.put(wave, new LoadedDefinition(file, definition.get()));
        }

        return new LoadResult(
                definitions.values().stream().map(LoadedDefinition::definition).toList(),
                issues);
    }

    public Path waveTextDirectory() {
        return waveTextDirectory;
    }

    private Optional<ZombiesWaveTextDefinition> readDefinition(Path file, List<LoadIssue> issues) {
        Matcher fileMatcher = WAVE_TEXT_FILE_PATTERN.matcher(file.getFileName().toString());
        if (!fileMatcher.matches()) {
            return Optional.empty();
        }

        final int fileWave;
        try {
            fileWave = Integer.parseInt(fileMatcher.group(1));
        } catch (NumberFormatException exception) {
            addIssue(issues, file, "Wave number in file name is outside the supported integer range");
            return Optional.empty();
        }
        if (fileWave < 1) {
            addIssue(issues, file, "Wave number in file name must be positive");
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                addIssue(issues, file, "Root value must be a JSON object");
                return Optional.empty();
            }
            JsonObject object = root.getAsJsonObject();
            Optional<Integer> configuredWave = positiveInteger(object.get("wave"));
            if (configuredWave.isEmpty()) {
                addIssue(issues, file, "Field 'wave' must be a positive integer");
                return Optional.empty();
            }
            if (configuredWave.get() != fileWave) {
                addIssue(issues, file, "File name wave " + fileWave
                        + " does not match JSON field 'wave' " + configuredWave.get());
                return Optional.empty();
            }
            JsonElement messagesElement = object.get("messages");
            if (messagesElement == null || !messagesElement.isJsonArray()) {
                addIssue(issues, file, "Field 'messages' must be an array");
                return Optional.empty();
            }

            List<ZombiesWaveTextMessage> messages = readMessages(file, messagesElement.getAsJsonArray(), issues);
            return Optional.of(new ZombiesWaveTextDefinition(fileWave, messages));
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            addIssue(issues, file, "Wave text file could not be parsed: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private boolean ensureDefaultExample(List<LoadIssue> issues) {
        Path example = waveTextDirectory.resolve(DEFAULT_WAVE_TEXT_FILE_NAME);
        try {
            if (!Files.exists(example)) {
                Files.writeString(
                        example,
                        DEFAULT_WAVE_TEXT_JSON,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            }
            return true;
        } catch (IOException | SecurityException exception) {
            addIssue(issues, example, "Default wave text example could not be created: "
                    + exception.getMessage());
            return false;
        }
    }

    private List<ZombiesWaveTextMessage> readMessages(Path file, JsonArray array, List<LoadIssue> issues) {
        List<ZombiesWaveTextMessage> messages = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String fieldPath = "messages[" + index + "]";
            if (element == null || !element.isJsonObject()) {
                addIssue(issues, file, fieldPath + " must be an object; entry skipped");
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            Optional<Integer> delayTicks = nonNegativeInteger(object.get("delayTicks"));
            if (delayTicks.isEmpty()) {
                addIssue(issues, file, fieldPath
                        + ".delayTicks must be a non-negative integer; entry skipped");
                continue;
            }
            JsonElement textElement = object.get("text");
            if (textElement == null || !textElement.isJsonPrimitive()
                    || !textElement.getAsJsonPrimitive().isString()) {
                addIssue(issues, file, fieldPath + ".text must be a string; entry skipped");
                continue;
            }
            String text = textElement.getAsString();
            if (text.isBlank()) {
                addIssue(issues, file, fieldPath + ".text must not be blank; entry skipped");
                continue;
            }
            messages.add(new ZombiesWaveTextMessage(delayTicks.get(), text));
        }
        return List.copyOf(messages);
    }

    private static Optional<Integer> positiveInteger(JsonElement element) {
        return exactInteger(element).filter(value -> value > 0);
    }

    private static Optional<Integer> nonNegativeInteger(JsonElement element) {
        return exactInteger(element).filter(value -> value >= 0);
    }

    private static Optional<Integer> exactInteger(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(element.getAsString()).intValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static void addIssue(List<LoadIssue> issues, Path path, String message) {
        LoadIssue issue = new LoadIssue(path, message);
        issues.add(issue);
        LOGGER.warn("Invalid Zombies wave text config at {}: {}",
                path == null ? "<unset>" : path.toAbsolutePath(), message);
    }

    private record LoadedDefinition(Path source, ZombiesWaveTextDefinition definition) {
    }

    public record LoadIssue(Path path, String message) {
    }

    public static final class LoadResult {
        private final List<ZombiesWaveTextDefinition> definitions;
        private final List<LoadIssue> issues;
        private final Map<Integer, ZombiesWaveTextDefinition> byWave;

        private LoadResult(List<ZombiesWaveTextDefinition> definitions, List<LoadIssue> issues) {
            this.definitions = List.copyOf(definitions);
            this.issues = List.copyOf(issues);
            Map<Integer, ZombiesWaveTextDefinition> indexed = new LinkedHashMap<>();
            this.definitions.forEach(definition -> indexed.put(definition.wave(), definition));
            this.byWave = Map.copyOf(indexed);
        }

        public List<ZombiesWaveTextDefinition> definitions() {
            return definitions;
        }

        public List<LoadIssue> issues() {
            return issues;
        }

        public Optional<ZombiesWaveTextDefinition> definition(int wave) {
            return Optional.ofNullable(byWave.get(wave));
        }
    }
}
