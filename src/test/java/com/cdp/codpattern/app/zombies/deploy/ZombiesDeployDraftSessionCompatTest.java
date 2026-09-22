package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesArmorStationData;
import com.cdp.codpattern.client.zombies.ZombiesDeployCloseRequest;
import com.cdp.codpattern.client.zombies.ZombiesDeployCloseRequest.Outcome;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket.Action;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Behavioral regressions for selection, rollback, close acknowledgements, and their wire context. */
public final class ZombiesDeployDraftSessionCompatTest {
    private ZombiesDeployDraftSessionCompatTest() { }

    public static void main(String[] args) throws Exception {
        stagedObjectsRemainSelectableAndLoadTheirCurrentFields();
        discardAndUndoRestoreFieldsAndClearRemovedSelections();
        unchangedFieldAcknowledgementsDoNotConsumeUndo();
        closeWaitsForTheMatchingSuccessfulResponse();
        responseActionAndSnapshotSurvivePacketRoundTrip();
        System.out.println("PASS zombies deploy draft session compat");
    }

    private static void stagedObjectsRemainSelectableAndLoadTheirCurrentFields() throws Exception {
        ZombiesMapObjects staged = objects(500, 750);
        ZombiesDeployDraft addedSelection = normalize("normalizeObjectSelection", draft(1, Map.of()), staged);
        require(addedSelection.selectedIndex() == 1, "new staged object must retain its index before saving");
        require("station-1".equals(addedSelection.fields().get("objectId")), "selection must load the newly staged object");
        require("750".equals(addedSelection.fields().get("buyCost")), "selection must load staged fields");

        ZombiesDeployDraft editedSelection = normalize("normalizeObjectSelection", draft(0, Map.of()), objects(900));
        require("900".equals(editedSelection.fields().get("buyCost")), "reselection must load the edited cost");
        ZombiesDeployDraft editorValue = normalize("normalizeObjectSelection", draft(0, Map.of("buyCost", "950")), objects(900));
        require("950".equals(editorValue.fields().get("buyCost")), "save request must retain the unblurred editor value");
    }

    private static void discardAndUndoRestoreFieldsAndClearRemovedSelections() throws Exception {
        ZombiesDeployDraft restored = normalize("restoreDraftFields", draft(0, Map.of("buyCost", "900")), objects(500));
        require("500".equals(restored.fields().get("buyCost")), "rollback must restore the object cost");
        require("station-0".equals(restored.fields().get("objectId")), "rollback must reload object identity");
        ZombiesDeployDraft removed = normalize("restoreDraftFields", draft(1, Map.of("objectId", "station-1")), ZombiesMapObjects.EMPTY);
        require(removed.selectedIndex() == -1, "removed staged object must clear selection");
        require(removed.fields().get("objectId").isEmpty(), "removed selection must clear stale object identity");
    }

