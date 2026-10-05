package com.cdp.codpattern.config.zombies;

import com.cdp.codpattern.app.zombies.service.ZombiesBackpackAmmoResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Pure JVM coverage. Failures are never swallowed or reported as skipped. */
public final class ZombiesBackpackCompatSuite {
    private static int assertions;
    private static final String BASE = """
            {"schemaVersion":1,"starterWeapon":{"item":"tacz:modern_kinetic_gun","count":1,
            "nbt":"{GunId:\\"tacz:glock_17\\"}"},"ammunition":{"defaultMaxReserveAmmo":120}}
            """;

    public static void main(String[] args) throws Exception {
        assertions = 0;
        defaults();
        strictNumbers();
        structure();
        labelsRemainOpaque();
        matching();
        filesAndSnapshots();
        migrationOpaqueStrings();
        System.out.println("ZombiesBackpackCompatSuite PASS (" + assertions + " assertions, no skips)");
    }

    private static void defaults() {
        ZombiesBackpackConfig config = ZombiesBackpackConfig.defaults();
        check(config.errors().isEmpty(), "defaults must be valid");
        check(config.getStarterWeapon().getItem().equals("tacz:modern_kinetic_gun"), "default gun item");
        check(config.getStarterWeapon().getNbt().contains("tacz:glock_17"), "default Glock");
        check(config.getStarterWeapon().getCount() == 1, "default count");
        check(config.getStarterWeapon().getAttachmentPreset().isEmpty(), "default attachment preset");
        check(config.getAmmunition().getDefaultMaxReserveAmmo() == 120, "default fallback");
        check(config.getAmmunition().getMaxReserveAmmoByType().equals(Map.of(
                "pistol", 180, "rifle", 360, "smg", 420, "mg", 560,
                "shotgun", 180, "sniper", 120, "rpg", 36)), "complete default category table");
        check(config.getAmmunition().getMaxReserveAmmoByGunId().isEmpty(), "default gun overrides");
    }

