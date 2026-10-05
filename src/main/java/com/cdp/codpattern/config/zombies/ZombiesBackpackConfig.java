package com.cdp.codpattern.config.zombies;

import com.cdp.codpattern.app.zombies.service.ZombiesErrorCode;
import com.cdp.codpattern.app.zombies.validation.ZombiesValidationIssue;
import com.cdp.codpattern.config.storage.StorageFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Map-owned starting equipment and absolute reserve limits. Labels are interpreted only when used. */
public final class ZombiesBackpackConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String FILE_NAME = "backpack.json";
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String DEFAULT_STARTER_GUN_ITEM = "tacz:modern_kinetic_gun";
    public static final String DEFAULT_STARTER_GUN_ID = "tacz:glock_17";
    public static final String DEFAULT_STARTER_WEAPON_NBT =
            "{GunId:\"" + DEFAULT_STARTER_GUN_ID
                    + "\",GunCurrentAmmoCount:17,GunFireMode:\"SEMI\",HasBulletInBarrel:1}";
    public static final String DEFAULT_TEMPLATE = """
            {
              "schemaVersion": 1,
              "starterWeapon": {
                "item": "tacz:modern_kinetic_gun",
                "count": 1,
                "nbt": "{GunId:\\"tacz:glock_17\\",GunCurrentAmmoCount:17,GunFireMode:\\"SEMI\\",HasBulletInBarrel:1}",
                "attachmentPreset": ""
              },
              "ammunition": {
                "defaultMaxReserveAmmo": 120,
                "maxReserveAmmoByType": {
                  "pistol": 180,
                  "rifle": 360,
                  "smg": 420,
                  "mg": 560,
                  "shotgun": 180,
                  "sniper": 120,
                  "rpg": 36
                },
                "maxReserveAmmoByGunId": {}
              }
            }
            """;

    private final Path path;
    private final StarterWeapon starterWeapon;
    private final Ammunition ammunition;
    private final List<String> errors;
    private final boolean templateCreated;

    private ZombiesBackpackConfig(Path path, StarterWeapon starterWeapon, Ammunition ammunition,
                                  List<String> errors, boolean templateCreated) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.starterWeapon = starterWeapon;
        this.ammunition = ammunition;
        this.errors = List.copyOf(errors);
        this.templateCreated = templateCreated;
    }

    public static ZombiesBackpackConfig defaults() {
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("pistol", 180);
        types.put("rifle", 360);
        types.put("smg", 420);
        types.put("mg", 560);
        types.put("shotgun", 180);
        types.put("sniper", 120);
        types.put("rpg", 36);
        return new ZombiesBackpackConfig(null, StarterWeapon.defaults(),
                new Ammunition(120, types, Map.of()), List.of(), false);
    }

    public int getSchemaVersion() { return SUPPORTED_SCHEMA_VERSION; }
    public StarterWeapon getStarterWeapon() { requireValid(); return starterWeapon; }
    public Ammunition getAmmunition() { requireValid(); return ammunition; }
    public List<String> errors() { return errors; }
    public boolean templateCreated() { return templateCreated; }
    public String sourcePath() { return path == null ? FILE_NAME : path.toString(); }

    private void requireValid() {
        if (!errors.isEmpty()) throw new IllegalStateException(sourcePath() + ": " + String.join("; ", errors));
    }

    /** Only a missing file is created. Existing invalid files are preserved for correction. */
    public static ZombiesBackpackConfig load(Path path) {
        try {
            Path resolved = path.toAbsolutePath().normalize();
            StorageFiles.checkPath(resolved);
            boolean created = false;
            if (Files.notExists(resolved)) {
                Files.createDirectories(resolved.getParent());
                try {
                    Files.writeString(resolved, DEFAULT_TEMPLATE, StandardOpenOption.CREATE_NEW);
                    created = true;
                } catch (FileAlreadyExistsException ignored) { /* Read the file created by the other loader. */ }
            }
            ZombiesBackpackConfig loaded = parse(Files.readString(resolved), resolved);
            if (!loaded.errors.isEmpty()) {
                LOGGER.error("Invalid Zombies backpack config {}: {}", resolved, String.join("; ", loaded.errors));
            }
            return new ZombiesBackpackConfig(resolved, loaded.starterWeapon, loaded.ammunition, loaded.errors, created);
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Cannot load Zombies backpack config {}: {}", path, exception.getMessage(), exception);
            return invalid(path, exception);
        }
    }

    /** Pure structural and numeric validation. Does not resolve resource IDs or parse NBT/SNBT. */
    public static ZombiesBackpackConfig parse(String json, Path path) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonObject root = object(readValue(reader, 0), "root");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON content");
            if (integer(root.get("schemaVersion"), "schemaVersion", 0) != SUPPORTED_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported schemaVersion; expected 1");
            }
            JsonObject weapon = object(root.get("starterWeapon"), "starterWeapon");
            StarterWeapon starter = new StarterWeapon(
                    string(weapon.get("item"), "starterWeapon.item"),
                    integer(weapon.get("count"), "starterWeapon.count", 1),
                    string(weapon.get("nbt"), "starterWeapon.nbt"),
                    weapon.has("attachmentPreset")
                            ? string(weapon.get("attachmentPreset"), "starterWeapon.attachmentPreset") : "");
            JsonObject ammo = object(root.get("ammunition"), "ammunition");
            Ammunition ammunition = new Ammunition(
                    integer(ammo.get("defaultMaxReserveAmmo"), "ammunition.defaultMaxReserveAmmo", 0),
                    ammoMap(ammo, "maxReserveAmmoByType"),
                    ammoMap(ammo, "maxReserveAmmoByGunId"));
            return new ZombiesBackpackConfig(path, starter, ammunition, List.of(), false);
        } catch (IOException | RuntimeException exception) {
            return invalid(path, exception);
        }
    }

    public List<ZombiesValidationIssue> fileValidationIssues() {
        return errors.stream().map(error -> ZombiesValidationIssue.error(
                ZombiesErrorCode.of("map.invalid_backpack_rules"), sourcePath(), sourcePath() + ": " + error)).toList();
    }

    private static ZombiesBackpackConfig invalid(Path path, Exception exception) {
        return new ZombiesBackpackConfig(path, null, null,
                List.of(Objects.toString(exception.getMessage(), exception.getClass().getSimpleName())), false);
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement value, String name, int minimum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(name + " must be an explicit "
                    + (minimum == 0 ? "non-negative" : "positive") + " integer");
        }
        final int result;
        try { result = Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " exceeds the supported integer range", exception);
        }
        if (result < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return result;
    }

    private static Map<String, Integer> ammoMap(JsonObject ammunition, String field) {
        Map<String, Integer> values = new LinkedHashMap<>();
        if (!ammunition.has(field)) return values;
        JsonObject definitions = object(ammunition.get(field), "ammunition." + field);
        for (var definition : definitions.entrySet()) {
            values.put(definition.getKey(), integer(definition.getValue(),
                    "ammunition." + field + "." + definition.getKey(), 0));
        }
        return values;
    }

    private static JsonElement readValue(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    // Keep the last declaration in its input position, including normalized type-key collisions.
                    object.remove(name);
                    object.add(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            // Preserve the token so decimal/exponent notation is not silently converted to an integer.
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token: " + reader.peek());
        };
    }

    public static final class StarterWeapon {
        private final String item;
        private final Integer count;
        private final String nbt;
        private final String attachmentPreset;

        public StarterWeapon(String item, Integer count, String nbt, String attachmentPreset) {
            this.item = Objects.requireNonNull(item, "item");
            if (count == null || count < 1) throw new IllegalArgumentException("starterWeapon.count must be positive");
            this.count = count;
            this.nbt = Objects.requireNonNull(nbt, "nbt");
            this.attachmentPreset = Objects.requireNonNull(attachmentPreset, "attachmentPreset");
        }

        public static StarterWeapon defaults() {
            return new StarterWeapon(DEFAULT_STARTER_GUN_ITEM, 1, DEFAULT_STARTER_WEAPON_NBT, "");
        }
        public String getItem() { return item; }
        public Integer getCount() { return count; }
        public String getNbt() { return nbt; }
        public String getAttachmentPreset() { return attachmentPreset; }
    }

    public static final class Ammunition {
        private final int defaultMaxReserveAmmo;
        private final Map<String, Integer> maxReserveAmmoByType;
        private final Map<String, Integer> maxReserveAmmoByGunId;

        public Ammunition(int defaultMaxReserveAmmo, Map<String, Integer> byType, Map<String, Integer> byGunId) {
            if (defaultMaxReserveAmmo < 0) throw new IllegalArgumentException("defaultMaxReserveAmmo must be non-negative");
            this.defaultMaxReserveAmmo = defaultMaxReserveAmmo;
            this.maxReserveAmmoByType = immutableAmmoMap(byType);
            this.maxReserveAmmoByGunId = immutableAmmoMap(byGunId);
        }

        public int getDefaultMaxReserveAmmo() { return defaultMaxReserveAmmo; }
        public Map<String, Integer> getMaxReserveAmmoByType() { return maxReserveAmmoByType; }
        public Map<String, Integer> getMaxReserveAmmoByGunId() { return maxReserveAmmoByGunId; }

        private static Map<String, Integer> immutableAmmoMap(Map<String, Integer> source) {
            Map<String, Integer> copy = new LinkedHashMap<>();
            Objects.requireNonNull(source, "reserve ammo map").forEach((key, value) -> {
                if (key == null || value == null || value < 0) throw new IllegalArgumentException("Invalid reserve ammo entry");
                copy.put(key, value);
            });
            return Collections.unmodifiableMap(copy);
        }
    }
}