    private static void unchangedFieldAcknowledgementsDoNotConsumeUndo() throws Exception {
        Class<?> sessionType = Class.forName(ZombiesDeployToolService.class.getName() + "$DraftSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(ZombiesMapObjects.class);
        constructor.setAccessible(true);
        ZombiesMapObjects base = objects(500);
        ZombiesMapObjects edited = objects(750);
        Object session = constructor.newInstance(base);
        invokeSession(session, "stage", edited);
        invokeSession(session, "stage", edited);
        require(Integer.valueOf(1).equals(invokeSession(session, "revision")), "unchanged field submit must not advance revision");
        invokeSession(session, "undo");
        require(base.equals(invokeSession(session, "currentObjects")), "undo must restore the actual previous object state");
        require(invokeSession(session, "previousObjects") == null, "undo should remain one step");
        ZombiesDeployDraft restored = normalize("restoreDraftFields", draft(0, Map.of("buyCost", "750")),
                (ZombiesMapObjects) invokeSession(session, "currentObjects"));
        require("500".equals(restored.fields().get("buyCost")), "undo editor fields must match the restored session");
    }

    public static void closeWaitsForTheMatchingSuccessfulResponse() {
        ZombiesDeployCloseRequest close = new ZombiesDeployCloseRequest();
        long requestId = close.begin(Action.SAVE_DRAFT);
        require(close.accept(Action.SET_FIELD, requestId, "draft.staged", true) == Outcome.WAIT, "earlier field reply must not resolve close");
        require(close.accept(Action.SAVE_DRAFT, requestId - 1, "draft.saved", false) == Outcome.WAIT, "older save reply must not resolve close");
        require(close.accept(Action.REFRESH, requestId, "ok", false) == Outcome.WAIT, "refresh must not resolve close");
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.validation_failed", true) == Outcome.KEEP_EDITING, "validation failure must retain the editor");
        requestId = close.begin(Action.SAVE_DRAFT);
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.revision_conflict", true) == Outcome.KEEP_EDITING, "revision conflict must retain the editor");
        requestId = close.begin(Action.SAVE_DRAFT);
        require(close.accept(Action.SAVE_DRAFT, requestId, "save_failed_rolled_back", true) == Outcome.KEEP_EDITING, "disk failure must retain the editor");
        requestId = close.begin(Action.SAVE_DRAFT);
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.empty", true) == Outcome.KEEP_EDITING, "no-op save must not close a dirty draft");
        requestId = close.begin(Action.SAVE_DRAFT);
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.saved", false) == Outcome.CLOSE, "successful save may close");
        requestId = close.begin(Action.DISCARD_DRAFT);
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.saved", false) == Outcome.WAIT, "save reply must not resolve discard");
        require(close.accept(Action.DISCARD_DRAFT, requestId, "draft.discarded", false) == Outcome.CLOSE, "successful discard may close");
        requestId = close.begin(Action.SAVE_DRAFT);
        close.cancel();
        require(close.accept(Action.SAVE_DRAFT, requestId, "draft.saved", false) == Outcome.WAIT,
                "cancelled close must ignore a late successful reply");
    }

    private static void responseActionAndSnapshotSurvivePacketRoundTrip() {
        ZombiesDeploySnapshot snapshot = new ZombiesDeploySnapshot(
                List.of("review-map"), ZombiesDeployDraft.STAGE_OBJECT_MARKING, ZombiesDeployDraft.WORKFLOW_INTERACT,
                ZombiesDeployDraft.WORKFLOW_VALIDATE, "", "gui.codpattern.zombies.deploy.next_step", true,
                "review-map", "", null, null,
                List.of(new ZombiesDeploySnapshot.ObjectTypeOption(ZombiesDeployFieldSchema.ARMOR_STATION, "armor")),
                ZombiesDeployFieldSchema.ARMOR_STATION, ZombiesDeployDraft.CAPTURE_DEFAULT, "pos", "", 0,
                List.of(new ZombiesDeploySnapshot.ObjectSummary(0, ZombiesDeployFieldSchema.ARMOR_STATION, "station-0", "armor 1", "0,64,0")),
                List.of(new ZombiesDeploySnapshot.FieldValue("buyCost", "cost", ZombiesDeployFieldSchema.FieldType.INTEGER, "500", true)),
                ZombiesDeployFieldSchema.PROFILE_MVP3, List.of(ZombiesDeployFieldSchema.PROFILE_MVP3),
                List.of(), List.of(), List.of(), List.of(), List.of(), false, "station-0|1.0", false, 2,
                "message.codpattern.zombies.deploy.object_saved", "draft.saved", "");
        FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ZombiesDeployToolActionC2SPacket(Action.SAVE_DRAFT, draft(0, Map.of("buyCost", "950")), 42L).encode(request);
            require(ZombiesDeployToolActionC2SPacket.decode(request).requestId() == 42L,
                    "close request ID must survive the request codec");
            require(request.readableBytes() == 0, "request decoder must consume the full request");
        } finally {
            request.release();
        }
        for (Action action : List.of(Action.REFRESH, Action.SAVE_DRAFT, Action.DISCARD_DRAFT, Action.UNDO_LAST)) {
            boolean opens = action == Action.REFRESH;
            FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new OpenZombiesDeployToolScreenS2CPacket(snapshot, opens, action, 42L).encode(bytes);
                OpenZombiesDeployToolScreenS2CPacket decoded = OpenZombiesDeployToolScreenS2CPacket.decode(bytes);
                require(decoded.requestId() == 42L, "response request ID must survive codec round trip");
                require(decoded.responseAction() == action, "response action must survive codec round trip");
                require(decoded.openScreen() == opens, "open-screen flag must survive codec round trip");
                require(snapshot.equals(decoded.snapshot()), "snapshot must survive codec round trip");
                require(bytes.readableBytes() == 0, "decoder must consume the full response");
            } finally {
                bytes.release();
            }
        }
    }

    private static ZombiesDeployDraft normalize(String methodName, ZombiesDeployDraft draft, ZombiesMapObjects objects) throws Exception {
        Method method = ZombiesDeployToolService.class.getDeclaredMethod(methodName, ServerPlayer.class, ZombiesDeployDraft.class, ZombiesMapObjects.class);
        method.setAccessible(true);
        return (ZombiesDeployDraft) method.invoke(ZombiesDeployToolService.instance(), null, draft, objects);
    }

    private static Object invokeSession(Object session, String methodName, Object... args) throws Exception {
        Method method = args.length == 0 ? session.getClass().getDeclaredMethod(methodName)
                : session.getClass().getDeclaredMethod(methodName, ZombiesMapObjects.class);
        method.setAccessible(true);
        return method.invoke(session, args);
    }

    private static ZombiesDeployDraft draft(int index, Map<String, String> fields) {
        return new ZombiesDeployDraft("review-map", ZombiesDeployFieldSchema.ARMOR_STATION, index, ZombiesDeployFieldSchema.PROFILE_MVP3, fields);
    }

    private static ZombiesMapObjects objects(int... costs) {
        java.util.ArrayList<ZombiesArmorStationData> stations = new java.util.ArrayList<>();
        for (int index = 0; index < costs.length; index++) {
            stations.add(new ZombiesArmorStationData("station-" + index, 1, costs[index], 1.0D,
                    Level.OVERWORLD, new BlockPos(index, 64, 0), Optional.empty()));
        }
        return new ZombiesMapObjects(List.of(), List.of(), List.of(), List.of(), List.of(), stations,
                Optional.empty(), List.of(), List.of(), List.of(), List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
