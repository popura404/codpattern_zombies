package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraft;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeploySnapshot;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket.Action;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Shared by both pages; only one mutation can be in flight at a time. */
final class ZombiesDeployEditorSession {
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    ZombiesDeploySnapshot snapshot;
    final Map<String, String> fields = new LinkedHashMap<>();
    String mapName;
    final String[] cornerA = new String[3];
    final String[] cornerB = new String[3];
    int mapScroll, typeScroll, objectScroll, fieldScroll, registrationScroll, issueScroll;
    boolean details;
    boolean suspended;
    private Action pending;
    private long requestId;
    private long lastCompletedRequest;
    private Runnable completion;

    ZombiesDeployEditorSession(ZombiesDeploySnapshot snapshot) { accept(snapshot); }

    void accept(ZombiesDeploySnapshot value) {
        snapshot = value;
        fields.clear();
        value.fields().stream().filter(field -> !field.key().startsWith("@barrierRules."))
                .forEach(field -> fields.put(field.key(), field.value()));
        mapName = value.draftMapName();
        readCorner(cornerA, value.mapPos1());
        readCorner(cornerB, value.mapPos2());
    }

    boolean busy() { return pending != null; }

    void send(Action action, ZombiesDeployDraft draft, Runnable after) {
        if (busy()) { return; }
        pending = action;
        requestId = REQUEST_IDS.incrementAndGet();
        completion = after;
        FPSMatch.sendToServer(new ZombiesDeployToolActionC2SPacket(action, draft,
                action == Action.UNDO_LAST || action == Action.REDO_LAST ? snapshot.revision() : -1, requestId));
    }

    boolean matches(OpenZombiesDeployToolScreenS2CPacket packet) {
        return pending == packet.responseAction() && requestId == packet.requestId();
    }

    boolean accepts(OpenZombiesDeployToolScreenS2CPacket packet) {
        if (busy()) { return matches(packet); }
        return packet.requestId() < 0 || packet.requestId() > lastCompletedRequest;
    }

    Runnable complete(OpenZombiesDeployToolScreenS2CPacket packet) {
        if (!accepts(packet)) { return null; }
        boolean matched = matches(packet);
        Action action = pending;
        Runnable next = completion;
        if (matched) { lastCompletedRequest = requestId; pending = null; completion = null; }
        accept(packet.snapshot());
        String code = snapshot.statusCode();
        boolean success = code.equals("ok") || code.startsWith("ok.") || code.equals("draft.staged")
                || code.equals("object.field_staged") || code.equals("object.field_updated")
                || code.equals("draft.saved") || code.equals("draft.empty") || code.equals("draft.discarded")
                || code.equals("undo.applied") || code.equals("redo.applied");
        // Never continue navigation after a parse, persistence, or revision failure.
        if (action == Action.DISCARD_DRAFT) { success = code.equals("draft.discarded") && !snapshot.dirty(); }
        return matched && success ? next : null;
    }

    boolean fieldsChanged() {
        return snapshot.fields().stream().anyMatch(field -> field.editable()
                && !Objects.equals(field.value(), fields.get(field.key())));
    }

    boolean registrationDirty() {
        return !mapName.isBlank() || !empty(cornerA) || !empty(cornerB);
    }

    boolean dirty() { return snapshot.dirty() || fieldsChanged() || registrationDirty(); }

    ZombiesDeployDraft draft(boolean includeFields) {
        return new ZombiesDeployDraft(snapshot.workspaceStage(), snapshot.currentWorkflowStep(), snapshot.selectedMap(),
                mapName, corner(cornerA), corner(cornerB), snapshot.selectedObjectType(), snapshot.capturePreset(),
                snapshot.selectedIndex(), snapshot.profileKey(), includeFields ? new LinkedHashMap<>(fields) : Map.of());
    }

    static BlockPos corner(String[] values) {
        if (empty(values)) { return null; }
        return new BlockPos(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]));
    }
    static boolean empty(String[] values) {
        for (String value : values) { if (value != null && !value.isBlank()) { return false; } }
        return true;
    }
    private static void readCorner(String[] values, BlockPos pos) {
        values[0] = pos == null ? "" : Integer.toString(pos.getX());
        values[1] = pos == null ? "" : Integer.toString(pos.getY());
        values[2] = pos == null ? "" : Integer.toString(pos.getZ());
    }
}
