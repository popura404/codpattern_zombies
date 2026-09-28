package com.cdp.codpattern.app.zombies.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZombiesDeployGuiStaticContractCompatTest {
    private static final Path SCREEN = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/client/gui/screen/zombies/deploy/ZombiesDeployToolScreen.java");
    private static final Path SERVICE = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployToolService.java");
    private static final Path TOOL = Path.of("../zombies-addon/src/main/java/com/phasetranscrystal/fpsmatch/common/item/zombies/ZombiesDeployTool.java");
    private static final Path PACKET = Path.of("../zombies-addon/src/main/java/com/phasetranscrystal/fpsmatch/common/packet/zombies/ZombiesDeployToolActionC2SPacket.java");
    private static final Path TOOL_INTERACTION_PACKET = Path.of("src/main/java/com/phasetranscrystal/fpsmatch/common/packet/ToolInteractionC2SPacket.java");
    private static final Path TOOL_INTERACTION_HANDLER = Path.of("src/main/java/com/phasetranscrystal/fpsmatch/common/item/tool/ToolInteractionClientHandler.java");
    private static final Path TOOL_INTERACTION_HIT = Path.of("src/main/java/com/phasetranscrystal/fpsmatch/common/item/tool/ToolInteractionHit.java");
    private static final Path FIELD_SCHEMA = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployFieldSchema.java");
    private static final Path VALIDATOR = Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/validation/ZombiesMapValidator.java");
    private static final List<Path> KEY_SOURCE_FILES = List.of(
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/client/gui/screen/zombies/deploy/ZombiesDeployToolScreen.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/client/gui/screen/zombies/deploy/ZombiesDeployUnsavedChangesScreen.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/client/gui/overlay/zombies/ZombiesDeploySessionOverlay.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployToolService.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployPreviewService.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployServiceResult.java"),
            Path.of("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployFieldSchema.java"),
            Path.of("../zombies-addon/src/main/java/com/phasetranscrystal/fpsmatch/common/item/zombies/ZombiesDeployTool.java"),
            Path.of("../zombies-addon/src/main/java/com/phasetranscrystal/fpsmatch/common/packet/zombies/ZombiesDeployToolActionC2SPacket.java"));
    private static final List<Path> LANG_FILES = List.of(
            Path.of("../zombies-addon/src/main/resources/assets/codpattern_zombies/lang/en_us.json"),
            Path.of("../zombies-addon/src/main/resources/assets/codpattern_zombies/lang/zh_cn.json"),
            Path.of("../zombies-addon/src/main/resources/assets/codpattern_zombies/lang/ja_jp.json"),
            Path.of("../zombies-addon/src/main/resources/assets/codpattern_zombies/lang/zh_tw.json"));
    private static final Pattern JAVA_DEPLOY_KEY = Pattern.compile("\"((?:gui\\.codpattern\\.zombies\\.deploy|message\\.codpattern\\.zombies\\.deploy|tooltip\\.codpattern\\.zombies_deploy)\\.[^\"]+)\"");
    private static final Pattern JSON_DEPLOY_KEY = Pattern.compile("\"((?:gui\\.codpattern\\.zombies\\.deploy|message\\.codpattern\\.zombies\\.deploy|tooltip\\.codpattern\\.zombies_deploy)\\.[^\"]+)\"\\s*:");
    private static final Pattern JSON_DEPLOY_ENTRY = Pattern.compile("\"((?:gui\\.codpattern\\.zombies\\.deploy|message\\.codpattern\\.zombies\\.deploy|tooltip\\.codpattern\\.zombies_deploy)\\.[^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private ZombiesDeployGuiStaticContractCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        String screen = read(SCREEN);
        String service = read(SERVICE);
        String tool = read(TOOL);
        String packet = read(PACKET);
        String toolInteractionPacket = read(TOOL_INTERACTION_PACKET);
        String toolInteractionHandler = read(TOOL_INTERACTION_HANDLER);
        String toolInteractionHit = read(TOOL_INTERACTION_HIT);
        String fieldSchema = read(FIELD_SCHEMA);
        String validator = read(VALIDATOR);
        verifyCurrentDeployContract(screen, service, tool, packet, validator);
        requireSingleObjectPolicy(fieldSchema);
        requireContains(toolInteractionHit, "clickedBlockPos.relative(clickedFace)",
                "world deployment should use the clicked face placement position");
        requireContains(toolInteractionPacket, "ToolInteractionHit.fromClicked(clickedPos, clickedFace)",
                "world interaction should preserve clicked face context");
        requireContains(toolInteractionHandler, "&& clickedFace == lastSentFace",
                "interaction de-duplication should include the clicked face");

        Set<String> javaKeys = deployKeysFromJava();
        Map<Path, Set<String>> langKeysByPath = new LinkedHashMap<>();
        Map<Path, Map<String, String>> langValuesByPath = new LinkedHashMap<>();
        for (Path lang : LANG_FILES) {
            String json = read(lang);
            Set<String> langKeys = deployKeysFromJson(json);
            langKeysByPath.put(lang, langKeys);
            langValuesByPath.put(lang, deployValuesFromJson(json));
            for (String key : javaKeys) {
                if (key.endsWith(".")) {
                    continue;
                }
                requireContains(langKeys, key, lang + " must define Java-referenced deploy key");
            }
            requireContains(json, "\"gui.codpattern.zombies.deploy.section.objects_properties\"", lang + " must define objects/properties title");
            requireContains(json, "\"gui.codpattern.zombies.deploy.no_registered_maps\"", lang + " must define empty zombies map list label");
            requireContains(json, "\"gui.codpattern.zombies.deploy.click_to_deploy\"", lang + " must define click-to-deploy hint");
            requireContains(json, "\"gui.codpattern.zombies.deploy.legend.left_click\"", lang + " must define left-click legend");
            requireContains(json, "\"gui.codpattern.zombies.deploy.legend.right_click\"", lang + " must define right-click legend");
            requireContains(json, "\"tooltip.codpattern.zombies_deploy.left_click\"", lang + " must define deploy-tool left-click tooltip");
            requireContains(json, "\"tooltip.codpattern.zombies_deploy.right_click\"", lang + " must define deploy-tool right-click tooltip");
            requireContains(json, "\"message.codpattern.zombies.deploy.selections_saved\"", lang + " must define selection save message");
            requireContains(json, "\"message.codpattern.zombies.deploy.select_object_first\"", lang + " must define selected-object warning");
            requireContains(json, "\"message.codpattern.zombies.deploy.right_click_noop\"", lang + " must define right-click no-op message");
            requireContains(json, "\"message.codpattern.zombies.deploy.duplicate_position\"", lang + " must define duplicate-position message");
            requireContains(json, "\"message.codpattern.zombies.deploy.barrier_area_from\"", lang + " must define barrier first-point message");
            requireContains(json, "\"message.codpattern.zombies.deploy.barrier_area_first_required\"", lang + " must define barrier first-point requirement message");
            requireAbsent(json, "capture slot", lang + " must not expose old slot-capture wording");
            requireAbsent(json, "slot A", lang + " must not expose old slot A wording");
            requireAbsent(json, "slot B", lang + " must not expose old slot B wording");
            requireAbsent(json, "槽位 A", lang + " must not expose old slot A wording");
            requireAbsent(json, "槽位 B", lang + " must not expose old slot B wording");
            requireAbsent(json, "スロット A", lang + " must not expose old slot A wording");
            requireAbsent(json, "スロット B", lang + " must not expose old slot B wording");
            requireAbsent(json, "gui.codpattern.zombies.deploy.section.objects_draft", lang + " must not expose old draft section key");
            requireAbsent(json, "gui.codpattern.zombies.deploy.new_draft", lang + " must not expose new draft label");
            requireAbsent(json, "gui.codpattern.zombies.deploy.save_object", lang + " must not expose save object label");
            requireAbsent(json, "gui.codpattern.zombies.deploy.draft_unsaved", lang + " must not expose draft unsaved label");
            requireAbsent(json, "gui.codpattern.zombies.deploy.draft_synced", lang + " must not expose draft synced label");
            requireAbsent(json, "gui.codpattern.zombies.deploy.status.draft_unsaved", lang + " must not expose draft status label");
            requireAbsent(json, "message.codpattern.zombies.deploy.draft_saved", lang + " must not expose old draft saved message");
        }
        assertSameDeployKeySet(langKeysByPath);
        assertSameDeployPlaceholderCounts(langValuesByPath);

        System.out.println("PASS zombies deploy GUI static contract compat");
    }

    private static void verifyCurrentDeployContract(
            String screen,
            String service,
            String tool,
            String packet,
            String validator
    ) {
        requireAbsent(screen, "layoutScale()", "full-screen editor must not scale a fixed window");
        requireAbsent(screen, "modeButton", "guided/expert split must be removed");
        requireAbsent(screen, "fieldValueBox", "fields must be editable inline");
        requireContains(screen, "session.mapScroll", "map list must scroll independently");
        requireContains(screen, "button(name, 12", "map rows should contain only their names");
        requireContains(screen, "ZombiesDeployMapScreen(session)", "map registration must have its own screen");
        requireContains(screen, "Action.REDO_LAST", "editor must expose redo");
        requireAbsent(packet, "player.displayClientMessage", "editor actions must not write to chat");
        requireAbsent(tool, "player.displayClientMessage", "world actions must use the deployment HUD");
        requireAbsent(service, "autoAdvanceDraft", "placement must keep the selected type");
        requireAbsent(service, "draft.validation_failed", "validation errors must not block saving");
        requireContains(screen, "Action.SAVE_DRAFT", "deploy GUI should expose explicit draft saving");
        requireContains(screen, "Action.DISCARD_DRAFT", "confirmed close must clear the draft");
        requireContains(screen, "Action.ADD_OBJECT", "editor should expose add-object action");
        requireContains(service, "private static final class DraftSession", "server should keep staged deployment sessions");
        requireContains(service, "public ZombiesDeployServiceResult<ZombiesDeploySnapshot> saveDraft", "server should commit staged drafts explicitly");
        requireContains(service, "public ZombiesDeployServiceResult<ZombiesDeploySnapshot> undoLast", "server should support undo");
        requireContains(tool, "new com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket(snapshot, false)", "world placement should refresh HUD state without reopening the screen");
        requireContains(packet, "case SAVE_DRAFT -> service.saveDraft(player, stack, draft);", "draft save action should route through the service");
        requireContains(packet, "case UNDO_LAST -> service.undoLast(player, stack, draft, expectedRevision);", "undo action should route through the service with revision validation");
        requireContains(validator, "map.missing_power_switch", "powered facilities should require a power switch");
        requireContains(validator, "map.spawn_group_never_enabled", "spawn groups without an explicit activation should warn");
        requireContains(validator, "private static void addConditionalRuntimeRequirementIssues", "conditional runtime requirements should be centralized");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static Set<String> deployKeysFromJava() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (Path path : KEY_SOURCE_FILES) {
            Matcher matcher = JAVA_DEPLOY_KEY.matcher(read(path));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        Matcher fields = Pattern.compile("field\\(\"([^\"]+)\"").matcher(read(FIELD_SCHEMA));
        while (fields.find()) {
            keys.add("gui.codpattern.zombies.deploy.field." + fields.group(1));
        }
        return keys;
    }

    private static Set<String> deployKeysFromJson(String json) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = JSON_DEPLOY_KEY.matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static Map<String, String> deployValuesFromJson(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = JSON_DEPLOY_ENTRY.matcher(json);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
    }

    private static void requireContains(String text, String expected, String message) {
        if (!text.contains(expected)) {
            throw new AssertionError(message + ": missing `" + expected + "`");
        }
    }

    private static void requireContains(Set<String> values, String expected, String message) {
        if (!values.contains(expected)) {
            throw new AssertionError(message + ": missing `" + expected + "`");
        }
    }

    private static void requireAbsent(String text, String unexpected, String message) {
        if (text.contains(unexpected)) {
            throw new AssertionError(message + ": found `" + unexpected + "`");
        }
    }

    private static void assertSameDeployKeySet(Map<Path, Set<String>> langKeysByPath) {
        Path baselinePath = null;
        Set<String> baseline = null;
        for (Map.Entry<Path, Set<String>> entry : langKeysByPath.entrySet()) {
            if (baseline == null) {
                baselinePath = entry.getKey();
                baseline = entry.getValue();
                continue;
            }
            Set<String> missing = new LinkedHashSet<>(baseline);
            missing.removeAll(entry.getValue());
            Set<String> extra = new LinkedHashSet<>(entry.getValue());
            extra.removeAll(baseline);
            if (!missing.isEmpty() || !extra.isEmpty()) {
                throw new AssertionError(entry.getKey() + " deploy key set differs from " + baselinePath
                        + "; missing=" + missing + "; extra=" + extra);
            }
        }
    }

    private static void assertSameDeployPlaceholderCounts(Map<Path, Map<String, String>> langValuesByPath) {
        Path baselinePath = null;
        Map<String, String> baseline = null;
        for (Map.Entry<Path, Map<String, String>> entry : langValuesByPath.entrySet()) {
            if (baseline == null) {
                baselinePath = entry.getKey();
                baseline = entry.getValue();
                continue;
            }
            for (Map.Entry<String, String> baselineEntry : baseline.entrySet()) {
                String key = baselineEntry.getKey();
                String value = entry.getValue().get(key);
                if (value == null) {
                    continue;
                }
                int expected = placeholderCount(baselineEntry.getValue());
                int actual = placeholderCount(value);
                if (expected != actual) {
                    throw new AssertionError(entry.getKey() + " placeholder count differs from "
                            + baselinePath + " for " + key + "; expected=" + expected + "; actual=" + actual);
                }
            }
        }
    }

    private static void requireSingleObjectPolicy(String fieldSchema) {
        Map<String, Boolean> expected = Map.of(
                "INITIAL", false,
                "ZOMBIE_SPAWN", false,
                "BARRIER", false,
                "WEAPON_WALL", false,
                "AMMO_BOX", false,
                "ARMOR_STATION", false,
                "POWER_SWITCH", true,
                "SODA_MACHINE", false,
                "ULTIMATE_MACHINE", false);
        for (Map.Entry<String, Boolean> entry : expected.entrySet()) {
            Pattern schemaEntry = Pattern.compile(
                    "new ObjectTypeSchema\\(" + entry.getKey()
                            + ",\\s*\"[^\"]+\",\\s*(?:true|false),\\s*"
                            + entry.getValue()
                            + ",\\s*List\\.of\\(",
                    Pattern.DOTALL);
            if (!schemaEntry.matcher(fieldSchema).find()) {
                throw new AssertionError(entry.getKey() + " singleObject policy must be "
                        + entry.getValue());
            }
        }
    }

    private static int placeholderCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length() - 1; index++) {
            if (value.charAt(index) == '%' && value.charAt(index + 1) == 's') {
                count++;
                index++;
            }
        }
        return count;
    }

}
