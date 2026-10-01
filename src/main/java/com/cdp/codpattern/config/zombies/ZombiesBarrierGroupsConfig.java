package com.cdp.codpattern.config.zombies;

import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.item.ZombiesRequiredItem;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSpawnGroupChanges;
import com.cdp.codpattern.app.zombies.service.ZombiesErrorCode;
import com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue;
import com.cdp.codpattern.config.storage.StorageFiles;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable, map-owned rules. Legacy per-object values are never a fallback. */
public final class ZombiesBarrierGroupsConfig {
    public static final String FILE_NAME = "barrier_groups.json";
    public static final String EMPTY_TEMPLATE = "{\n  \"schemaVersion\": 2,\n  \"groups\": {}\n}\n";
    private final Path path;
    private final Map<Integer, GroupRule> groups;
    private final List<String> errors;
    private final boolean templateCreated;

    private ZombiesBarrierGroupsConfig(Path path, Map<Integer, GroupRule> groups, List<String> errors, boolean created) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.groups = Collections.unmodifiableMap(new TreeMap<>(groups));
        this.errors = List.copyOf(errors);
        this.templateCreated = created;
    }
    public static ZombiesBarrierGroupsConfig empty() { return new ZombiesBarrierGroupsConfig(null, Map.of(), List.of(), false); }
    public Map<Integer, GroupRule> groups() { return groups; }
    public Optional<GroupRule> rule(int group) { return errors.isEmpty() ? Optional.ofNullable(groups.get(group)) : Optional.empty(); }
    public Optional<ResolvedRule> rule(int group, int entryId) {
        GroupRule parent = rule(group).orElse(null);
        EntryRule entry = parent == null ? null : parent.entries().get(entryId);
        return entry == null ? Optional.empty() : Optional.of(new ResolvedRule(
                group, entryId, entry.cost(), entry.requiredItem(), parent.spawnGroupChanges()));
    }
    public List<String> errors() { return errors; }
    public boolean templateCreated() { return templateCreated; }
    public String sourcePath() { return path == null ? FILE_NAME : path.toString(); }

    public static ZombiesBarrierGroupsConfig load(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        try {
            StorageFiles.checkPath(resolved);
            boolean created = false;
            if (Files.notExists(resolved)) {
                Files.createDirectories(resolved.getParent());
                try {
                    Files.writeString(resolved, EMPTY_TEMPLATE, StandardOpenOption.CREATE_NEW);
                    created = true;
                } catch (FileAlreadyExistsException ignored) { /* Another reader created it; read, never overwrite. */ }
            }
            ZombiesBarrierGroupsConfig loaded = parse(Files.readString(resolved), resolved);
            return new ZombiesBarrierGroupsConfig(resolved, loaded.groups, loaded.errors, created);
        } catch (IOException | RuntimeException exception) {
            return invalid(resolved, exception);
        }
    }

    /** Pure parser, also used by storage migration preflight; does not write any files. */
    public static ZombiesBarrierGroupsConfig parse(String json, Path path) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonObject root = object(readValue(reader, 0), "root");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON content");
            if (integer(root.get("schemaVersion"), "schemaVersion") != 2) {
                throw new IllegalArgumentException("Unsupported schemaVersion; expected 2. Manually configure entries and choose entryId in the GUI; no automatic migration.");
            }
            knownFields(root, Set.of("schemaVersion", "groups"), "root");
            JsonObject definitions = object(root.get("groups"), "groups");
            Map<Integer, GroupRule> groups = new TreeMap<>();
            for (var definition : definitions.entrySet()) {
                int group = positiveKey(definition.getKey(), "barrier group");
                String groupPath = "groups." + group;
                JsonObject parent = object(definition.getValue(), groupPath);
                knownFields(parent, Set.of("spawnGroupChanges", "entries"), groupPath);
                JsonObject changes = parent.has("spawnGroupChanges")
                        ? object(parent.get("spawnGroupChanges"), groupPath + ".spawnGroupChanges") : new JsonObject();
                knownFields(changes, Set.of("enable", "disable"), groupPath + ".spawnGroupChanges");
                var actions = new ZombiesSpawnGroupChanges(groupSet(changes, "enable"), groupSet(changes, "disable"));
                if (!actions.valid()) throw new IllegalArgumentException("Conflicting spawn groups in barrier group " + group + ": " + actions.conflicts());
                JsonObject entryDefinitions = object(parent.get("entries"), groupPath + ".entries");
                Map<Integer, EntryRule> entries = new TreeMap<>();
                for (var definitionEntry : entryDefinitions.entrySet()) {
                    int entryId = positiveKey(definitionEntry.getKey(), groupPath + ".entries");
                    String entryPath = groupPath + ".entries." + entryId;
                    JsonObject entry = object(definitionEntry.getValue(), entryPath);
                    knownFields(entry, Set.of("cost", "requiredItem"), entryPath);
                    int cost = integer(entry.get("cost"), entryPath + ".cost");
                    String requiredItem = "";
                    if (entry.has("requiredItem")) {
                        JsonElement value = entry.get("requiredItem");
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                            throw new IllegalArgumentException(entryPath + ".requiredItem must be a string");
                        }
                        requiredItem = value.getAsString().trim();
                    }
                    entries.put(entryId, new EntryRule(cost, requiredItem));
                }
                groups.put(group, new GroupRule(actions, entries));
            }
            return new ZombiesBarrierGroupsConfig(path, groups, List.of(), false);
        } catch (IOException | RuntimeException exception) {
            return invalid(path, exception);
        }
    }

    private static ZombiesBarrierGroupsConfig invalid(Path path, Exception exception) {
        return new ZombiesBarrierGroupsConfig(path, Map.of(), List.of(Objects.toString(exception.getMessage(), exception.getClass().getSimpleName())), false);
    }
    private static int positiveKey(String key, String where) {
        if (!key.matches("[1-9][0-9]*")) throw new IllegalArgumentException(where + " must be a positive integer: " + key);
        try { return Integer.parseInt(key); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(where + " exceeds the supported integer range: " + key, ex); }
    }
    private static void knownFields(JsonObject object, Set<String> allowed, String where) {
        for (var field : object.entrySet()) {
            if (!allowed.contains(field.getKey())) throw new IllegalArgumentException("Unknown or misplaced field: " + where + "." + field.getKey());
        }
    }
    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
        return value.getAsJsonObject();
    }
    private static int integer(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(name + " must be an explicit non-negative integer");
        }
        try { return Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " exceeds the supported integer range", ex); }
    }
    private static Set<Integer> groupSet(JsonObject changes, String key) {
        if (!changes.has(key)) return Set.of();
        if (!changes.get(key).isJsonArray()) throw new IllegalArgumentException("spawnGroupChanges." + key + " must be an array");
        Set<Integer> result = new TreeSet<>();
        for (JsonElement value : changes.getAsJsonArray(key)) result.add(integer(value, "spawnGroupChanges." + key));
        return result;
    }
    // JsonParser alone silently replaces duplicate object keys. Reject them before binding rules.
    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject(); Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IllegalArgumentException("Duplicate JSON definition: " + name);
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token: " + reader.peek());
        };
    }

    public List<ZombiesValidationIssue> fileValidationIssues() {
        return errors.stream().map(error -> issue("map.invalid_barrier_group_rules", sourcePath(), sourcePath() + ": " + error)).toList();
    }
    /** Called only during map/server preflight, after the item registry is ready. */
    public List<ZombiesValidationIssue> bindingIssues(Collection<ZombiesBarrierData> barriers, Collection<ZombiesZombieSpawnData> spawns) {
        if (!errors.isEmpty()) return List.of();
        List<ZombiesValidationIssue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ZombiesBarrierData barrier : barriers) {
            String binding = barrier.group() + "/" + barrier.entryId();
            if (!seen.add(binding)) continue;
            if (!groups.containsKey(barrier.group())) {
                issues.add(issue("map.missing_barrier_group_rules", "barrier." + barrier.objectId(),
                        "Configure barrier group " + barrier.group() + " in " + sourcePath() + "; legacy object values are not used."));
            } else if (barrier.entryId() < 1 || rule(barrier.group(), barrier.entryId()).isEmpty()) {
                issues.add(issue("map.missing_barrier_entry_rules", "barrier." + barrier.objectId(),
                        "Select/configure barrier group/entry " + binding + " in " + sourcePath() + "; entry 0 means unselected and never defaults to entry 1."));
            }
        }
        Set<Integer> available = spawns.stream().map(ZombiesZombieSpawnData::group).collect(Collectors.toSet());
        for (var group : groups.entrySet()) {
            for (int target : group.getValue().spawnGroupChanges().referencedGroups()) {
                if (!available.contains(target)) issues.add(issue("map.unknown_spawn_group", "barrier.group_" + group.getKey(),
                        sourcePath() + ": barrier group " + group.getKey() + " references unknown zombie spawn group " + target));
            }
            for (var entry : group.getValue().entries().entrySet()) {
                var item = ZombiesRequiredItem.parse(entry.getValue().requiredItem());
                if (!item.valid()) issues.add(issue("map.invalid_barrier_entry_item", "barrier.group_" + group.getKey() + ".entry_" + entry.getKey(),
                        sourcePath() + ": group/entry " + group.getKey() + "/" + entry.getKey() + " invalid requiredItem: " + item.error()));
            }
        }
        return List.copyOf(issues);
    }
    private static ZombiesValidationIssue issue(String code, String subject, String message) {
        return ZombiesValidationIssue.error(ZombiesErrorCode.of(code), subject, message);
    }
    /** Missing rules use a non-purchasable sentinel, never the legacy price or a free default. */
    public ZombiesBarrierData resolve(ZombiesBarrierData barrier) {
        GroupRule parent = rule(barrier.group()).orElse(null);
        ResolvedRule resolved = rule(barrier.group(), barrier.entryId()).orElse(null);
        return new ZombiesBarrierData(barrier.objectId(), barrier.name(), barrier.group(), resolved == null ? -1 : resolved.cost(),
                barrier.blocksPlayersOnly(), barrier.dimension(), barrier.areaFrom(), barrier.areaTo(), barrier.interactionPos(),
                resolved == null ? "" : resolved.requiredItem(),
                parent == null ? ZombiesSpawnGroupChanges.NONE : parent.spawnGroupChanges(), barrier.entryId());
    }
    public com.cdp.codpattern.app.zombies.map.ZombiesMapObjects resolveObjects(
            com.cdp.codpattern.app.zombies.map.ZombiesMapObjects objects) {
        var source = objects == null ? com.cdp.codpattern.app.zombies.map.ZombiesMapObjects.EMPTY : objects;
        return new com.cdp.codpattern.app.zombies.map.ZombiesMapObjects(
                source.initialSpawns(), source.zombieSpawns(), source.barriers().stream().map(this::resolve).toList(),
                source.weaponWalls(), source.ammoBoxes(), source.armorStations(), source.powerSwitch(),
                source.sodaMachines(), source.ultimateMachines(), source.mysteryBoxes(), source.windows());
    }

    public record GroupRule(ZombiesSpawnGroupChanges spawnGroupChanges, Map<Integer, EntryRule> entries) {
        public GroupRule {
            if (spawnGroupChanges == null || !spawnGroupChanges.valid() || entries == null) throw new IllegalArgumentException("Invalid barrier group rule");
            for (var entry : entries.entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 1 || entry.getValue() == null) throw new IllegalArgumentException("Invalid barrier entry definition");
            }
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }
    }
    public record EntryRule(int cost, String requiredItem) {
        public EntryRule {
            if (cost < 0) throw new IllegalArgumentException("Invalid barrier entry cost");
            requiredItem = Objects.requireNonNullElse(requiredItem, "").trim();
        }
    }
    public record ResolvedRule(int group, int entryId, int cost, String requiredItem, ZombiesSpawnGroupChanges spawnGroupChanges) {}

}
