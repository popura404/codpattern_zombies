package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraft;
import com.cdp.codpattern.app.zombies.deploy.ZombiesSpawnGroupFields;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployFieldSchema;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeploySnapshot;
import com.cdp.codpattern.app.zombies.model.ZombiesBuffType;
import com.cdp.codpattern.client.zombies.ZombiesDeployClientActionHandler;
import com.cdp.codpattern.client.zombies.ZombiesDeployClientState;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Full-screen object editor. Registration uses a separate Screen and the same session. */
public class ZombiesDeployToolScreen extends Screen {
    private static final int ROW = 26;
    private static final int TEXT = 0xFFE8EFED;
    private static final int MUTED = 0xFF9DAFAA;
    private static final int ACCENT = 0xFF7FD6A0;
    private static final int WARNING = 0xFFFFCD78;
    protected final ZombiesDeployEditorSession session;
    private final List<Label> labels = new ArrayList<>();
    private final Map<String, EditBox> editors = new LinkedHashMap<>();
    private final Map<String, List<String>> listValues = new LinkedHashMap<>();
    // Preserve local empty placeholders through widget rebuilds; they are never saved as group 0.
    private final Map<String, List<String>> spawnGroupRows = new LinkedHashMap<>();
    private boolean splitGroupRowsPending;
    private boolean sodaEffectsExpanded;
    private static final String GROUP_SUMMARY = "@spawn_group_summary";
    private static final String PLAYER_GROUP_SUMMARY = "@player_spawn_group_summary";
    private static final String GROUP_SCOPE = "@spawn_group_scope";
    private int bodyTop, bodyBottom, typeWidth, objectX, objectWidth, fieldX, fieldWidth, issueTop;
    private int totalFieldRows, totalRegistrationRows;
    private boolean compactDetails;
    private Button saveButton, closeButton, undoButton, redoButton;
    private EditBox lastFocused;
    private String focusedKey;
    private String localError = "";

    public ZombiesDeployToolScreen(OpenZombiesDeployToolScreenS2CPacket packet) {
        this(new ZombiesDeployEditorSession(packet.snapshot()));
    }
    protected ZombiesDeployToolScreen(ZombiesDeployEditorSession session) {
        super(Component.translatable("gui.codpattern.zombies.deploy.title"));
        this.session = session;
    }
    protected boolean mapPage() { return false; }
    private ZombiesDeploySnapshot snapshot() { return session.snapshot; }
    protected static String tr(String suffix, Object... args) {
        return Component.translatable("gui.codpattern.zombies.deploy." + suffix, args).getString();
    }
    private static String translated(String key) { return Component.translatable(key).getString(); }

    @Override protected void init() {
        labels.clear(); editors.clear(); listValues.clear(); lastFocused = null;
        bodyTop = 76;
        int footer = width < 420 ? 60 : 34;
        compactDetails = session.details && height < 360;
        issueTop = compactDetails ? bodyTop : height - footer - Math.min(118, Math.max(54, height / 4));
        bodyBottom = Math.max(bodyTop + ROW, session.details && !compactDetails ? issueTop - 8 : height - footer - 16);
        typeWidth = Math.max(68, Math.min(142, width / 6));
        objectX = 12 + typeWidth + 8;
        objectWidth = Math.max(72, Math.min(184, width / 5));
        fieldX = mapPage() ? Math.max(106, Math.min(224, width / 3)) : objectX + objectWidth + 10;
        fieldWidth = Math.max(80, width - fieldX - 12);
        button(mapPage() ? tr("editor.objects") : tr("editor.maps"), 12, 38, Math.min(118, width / 3), () ->
                withCommitted(() -> changePage(!mapPage()))).active = !session.busy() && (mapPage() ? !snapshot().selectedMap().isBlank() : true);
        button(tr("editor.details") + (session.details ? " ▴" : " ▾"), width - 130, 38, 118, () ->
                withCommitted(() -> { session.details = !session.details; rebuild(); }));
        if (!compactDetails) {
            if (mapPage()) { buildMaps(); buildRegistration(); }
            else { buildTypes(); buildObjects(); buildFields(); }
        }
        if (session.details) { buildValidation(); }
        buildFooter(footer);
        if (focusedKey != null && editors.containsKey(focusedKey) && !session.busy()) {
            setInitialFocus(editors.get(focusedKey));
            lastFocused = editors.get(focusedKey);
        }
        updateActions();
    }