    private static void strictNumbers() {
        List<String> invalid = List.of("-1", "1.5", "1.0", "1e2", "\"120\"", "null", "true", "2147483648", "[]", "{}");
        for (String value : invalid) {
            reject(withNumber("count", value), "starterWeapon.count");
            reject(withNumber("defaultMaxReserveAmmo", value), "ammunition.defaultMaxReserveAmmo");
            reject(withTable("maxReserveAmmoByType", "{\"custom type!\":" + value + "}"), "maxReserveAmmoByType");
            reject(withTable("maxReserveAmmoByGunId", "{\"not a resource id\":" + value + "}"), "maxReserveAmmoByGunId");
        }
        reject(withNumber("count", "0"), "starterWeapon.count");
        reject(BASE.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"), "schemaVersion");
        reject(BASE.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "schemaVersion");
        check(parse(withNumber("count", "2147483647")).errors().isEmpty(), "max count supported");
        check(parse(withNumber("defaultMaxReserveAmmo", "2147483647")).errors().isEmpty(), "max ammo supported");
        check(parse(withNumber("defaultMaxReserveAmmo", "0")).getAmmunition().getDefaultMaxReserveAmmo() == 0,
                "zero ammo remains zero");
    }

    private static void structure() {
        for (String malformed : List.of("[]", "null", "{", BASE + "{}", BASE.replace("\"schemaVersion\"", "schemaVersion"))) {
            check(!parse(malformed).errors().isEmpty(), "reject malformed JSON: " + malformed);
        }
        JsonObject missing = tree(); missing.remove("schemaVersion"); reject(missing.toString(), "schemaVersion");
        missing = tree(); missing.getAsJsonObject("ammunition").remove("defaultMaxReserveAmmo");
        reject(missing.toString(), "defaultMaxReserveAmmo");
        for (String field : List.of("starterWeapon", "ammunition")) {
            JsonObject root = tree(); root.remove(field);
            reject(root.toString(), field);
            root = tree(); root.add(field, JsonParser.parseString("[]"));
            reject(root.toString(), field);
        }
        for (String field : List.of("item", "nbt", "count")) {
            JsonObject root = tree(); root.getAsJsonObject("starterWeapon").remove(field);
            reject(root.toString(), field);
        }
        for (String field : List.of("item", "nbt", "attachmentPreset")) {
            for (String value : List.of("null", "123", "true", "{}", "[]")) {
                JsonObject root = tree(); root.getAsJsonObject("starterWeapon").add(field, JsonParser.parseString(value));
                reject(root.toString(), field);
            }
        }
        for (String field : List.of("maxReserveAmmoByType", "maxReserveAmmoByGunId")) {
            reject(withTable(field, "null"), field);
            reject(withTable(field, "[]"), field);
        }
        ZombiesBackpackConfig minimal = parse(BASE);
        check(minimal.errors().isEmpty(), "optional tables and preset can be absent");
        check(minimal.getStarterWeapon().getAttachmentPreset().isEmpty(), "missing preset defaults empty");
        check(minimal.getAmmunition().getMaxReserveAmmoByType().isEmpty(), "missing type table stays empty");
        check(minimal.getAmmunition().getMaxReserveAmmoByGunId().isEmpty(), "missing gun table stays empty");
        JsonObject root = tree(); root.addProperty("ignoredFutureSetting", "opaque");
        check(parse(root.toString()).errors().isEmpty(), "unknown fields do not affect loading");
    }

    private static void labelsRemainOpaque() {
        JsonObject root = tree(); JsonObject starter = root.getAsJsonObject("starterWeapon");
        starter.addProperty("item", "  未安装：not a valid resource id!  ");
        starter.addProperty("nbt", "{{ this is not SNBT and GunCurrentAmmoCount:-7");
        starter.addProperty("attachmentPreset", "}} invalid attachment SNBT missing:attachment ");
        JsonObject types = new JsonObject(); types.addProperty(" 自定义 类别!! ", 38);
        JsonObject guns = new JsonObject(); guns.addProperty("  tacz：unknown gun!  ", 47);
        root.getAsJsonObject("ammunition").add("maxReserveAmmoByType", types);
        root.getAsJsonObject("ammunition").add("maxReserveAmmoByGunId", guns);
        ZombiesBackpackConfig config = parse(root.toString());
        check(config.errors().isEmpty(), "unknown IDs and invalid SNBT must load: " + config.errors());
        check(config.getStarterWeapon().getItem().equals(starter.get("item").getAsString()), "item content preserved");
        check(config.getStarterWeapon().getNbt().equals(starter.get("nbt").getAsString()), "NBT content preserved");
        check(config.getStarterWeapon().getAttachmentPreset().equals(starter.get("attachmentPreset").getAsString()), "preset preserved");
        check(config.getAmmunition().getMaxReserveAmmoByGunId().containsKey("  tacz：unknown gun!  "), "gun labels preserved");
        check(resolve(config, "  tacz：unknown gun!  ", "unknown") == 47, "opaque gun key exact matching");
        check(resolve(config, "tacz：unknown gun!", "unknown") == 120, "gun labels are not trimmed");
    }

    private static void matching() {
        String json = withTable("maxReserveAmmoByType", "{\"pistol\":180,\" RIFLE \":301,\"rifle\":302,\"no-ammo\":0}");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        root.getAsJsonObject("ammunition").add("maxReserveAmmoByGunId", JsonParser.parseString("{\"tacz:glock_17\":77,\"test:zero\":0}"));
        ZombiesBackpackConfig config = parse(root.toString());
        check(config.errors().isEmpty(), "duplicate normalized category is allowed");
        check(resolve(config, "tacz:glock_17", "pistol") == 77, "gun override wins category");
        check(resolve(config, "other:gun", "pistol") == 180, "category wins default");
        check(resolve(config, "other:gun", " RIfLe ") == 302, "normalized later category wins");
        check(resolve(config, "test:zero", "pistol") == 0, "zero gun override wins");
        check(resolve(config, "other:gun", "no-ammo") == 0, "zero category wins");
        check(resolve(config, "other:gun", "new-category") == 120, "unknown category fallback");
        check(resolve(config, null, null) == 120, "unavailable gun/type fallback");
        check(resolve(parse(BASE), "tacz:glock_17", "pistol") == 120, "empty tables not replenished with defaults");
    }

    private static void filesAndSnapshots() throws Exception {
        Path root = Files.createTempDirectory("zombies-backpack-");
        try {
            Path first = root.resolve("map-a/rules/backpack.json");
            ZombiesBackpackConfig created = ZombiesBackpackConfig.load(first);
            check(created.errors().isEmpty() && created.templateCreated(), "missing file creates default template");
            String template = Files.readString(first);
            check(!template.contains("MagazineMultiple") && !template.contains("weaponTabs") && !template.contains("blocked"), "template has no retired settings");
            check(!ZombiesBackpackConfig.load(first).templateCreated(), "existing file not regenerated");
            check(Files.readString(first).equals(template), "loading existing file preserves original text");
            java.util.ArrayList<String> invalidFiles = new java.util.ArrayList<>(List.of("{invalid"));
            for (String value : List.of("-1", "1.5", "1.0", "1e2", "\"120\"", "null", "true", "2147483648", "[]", "{}")) {
                invalidFiles.add(withNumber("count", value));
                invalidFiles.add(withNumber("defaultMaxReserveAmmo", value));
                invalidFiles.add(withTable("maxReserveAmmoByType", "{\"pistol\":" + value + "}"));
                invalidFiles.add(withTable("maxReserveAmmoByGunId", "{\"tacz:glock_17\":" + value + "}"));
            }
            for (String invalid : invalidFiles) {
                Files.writeString(first, invalid);
                ZombiesBackpackConfig bad = ZombiesBackpackConfig.load(first);
                check(!bad.errors().isEmpty(), "invalid file rejected");
                check(!bad.templateCreated(), "invalid file not reported as generated");
                check(Files.readString(first).equals(invalid), "invalid file preserved byte-for-byte");
            }
            Files.writeString(first, withNumber("defaultMaxReserveAmmo", "43"));
            Path second = root.resolve("map-b/rules/backpack.json"); Files.createDirectories(second.getParent());
            Files.writeString(second, withNumber("defaultMaxReserveAmmo", "98"));
            ZombiesBackpackConfig a = ZombiesBackpackConfig.load(first), b = ZombiesBackpackConfig.load(second);
            check(resolve(a, "g", "type") == 43 && resolve(b, "g", "type") == 98, "maps own independent rules");
            Files.writeString(first, withNumber("defaultMaxReserveAmmo", "66"));
            check(resolve(a, "g", "type") == 43, "loaded snapshot not changed by disk edits");
            check(resolve(ZombiesBackpackConfig.load(first), "g", "type") == 66, "subsequent load gets new rules");
            Path directory = root.resolve("directory.json"); Files.createDirectory(directory);
            check(!ZombiesBackpackConfig.load(directory).errors().isEmpty(), "read failure surfaces as error");
            check(Files.isDirectory(directory), "read failure preserves existing directory");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void migrationOpaqueStrings() throws Exception {
        Path root = Files.createTempDirectory("zombies-backpack-preflight-");
        try {
            for (String active : List.of("room.json", "weapon_rules.json", "weapon_wall.json", "mystery_box.json")) {
                Files.writeString(root.resolve(active), "{\"schemaVersion\":1}");
            }
            JsonObject config = tree(); JsonObject starter = config.getAsJsonObject("starterWeapon");
            starter.addProperty("item", "missing mod：invalid tag !");
            starter.addProperty("nbt", "{{invalid SNBT");
            starter.addProperty("attachmentPreset", "}}also invalid SNBT");
            config.getAsJsonObject("ammunition").add("maxReserveAmmoByGunId", JsonParser.parseString("{\"not a resource ID\":7}"));
            String source = config.toString();
            Files.writeString(root.resolve("backpack.json"), source);
            Files.writeString(root.resolve("weapon_filter.json"), "{invalid retired filter");
            ZombiesStorageMigration.validate(root);
            check(Files.readString(root.resolve("backpack.json")).equals(source), "migration accepts opaque labels and SNBT without rewriting");
            check(Files.readString(root.resolve("weapon_filter.json")).equals("{invalid retired filter"), "migration ignores retired corrupt filter");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String withNumber(String field, String literal) {
        JsonObject root = tree();
        root.getAsJsonObject(field.equals("count") ? "starterWeapon" : "ammunition").add(field, JsonParser.parseString(literal));
        return root.toString();
    }
    private static String withTable(String field, String value) {
        JsonObject root = tree(); root.getAsJsonObject("ammunition").add(field, JsonParser.parseString(value)); return root.toString();
    }
    private static JsonObject tree() { return JsonParser.parseString(BASE).getAsJsonObject(); }
    private static ZombiesBackpackConfig parse(String json) { return ZombiesBackpackConfig.parse(json, Path.of("backpack.json")); }
    private static int resolve(ZombiesBackpackConfig config, String id, String type) {
        return ZombiesBackpackAmmoResolver.resolve(id, type, config.getAmmunition());
    }
    private static void reject(String json, String field) {
        ZombiesBackpackConfig config = parse(json);
        check(!config.errors().isEmpty(), "expected invalid " + field + ": " + json);
        check(config.errors().stream().anyMatch(error -> error.contains(field)), "error identifies " + field + ": " + config.errors());
    }
    private static void check(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
    private ZombiesBackpackCompatSuite() { }
}