    private void rebuild() {
        if (minecraft == null) { return; }
        if (!mapPage() && focusedKey != null) {
            String[] focused = focusedKey.split(":", 2);
            if (focused.length == 2 && ZombiesSpawnGroupFields.isGroupList(focused[0])) {
                List<FieldRow> rows = fieldRows();
                for (int i = 0; i < rows.size(); i++) {
                    FieldRow row = rows.get(i);
                    if (row.keys().contains(focused[0]) && Integer.toString(row.listIndex()).equals(focused[1])) {
                        if (i < session.fieldScroll) { session.fieldScroll = i; }
                        else if (i >= session.fieldScroll + visibleRows()) { session.fieldScroll = i - visibleRows() + 1; }
                        break;
                    }
                }
            }
        }
        rebuildWidgets();
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        Button button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(text, Math.max(12, w - 8))), ignored -> {
            focusedKey = null;
            action.run();
        }).bounds(x, y, Math.max(20, w), 20).build());
        button.setTooltip(Tooltip.create(Component.literal(text)));
        button.active = !session.busy();
        return button;
    }

    private void label(String value, int x, int y, int maxWidth, int color) {
        labels.add(new Label(value, x, y, maxWidth, color));
    }

    private int visibleRows() { return Math.max(1, (bodyBottom - bodyTop) / ROW); }
    private int clamp(int start, int count) { return Math.max(0, Math.min(start, Math.max(0, count - visibleRows()))); }

    private void buildMaps() {
        session.mapScroll = clamp(session.mapScroll, snapshot().availableMaps().size());
        int w = fieldX - 24;
        for (int row = 0; row < visibleRows(); row++) {
            int index = session.mapScroll + row;
            if (index >= snapshot().availableMaps().size()) { break; }
            String name = snapshot().availableMaps().get(index);
            // Map rows intentionally contain only the name. Selection is painted underneath.
            button(name, 12, bodyTop + row * ROW, w, () -> selectMap(name));
        }
        if (snapshot().availableMaps().isEmpty()) {
            label(tr("no_registered_maps"), 12, bodyTop + 6, w, MUTED);
        }
    }

    private void buildRegistration() {
        totalRegistrationRows = 10;
        session.registrationScroll = clamp(session.registrationScroll, totalRegistrationRows);
        registrationLabel(0, tr("editor.selected_map", snapshot().selectedMap().isBlank() ? "—" : snapshot().selectedMap()));
        registrationLabel(1, tr("editor.registration"));
        int y = registrationY(2);
        if (rowVisible(2, session.registrationScroll)) {
            label(tr("editor.map_name"), fieldX, y + 6, fieldWidth / 3, TEXT);
            edit("mapName", session.mapName, fieldX + fieldWidth / 3, y, fieldWidth * 2 / 3, value -> session.mapName = value, true);
        }
        registrationCorner(3, tr("editor.corner_a"), session.cornerA);
        registrationCorner(4, tr("editor.corner_b"), session.cornerB);
        registrationLabel(5, tr("editor.capture_hint"));
        if (rowVisible(6, session.registrationScroll)) {
            button(tr("create_map"), fieldX, registrationY(6), Math.min(160, fieldWidth), () -> {
                if (!validCorners()) { return; }
                if (session.mapName.isBlank() || ZombiesDeployEditorSession.empty(session.cornerA) || ZombiesDeployEditorSession.empty(session.cornerB)) {
                    localError = tr("editor.registration_required"); return;
                }
                ZombiesDeployDraft registration = session.draft(false);
                Runnable create = () -> send(Action.CREATE_MAP, registration, this::routePage);
                if (!snapshot().selectedMap().isBlank() && snapshot().undoCount() > 0 && snapshot().dirty()) {
                    minecraft.setScreen(new ZombiesDeployUnsavedChangesScreen(this,
                            () -> send(Action.DISCARD_DRAFT, discardRequest(), create)));
                } else { create.run(); }
            });
        }
        registrationLabel(7, tr("editor.registration_immediate"));
        if (snapshot().registeredMapPos1() != null) {
            registrationLabel(8, tr("editor.saved_bounds_a", snapshot().registeredMapPos1().toShortString()));
            registrationLabel(9, tr("editor.saved_bounds_b", snapshot().registeredMapPos2().toShortString()));
        }
    }
    private int registrationY(int row) { return bodyTop + (row - session.registrationScroll) * ROW; }
    private boolean rowVisible(int row, int scroll) { return row >= scroll && row < scroll + visibleRows(); }
    private void registrationLabel(int row, String text) {
        if (rowVisible(row, session.registrationScroll)) { label(text, fieldX, registrationY(row) + 6, fieldWidth, MUTED); }
    }
    private void registrationCorner(int row, String text, String[] corner) {
        if (!rowVisible(row, session.registrationScroll)) { return; }
        int y = registrationY(row), labelWidth = Math.min(70, fieldWidth / 4), cell = (fieldWidth - labelWidth) / 3;
        label(text, fieldX, y + 6, labelWidth - 4, TEXT);
        for (int i = 0; i < 3; i++) {
            int axis = i;
            EditBox box = edit(text + i, corner[i], fieldX + labelWidth + i * cell, y, cell - 4, value -> corner[axis] = value, true);
            box.setHint(Component.literal("XYZ".substring(i, i + 1)));
        }
    }

    private void buildTypes() {
        session.typeScroll = clamp(session.typeScroll, snapshot().objectTypes().size());
        for (int row = 0; row < visibleRows(); row++) {
            int index = session.typeScroll + row;
            if (index >= snapshot().objectTypes().size()) { break; }
            var option = snapshot().objectTypes().get(index);
            int count = snapshot().objectCounts().stream().filter(it -> it.objectType().equals(option.key())).mapToInt(ZombiesDeploySnapshot.ObjectTypeCount::count).sum();
            button(translated(option.labelKey()) + "  " + count, 12, bodyTop + row * ROW, typeWidth, () -> withCommitted(() -> {
                session.objectScroll = 0; session.fieldScroll = 0;
                send(Action.SELECT_OBJECT_TYPE, selection(snapshot().selectedMap(), option.key(), -1, false), null);
            }));
        }
    }

    private void buildObjects() {
        int objectRows = Math.max(1, visibleRows() - 2);
        session.objectScroll = Math.max(0, Math.min(session.objectScroll, snapshot().objects().size() - objectRows));
        for (int row = 0; row < objectRows; row++) {
            int index = session.objectScroll + row;
            if (index >= snapshot().objects().size()) { break; }
            var object = snapshot().objects().get(index);
            button(object.primary(), objectX, bodyTop + row * ROW, objectWidth, () -> withCommitted(() -> {
                session.fieldScroll = 0;
                send(Action.SELECT_OBJECT, selection(snapshot().selectedMap(), snapshot().selectedObjectType(), object.index(), false), null);
            })).setTooltip(Tooltip.create(Component.literal(object.primary() + "\n" + object.detail())));
        }
        int y = bodyBottom - 46;
        button(tr("add"), objectX, y, objectWidth, () -> withCommitted(() -> send(Action.ADD_OBJECT, session.draft(true), null)));
        button(tr("duplicate"), objectX, y + 24, objectWidth / 2 - 2,
                () -> withCommitted(() -> send(Action.DUPLICATE_OBJECT, session.draft(true), null))).active = !session.busy() && snapshot().selectedIndex() >= 0;
        button(tr("delete"), objectX + objectWidth / 2 + 2, y + 24, objectWidth / 2 - 2,
                () -> withCommitted(() -> send(Action.DELETE_OBJECT, session.draft(true), null))).active = !session.busy() && snapshot().selectedIndex() >= 0;
    }

    private void buildFields() {
        List<FieldRow> rows = fieldRows();
        totalFieldRows = rows.size();
        session.fieldScroll = clamp(session.fieldScroll, rows.size());
        for (int row = session.fieldScroll; row < Math.min(rows.size(), session.fieldScroll + visibleRows()); row++) {
            FieldRow value = rows.get(row);
            int y = bodyTop + (row - session.fieldScroll) * ROW;
            if (value.keys().isEmpty()) { label(value.label(), fieldX, y + 6, fieldWidth, ACCENT); continue; }
            int labelWidth = Math.min(128, Math.max(40, fieldWidth / 3));
            label(value.label(), fieldX, y + 6, labelWidth - 8, TEXT);
            int inputX = fieldX + labelWidth;
            int inputWidth = fieldWidth - labelWidth;
            String key = value.keys().get(0);
            var schema = snapshot().fields().stream().filter(it -> it.key().equals(key)).findFirst().orElseThrow();
            if (isSodaEffect(key)) {
                buildSodaEffect(value, schema, inputX, y, inputWidth);
            } else if (ZombiesSpawnGroupFields.isGroupList(key) && value.listIndex() == -2) {
                button("+", inputX, y, 28, () -> {
                    listRows(key).add("");
                    focusedKey = key + ":" + (listRows(key).size() - 1);
                    rebuild();
                }).active = !session.busy();
            } else if (ZombiesSpawnGroupFields.isGroupList(key) && value.listIndex() >= 0) {
                List<String> values = listRows(key);
                int index = value.listIndex();
                EditBox box = edit(key + ":" + index, values.get(index), inputX, y, inputWidth - 30, text -> {
                    List<String> pasted = ZombiesSpawnGroupFields.rows(text);
                    if (pasted.size() > 1) {
                        values.remove(index);
                        values.addAll(index, pasted);
                        focusedKey = key + ":" + (index + pasted.size() - 1);
                        splitGroupRowsPending = true;
                    } else { values.set(index, text); }
                    session.fields.put(key, String.join(";", values));
                }, schema.editable());
                box.setHint(Component.literal(tr("spawn_groups.row_hint")));
                button("−", inputX + inputWidth - 26, y, 26, () -> {
                    values.remove(index);
                    session.fields.put(key, String.join(";", values));
                    focusedKey = null;
                    rebuild();
                }).active = !session.busy();
            } else if (value.listIndex() == -2) {
                button("+", inputX, y, 28, () -> withCommitted(() -> {
                    // A new row is local until it contains a value; never stage a malformed placeholder.
                    List<String> values = new ArrayList<>(listRows(key));
                    values.add("");
                    session.fields.put(key, String.join(delimiter(key), values));
                    rebuild();
                }));
            } else if (value.listIndex() >= 0) {
                List<String> values = listValues.computeIfAbsent(key, this::listRows);
                int index = value.listIndex();
                edit(key + ":" + index, values.get(index), inputX, y, inputWidth - 30, text -> {
                    values.set(index, text); session.fields.put(key, String.join(delimiter(key), values));
                }, schema.editable());
                button("−", inputX + inputWidth - 26, y, 26, () -> {
                    values.remove(index);
                    session.fields.put(key, String.join(delimiter(key), values));
                    withCommitted(this::rebuild);
                });
            } else if (key.equals("facing")) {
                List<String> directions = List.of("north", "east", "south", "west");
                String facing = session.fields.getOrDefault(key, "north");
                button(tr("facing." + facing), inputX, y, Math.min(inputWidth, 96), () -> {
                    session.fields.put(key, directions.get((directions.indexOf(facing) + 1) % directions.size()));
                    withCommitted(this::rebuild);
                }).active = schema.editable() && !session.busy();
            } else if (schema.type() == ZombiesDeployFieldSchema.FieldType.BOOLEAN) {
                boolean checked = Boolean.parseBoolean(session.fields.get(key)) || "1".equals(session.fields.get(key));
                button(tr(checked ? "editor.on" : "editor.off"), inputX, y, Math.min(inputWidth, 96), () -> {
                    session.fields.put(key, Boolean.toString(!checked)); withCommitted(this::rebuild);
                }).active = schema.editable() && !session.busy();
            } else {
                int cell = inputWidth / value.keys().size();
                for (int i = 0; i < value.keys().size(); i++) {
                    String field = value.keys().get(i);
                    EditBox box = edit(field, session.fields.get(field), inputX + i * cell, y, cell - (value.keys().size() > 1 ? 4 : 0),
                            text -> session.fields.put(field, text), schema.editable());
                    if (value.keys().size() == 3) { box.setHint(Component.literal("XYZ".substring(i, i + 1))); }
                }
            }
        }
    }

    private boolean isSodaEffect(String key) {
        return key.equals("buffId") && snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.SODA_MACHINE);
    }

    private String sodaEffectName(ZombiesBuffType type) {
        String key = "gui.codpattern.zombies.deploy.soda_effect." + type.id();
        return I18n.exists(key) ? translated(key) : type.id();
    }

    private void buildSodaEffect(FieldRow row, ZombiesDeploySnapshot.FieldValue schema, int x, int y, int w) {
        String current = session.fields.getOrDefault(schema.key(), "");
        ZombiesBuffType selected = ZombiesBuffType.fromId(current).orElse(null);
        ZombiesBuffType option = row.sodaEffect();
        if (option == null) {
            String name = selected == null ? tr("soda_effect.unknown", current) : sodaEffectName(selected);
            Button selector = button(name + (sodaEffectsExpanded ? " ▴" : " ▾"), x, y, w, () -> withCommitted(() -> {
                sodaEffectsExpanded = !sodaEffectsExpanded;
                if (sodaEffectsExpanded) {
                    List<FieldRow> rows = fieldRows();
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).keys().contains(schema.key())) {
                            // Bring the choices into view even when the selector was at the bottom.
                            int shownRows = Math.min(visibleRows(), ZombiesBuffType.values().length + 1);
                            session.fieldScroll = Math.max(session.fieldScroll, i + shownRows - visibleRows());
                            break;
                        }
                    }
                }
                rebuild();
            }));
            selector.active = schema.editable() && !session.busy();
            selector.setTooltip(Tooltip.create(Component.literal(name + "\n" + current + "\n"
                    + tr(sodaEffectsExpanded ? "soda_effect.hide" : "soda_effect.choose"))));
        } else {
            Button choice = button((option == selected ? "● " : "○ ") + sodaEffectName(option), x, y, w, () -> {
                session.fields.put(schema.key(), option.id());
                sodaEffectsExpanded = false;
                // Reflect the local choice even if another field prevents the server commit.
                rebuild();
                withCommitted(() -> { });
            });
            choice.active = schema.editable() && !session.busy();
            choice.setTooltip(Tooltip.create(Component.literal(sodaEffectName(option) + "\n" + option.id())));
        }
    }

    private List<FieldRow> fieldRows() {
        List<FieldRow> result = new ArrayList<>();
        List<ZombiesDeploySnapshot.FieldValue> fields = new ArrayList<>(snapshot().fields());
        fields.removeIf(field -> field.key().startsWith("@barrierRules."));
        fields.sort(Comparator.comparingInt(field -> group(field.key())));
        int previous = -1;
        for (var field : fields) {
            int group = group(field.key());
            if (group != previous) {
                result.add(new FieldRow(tr("editor.group." + group), List.of(), -1)); previous = group;
            }
            String key = field.key();
            if ((key.endsWith("Y") || key.endsWith("Z")) && session.fields.containsKey(key.substring(0, key.length() - 1) + "X")) { continue; }
            if (key.endsWith("X") && session.fields.containsKey(key.substring(0, key.length() - 1) + "Z")) {
                String prefix = key.substring(0, key.length() - 1);
                result.add(new FieldRow(tr("editor.coordinate." + prefix), List.of(prefix + "X", prefix + "Y", prefix + "Z"), -1));
            } else if (field.type() == ZombiesDeployFieldSchema.FieldType.LIST) {
                result.add(new FieldRow(translated(field.labelKey()), List.of(key), -2));
                if (ZombiesSpawnGroupFields.isGroupList(key)) {
                    result.add(new FieldRow(tr("spawn_groups.hint"), List.of(), -1));
                }
                List<String> values = listRows(key);
                if (ZombiesSpawnGroupFields.isGroupList(key) && values.isEmpty()) {
                    result.add(new FieldRow(tr("spawn_groups.none"), List.of(), -1));
                }
                for (int i = 0; i < values.size(); i++) { result.add(new FieldRow("#" + (i + 1), List.of(key), i)); }
            } else {
                String label = key.equals("group") && snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.BARRIER)
                        ? tr("spawn_groups.barrier_group")
                        : key.equals("group") && snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.ZOMBIE_SPAWN)
                        ? tr("spawn_groups.spawn_group")
                        : key.equals("group") && snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.INITIAL)
                        ? tr("spawn_groups.player_group") : translated(field.labelKey());
                result.add(new FieldRow(label, List.of(key), -1));
                if (isSodaEffect(key) && sodaEffectsExpanded) {
                    for (ZombiesBuffType type : ZombiesBuffType.values()) {
                        result.add(new FieldRow("", List.of(key), -1, type));
                    }
                }
            }
        }
        if (snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.BARRIER)) {
            result.add(new FieldRow(GROUP_SUMMARY, List.of(), -1));
            result.add(new FieldRow(PLAYER_GROUP_SUMMARY, List.of(), -1));
            result.add(new FieldRow(GROUP_SCOPE, List.of(), -1));
        } else if (snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.INITIAL)) {
            result.add(new FieldRow(tr("spawn_groups.player_hint"), List.of(), -1));
        }
        return result;
    }
    private static int group(String key) {
        if (key.equals("objectId") || key.equals("name")) { return 0; }
        if (key.equals("dimension") || key.equals("facing") || key.equals("yaw") || key.equals("pitch") || key.matches("(?:pos|areaFrom|areaTo|interaction)[XYZ]")) { return 1; }
        return 2;
    }
    private static String delimiter(String key) { return key.equals("pricesByWeaponLevel") ? "," : ";"; }
    private List<String> listRows(String key) {
        if (ZombiesSpawnGroupFields.isGroupList(key)) {
            return spawnGroupRows.computeIfAbsent(key, ignored -> new ArrayList<>(
                    ZombiesSpawnGroupFields.rows(session.fields.getOrDefault(key, ""))));
        }
        String value = session.fields.getOrDefault(key, "");
        if (value.isEmpty()) { return new ArrayList<>(List.of("")); }
        return new ArrayList<>(Arrays.asList(value.split(key.equals("pricesByWeaponLevel") ? "[,;\\n\\r]" : "[;\\n\\r]", -1)));
    }

    private EditBox edit(String key, String value, int x, int y, int w, Consumer<String> changed, boolean editable) {
        boolean groupList = ZombiesSpawnGroupFields.isGroupList(key.split(":", 2)[0]);
        EditBox box = addRenderableWidget(new EditBox(font, x, y, Math.max(18, w), 20, Component.literal(key)) {
            @Override public void insertText(String text) {
                // Vanilla EditBox strips newlines before the responder sees pasted text.
                super.insertText(groupList ? text.replace("\r\n", ";").replace('\r', ';').replace('\n', ';') : text);
            }
        });
        box.setMaxLength(2048);
        box.setValue(Objects.requireNonNullElse(value, ""));
        box.setEditable(editable && !session.busy());
        box.active = !session.busy();
        box.setResponder(text -> {
            changed.accept(text);
            localError = "";
            box.setTooltip(Tooltip.create(Component.literal(text)));
        });
        box.setTooltip(Tooltip.create(Component.literal(box.getValue())));
        editors.put(key, box);
        return box;
    }

    private void buildValidation() {
        int y = issueTop;
        int profileWidth = Math.min(166, width / 3);
        button(tr("view_profile", profileLabel()), 12, y, profileWidth, () -> withCommitted(() -> {
            var profiles = snapshot().availableProfiles();
            String profile = profiles.get((profiles.indexOf(snapshot().profileKey()) + 1) % profiles.size());
            ZombiesDeployDraft draft = session.draft(false);
            send(Action.SAVE_SELECTIONS, draft.withSelection(draft.selectedMap(), draft.objectType(), draft.selectedIndex(), profile), null);
        }));
        button(tr("validate"), 20 + profileWidth, y, 78, () -> withCommitted(() -> send(Action.VALIDATE_MAP, session.draft(true), null)));
        int count = Math.max(1, (height - (width < 420 ? 60 : 34) - y - 40) / 20);
        session.issueScroll = Math.max(0, Math.min(session.issueScroll, snapshot().validationLines().size() - count));
        for (int i = 0; i < count; i++) {
            int index = session.issueScroll + i;
            if (index >= snapshot().validationLines().size()) { break; }
            var issue = snapshot().validationLines().get(index);
            String text = (issue.severity().equalsIgnoreCase("error") ? "! " : "△ ") + issue.subject() + "  " + issue.message();
            button(text, 12, y + 26 + i * 20, width - 24, () -> withCommitted(() -> jumpToIssue(index)));
        }
    }
    private String profileLabel() {
        return tr(snapshot().profileKey().equals(ZombiesDeployFieldSchema.PROFILE_MVP1) ? "editor.profile.minimal" :
                snapshot().profileKey().equals(ZombiesDeployFieldSchema.PROFILE_MVP2) ? "editor.profile.purchases" : "editor.profile.full");
    }
    private void jumpToIssue(int index) {
        if (index >= snapshot().issueTargets().size()) { return; }
        var target = snapshot().issueTargets().get(index);
        session.fieldScroll = 0;
        session.objectScroll = Math.max(0, target.targetIndex() - 1);
        send(Action.SAVE_SELECTIONS, selection(snapshot().selectedMap(), target.targetObjectType(), target.targetIndex(), target.mapStage()), this::routePage);
    }

    private void buildFooter(int footer) {
        int perRow = width < 420 ? 3 : 6;
        int cell = (width - 24) / perRow;
        int y = height - footer + 5;
        undoButton = button(tr("undo") + " " + snapshot().undoCount(), 12, y, cell - 4, () -> withCommitted(() -> send(Action.UNDO_LAST, session.draft(false), null)));
        redoButton = button(tr("editor.redo") + " " + snapshot().redoCount(), 12 + cell, y, cell - 4, () -> withCommitted(() -> send(Action.REDO_LAST, session.draft(false), null)));
        saveButton = button(tr("editor.save"), 12 + cell * 2, y, cell - 4, () -> withCommitted(() -> send(Action.SAVE_DRAFT, session.draft(true), null)));
        int nextY = perRow == 3 ? y + 25 : y;
        int nextX = perRow == 3 ? 12 : 12 + cell * 3;
        button(tr("editor.collapse"), nextX, nextY, cell - 4, this::collapse);
        closeButton = button(tr("editor.close"), nextX + cell, nextY, cell * 2 - 4, this::onClose);
    }

    private void updateActions() {
        if (saveButton == null) { return; }
        undoButton.active = !session.busy() && (snapshot().undoCount() > 0 || (!mapPage() && session.fieldsChanged()));
        redoButton.active = !session.busy() && snapshot().redoCount() > 0;
        saveButton.active = !session.busy() && !snapshot().selectedMap().isBlank() && !session.registrationDirty();
        closeButton.setMessage(Component.literal(tr(session.dirty() ? "editor.close_unsaved" : "editor.close")));
        closeButton.setTooltip(Tooltip.create(Component.literal(tr(session.dirty() ? "editor.close_warning" : "editor.close"))));
    }

    private void send(Action action, ZombiesDeployDraft draft, Runnable next) {
        localError = "";
        session.send(action, draft, next);
        rebuild();
    }

    private boolean validCorners() {
        try { ZombiesDeployEditorSession.corner(session.cornerA); ZombiesDeployEditorSession.corner(session.cornerB); return true; }
        catch (NumberFormatException exception) { localError = tr("editor.invalid_coordinates"); return false; }
    }

    private void withCommitted(Runnable next) {
        if (session.busy() || !validCorners()) { return; }
        if (session.fieldsChanged() && !mapPage()) {
            if (snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.BARRIER)) {
                try { ZombiesSpawnGroupFields.parse(session.fields); }
                catch (ZombiesSpawnGroupFields.InvalidGroups error) { localError = groupInputError(error); return; }
            }
            for (var field : snapshot().fields()) {
                if (!field.editable()) { continue; }
                String value = session.fields.getOrDefault(field.key(), "");
                try {
                    if (field.type() == ZombiesDeployFieldSchema.FieldType.INTEGER) { Integer.parseInt(value); }
                    if (field.type() == ZombiesDeployFieldSchema.FieldType.DECIMAL && !Double.isFinite(Double.parseDouble(value))) { throw new NumberFormatException(); }
                } catch (NumberFormatException exception) {
                    localError = tr("editor.invalid_field", translated(field.labelKey())); return;
                }
            }
            if (snapshot().selectedIndex() >= 0) { send(Action.UPDATE_OBJECT, session.draft(true), next); }
            else { send(Action.SET_FIELD, session.draft(true), next); }
        } else { next.run(); }
    }

    private ZombiesDeployDraft selection(String map, String type, int index, boolean registration) {
        return new ZombiesDeployDraft(registration ? ZombiesDeployDraft.STAGE_MAP_REGISTRATION : ZombiesDeployDraft.STAGE_OBJECT_MARKING,
                ZombiesDeployDraft.workflowStepForObjectType(type), map, session.mapName,
                ZombiesDeployEditorSession.corner(session.cornerA), ZombiesDeployEditorSession.corner(session.cornerB),
                type, ZombiesDeployDraft.CAPTURE_DEFAULT, index, snapshot().profileKey(), Map.of());
    }

    private void changePage(boolean registration) {
        send(Action.SAVE_SELECTIONS, session.draft(true).withWorkspaceStage(registration
                ? ZombiesDeployDraft.STAGE_MAP_REGISTRATION : ZombiesDeployDraft.STAGE_OBJECT_MARKING), this::routePage);
    }

    private void selectMap(String name) {
        if (name.equals(snapshot().selectedMap()) || session.busy()) { return; }
        Runnable switchMap = () -> {
            session.fieldScroll = 0; session.objectScroll = 0;
            send(Action.SAVE_SELECTIONS, selection(name, snapshot().selectedObjectType(), -1, true), null);
        };
        Runnable discardAndSwitch = () -> send(Action.DISCARD_DRAFT, discardRequest(), switchMap);
        if (session.dirty()) { minecraft.setScreen(new ZombiesDeployUnsavedChangesScreen(this, discardAndSwitch)); }
        else { discardAndSwitch.run(); }
    }

    private ZombiesDeployDraft discardRequest() {
        // Closing must work even when the local coordinate text cannot be parsed.
        var value = snapshot();
        return new ZombiesDeployDraft(value.workspaceStage(), value.currentWorkflowStep(), value.selectedMap(), "", null, null,
                value.selectedObjectType(), value.capturePreset(), value.selectedIndex(), value.profileKey(), Map.of());
    }

    private void collapse() {
        withCommitted(() -> send(Action.SAVE_SELECTIONS, session.draft(true), () -> {
            session.suspended = true;
            minecraft.setScreen(null);
        }));
    }

    @Override public void onClose() {
        if (session.busy()) { return; }
        Runnable close = () -> send(Action.DISCARD_DRAFT, discardRequest(), () -> {
            ZombiesDeployClientActionHandler.clearEditor(this);
            minecraft.setScreen(null);
        });
        if (session.dirty()) { minecraft.setScreen(new ZombiesDeployUnsavedChangesScreen(this, close)); }
        else { close.run(); }
    }

    public void applyData(OpenZombiesDeployToolScreenS2CPacket packet) {
        if (!session.accepts(packet)) { return; }
        boolean hadPending = session.busy();
        // Never replace unsubmitted local text with an unrelated refresh.
        if (!hadPending && !session.suspended && !packet.openScreen() && session.fieldsChanged()) { return; }
        Map<String, String> oldFields = new LinkedHashMap<>(session.fields);
        ZombiesDeploySnapshot previous = snapshot();
        Runnable next = session.complete(packet);
        if (!previous.selectedMap().equals(snapshot().selectedMap())
                || !previous.selectedObjectType().equals(snapshot().selectedObjectType())
                || previous.selectedIndex() != snapshot().selectedIndex()) {
            sodaEffectsExpanded = false;
        }
        String code = snapshot().statusCode();
        if (hadPending && (code.startsWith("object.") && !code.equals("object.field_staged") && !code.equals("object.field_updated"))) {
            session.fields.putAll(oldFields);
        }
        spawnGroupRows.clear();
        splitGroupRowsPending = false;
        ZombiesDeployClientState.update(snapshot());
        rebuild();
        if (next != null) { next.run(); }
        else if (minecraft.screen instanceof ZombiesDeployUnsavedChangesScreen && hadPending) { minecraft.setScreen(this); }
    }

    public void reopen(OpenZombiesDeployToolScreenS2CPacket packet) {
        session.suspended = false;
        applyData(packet);
        routePage();
    }

    private void routePage() {
        boolean registration = snapshot().workspaceStage().equals(ZombiesDeployDraft.STAGE_MAP_REGISTRATION);
        ZombiesDeployToolScreen next = registration == mapPage() ? this :
                registration ? new ZombiesDeployMapScreen(session) : new ZombiesDeployToolScreen(session);
        ZombiesDeployClientActionHandler.setEditor(next);
        minecraft.setScreen(next);
    }

    @Override public void tick() {
        if (splitGroupRowsPending && !session.busy()) {
            splitGroupRowsPending = false;
            rebuild();
        }
        for (EditBox box : editors.values()) { box.tick(); }
        if (!session.busy()) {
            EditBox focus = getFocused() instanceof EditBox box ? box : null;
            focusedKey = editors.entrySet().stream().filter(entry -> entry.getValue() == focus).map(Map.Entry::getKey).findFirst().orElse(null);
            if (lastFocused != null && lastFocused != focus && !mapPage() && session.fieldsChanged()) { withCommitted(() -> { }); }
            lastFocused = focus;
        }
        updateActions();
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (session.busy() || delta == 0) { return true; }
        int direction = delta > 0 ? -1 : 1;
        if (!mapPage() && !compactDetails && snapshot().selectedObjectType().equals(ZombiesDeployFieldSchema.BARRIER)
                && x >= fieldX && y >= bodyTop && y < bodyBottom) {
            // Scrolling must stay possible while correcting an invalid/conflicting group on another row.
            // Text already lives in the local draft; only navigation and save need a server commit.
            session.fieldScroll = clamp(session.fieldScroll + direction, totalFieldRows);
            focusedKey = null;
            lastFocused = null;
            rebuild();
            return true;
        }
        withCommitted(() -> {
            if (session.details && y >= issueTop) { session.issueScroll = Math.max(0, session.issueScroll + direction); }
            else if (y >= bodyTop && y < bodyBottom) {
                if (mapPage()) {
                    if (x < fieldX) { session.mapScroll = clamp(session.mapScroll + direction, snapshot().availableMaps().size()); }
                    else { session.registrationScroll = clamp(session.registrationScroll + direction, totalRegistrationRows); }
                } else if (x < objectX) { session.typeScroll = clamp(session.typeScroll + direction, snapshot().objectTypes().size()); }
                else if (x < fieldX) { session.objectScroll = Math.max(0, session.objectScroll + direction); }
                else { session.fieldScroll = clamp(session.fieldScroll + direction, totalFieldRows); }
            }
            focusedKey = null; rebuild();
        });
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (session.busy()) { return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { withCommitted(() -> { }); return true; }
        if (hasControlDown() && key == GLFW.GLFW_KEY_S) {
            if (saveButton.active) { withCommitted(() -> send(Action.SAVE_DRAFT, session.draft(true), null)); }
            return true;
        }
        if (hasControlDown() && (key == GLFW.GLFW_KEY_Z || key == GLFW.GLFW_KEY_Y) && !(getFocused() instanceof EditBox)) {
            Action action = key == GLFW.GLFW_KEY_Y || hasShiftDown() ? Action.REDO_LAST : Action.UNDO_LAST;
            withCommitted(() -> send(action, session.draft(false), null)); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xA512191D);
        graphics.fill(0, 0, width, 65, 0x6812191D);
        graphics.drawString(font, title, 12, 12, TEXT, false);
        String status = readiness();
        graphics.drawString(font, font.plainSubstrByWidth(status, width - 24), 12, 25, session.busy() ? MUTED : snapshot().validationSummaries().stream().anyMatch(it -> it.profileKey().equals(ZombiesDeployFieldSchema.PROFILE_MVP3) && it.errors() > 0) ? WARNING : ACCENT, false);
        if (!compactDetails) {
            graphics.fill(fieldX - 6, bodyTop, fieldX - 5, bodyBottom, 0x405B796D);
            drawSelection(graphics);
        }
        for (Label label : labels) {
            graphics.drawString(font, font.plainSubstrByWidth(labelText(label), Math.max(8, label.width())), label.x(), label.y(), label.color(), false);
        }
        String statusMessage = !localError.isBlank() ? localError : session.busy() ? tr("waiting_response") : feedback();
        int statusY = height - (width < 420 ? 60 : 34) - 10;
        graphics.drawString(font, font.plainSubstrByWidth(statusMessage, width - 24), 12, statusY, WARNING, false);
        if (mouseY >= statusY && mouseY < statusY + 10 && !statusMessage.isBlank()) {
            graphics.renderTooltip(font, Component.literal(statusMessage), mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        for (Label label : labels) {
            if (font.width(labelText(label)) > label.width() && mouseX >= label.x() && mouseX < label.x() + label.width()
                    && mouseY >= label.y() && mouseY <= label.y() + 12) {
                graphics.renderTooltip(font, Component.literal(labelText(label)), mouseX, mouseY); break;
            }
        }
        if (!compactDetails) { drawScrollbars(graphics); }
    }

    private String groupInputError(ZombiesSpawnGroupFields.InvalidGroups error) {
        return error.conflict() ? tr("spawn_groups.conflict", error.entry())
                : tr("spawn_groups.invalid", translated(ZombiesDeployFieldSchema.labelKeyForField(error.field())), error.entry());
    }

    private String barrierRuleMetadata(String key) {
        return snapshot().fields().stream().filter(field -> field.key().equals("@barrierRules." + key))
                .map(ZombiesDeploySnapshot.FieldValue::value).findFirst().orElse("");
    }

    private String labelText(Label label) {
        if (GROUP_SCOPE.equals(label.text())) return tr("barrier_rules.source", barrierRuleMetadata("path"));
        if (!GROUP_SUMMARY.equals(label.text()) && !PLAYER_GROUP_SUMMARY.equals(label.text())) return label.text();
        String error = barrierRuleMetadata("error");
        if (!error.isBlank()) return tr("barrier_rules.invalid", error);
        String group = session.fields.getOrDefault("group", "").trim();
        String entryId = session.fields.getOrDefault("entryId", "").trim();
        String binding = group + "." + entryId;
        String cost = barrierRuleMetadata("cost." + binding);
        if (cost.isBlank()) return tr("barrier_rules.missing_entry", group, entryId);
        if (PLAYER_GROUP_SUMMARY.equals(label.text())) {
            String enablePlayer = barrierRuleMetadata("enablePlayer." + group);
            String disablePlayer = barrierRuleMetadata("disablePlayer." + group);
            return tr("barrier_rules.player_summary",
                    enablePlayer.isBlank() ? tr("spawn_groups.none") : enablePlayer,
                    disablePlayer.isBlank() ? tr("spawn_groups.none") : disablePlayer);
        }
        String required = barrierRuleMetadata("requiredItem." + binding);
        String itemName = required.isBlank() ? tr("spawn_groups.none")
                : com.cdp.codpattern.app.zombies.item.ZombiesRequiredItem.displayName(required).getString();
        String enable = barrierRuleMetadata("enable." + group);
        String disable = barrierRuleMetadata("disable." + group);
        return tr("barrier_rules.entry_summary", cost, itemName,
                enable.isBlank() ? tr("spawn_groups.none") : enable,
                disable.isBlank() ? tr("spawn_groups.none") : disable);
    }

    private String readiness() {
        if (snapshot().selectedMap().isBlank()) { return tr("no_registered_maps"); }
        if (session.busy()) { return tr("editor.checking"); }
        var full = snapshot().validationSummaries().stream().filter(value -> value.profileKey().equals(ZombiesDeployFieldSchema.PROFILE_MVP3)).findFirst();
        if (full.isEmpty()) { return tr("editor.checking"); }
        var result = full.get();
        return tr(result.errors() == 0 ? "editor.runnable" : "editor.unrunnable")
                + "  " + tr("editor.issue_count", result.errors(), result.warnings())
                + (session.dirty() ? "  ·  " + tr("editor.unsaved") : "") + "  ·  " + snapshot().selectedMap();
    }
    private String feedback() {
        if (snapshot().statusKey().isBlank()) { return ""; }
        return Component.translatable(snapshot().statusKey(), snapshot().statusDetail()).getString();
    }
    private void drawSelection(GuiGraphics graphics) {
        if (mapPage()) {
            int index = snapshot().availableMaps().indexOf(snapshot().selectedMap()) - session.mapScroll;
            selectionMark(graphics, 10, index, visibleRows());
        } else {
            int index = -1;
            for (int i = 0; i < snapshot().objectTypes().size(); i++) { if (snapshot().objectTypes().get(i).key().equals(snapshot().selectedObjectType())) { index = i; } }
            selectionMark(graphics, 10, index - session.typeScroll, visibleRows());
            int object = -1;
            for (int i = 0; i < snapshot().objects().size(); i++) { if (snapshot().objects().get(i).index() == snapshot().selectedIndex()) { object = i; } }
            selectionMark(graphics, objectX - 2, object - session.objectScroll, Math.max(1, visibleRows() - 2));
        }
    }
    private void selectionMark(GuiGraphics graphics, int x, int row, int count) {
        if (row >= 0 && row < count) { graphics.fill(x - 2, bodyTop + row * ROW, x, bodyTop + row * ROW + 20, ACCENT); }
    }
    private void drawScrollbars(GuiGraphics graphics) {
        if (mapPage()) { scrollbar(graphics, fieldX - 10, session.mapScroll, snapshot().availableMaps().size()); scrollbar(graphics, width - 5, session.registrationScroll, totalRegistrationRows); }
        else { scrollbar(graphics, objectX - 5, session.typeScroll, snapshot().objectTypes().size()); scrollbar(graphics, fieldX - 8, session.objectScroll, snapshot().objects().size() + 2); scrollbar(graphics, width - 5, session.fieldScroll, totalFieldRows); }
    }
    private void scrollbar(GuiGraphics graphics, int x, int start, int total) {
        if (total <= visibleRows()) { return; }
        int h = bodyBottom - bodyTop;
        int thumb = Math.max(10, h * visibleRows() / total);
        int y = bodyTop + (h - thumb) * start / Math.max(1, total - visibleRows());
        graphics.fill(x, bodyTop, x + 2, bodyBottom, 0x305B796D);
        graphics.fill(x, y, x + 2, y + thumb, 0xAA7FD6A0);
    }
    private record Label(String text, int x, int y, int width, int color) { }
    private record FieldRow(String label, List<String> keys, int listIndex, ZombiesBuffType sodaEffect) {
        private FieldRow(String label, List<String> keys, int listIndex) { this(label, keys, listIndex, null); }
    }
}
