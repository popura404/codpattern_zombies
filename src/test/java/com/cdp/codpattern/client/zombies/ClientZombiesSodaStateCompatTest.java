package com.cdp.codpattern.client.zombies;

import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.match.ModeModules;
import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.match.model.ModeRuntimeStateSnapshot;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.tdm.TdmModeModule;
import com.cdp.codpattern.app.zombies.ZombiesModeModule;
import com.cdp.codpattern.app.zombies.sync.ZombiesRuntimeStateKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientZombiesSodaStateCompatTest {
    private static final String ROOM_KEY = RoomId.of(BuiltInGameModes.ZOMBIES, "soda-state").encode();
    private static final List<String> BUFF_ORDER = List.of(
            "double_health", "speed_boost", "reactive_explosion",
            "double_ammo", "score_multiplier", "headshot_damage");

    private ClientZombiesSodaStateCompatTest() {
    }

    public static void main(String[] args) {
        ModeModules.contribute(TdmModeModule.INSTANCE);
        ModeModules.contribute(ZombiesModeModule.INSTANCE);
        ModeModules.freeze();
        currentRoomMustMatchSnapshot();
        incompletePlayerStateHidesSodas();
        onlyActiveAndIntermissionPhasesShowSodas();
        deadOrDisconnectedPlayersHideSodas();
        knownOwnedBuffsUseStableOrder();
        removingOwnershipUpdatesTheVisibleList();
        System.out.println("PASS ClientZombiesSodaStateCompatTest: 6/6 checks");
    }

    private static void currentRoomMustMatchSnapshot() {
        ModeRuntimeStateSnapshot current = snapshot(ROOM_KEY, "WAVE_ACTIVE", owned("double_health"));
        requireVisible(ROOM_KEY, current, List.of("double_health"), "matching room should show owned soda");
        requireHidden(null, current, "missing current room must not reuse cached soda state");
        requireHidden("", current, "empty current room must hide sodas");
        requireHidden("not-a-room", current, "malformed room must hide sodas");
        requireHidden(ROOM_KEY, null, "missing current-room snapshot must hide sodas");

        String otherRoom = RoomId.of(BuiltInGameModes.ZOMBIES, "old-room").encode();
        requireHidden(ROOM_KEY, snapshot(otherRoom, "WAVE_ACTIVE", owned("double_health")),
                "snapshot from another zombies room must hide sodas");
        String nonZombiesRoom = RoomId.of("tdm", "soda-state").encode();
        requireHidden(nonZombiesRoom, snapshot(nonZombiesRoom, "WAVE_ACTIVE", owned("double_health")),
                "matching non-zombies room must hide sodas");
    }

    private static void incompletePlayerStateHidesSodas() {
        requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", Map.of()), "empty player values must hide sodas");
        for (String missingKey : List.of(
                ZombiesRuntimeStateKeys.PLAYER_LIFE_STATE, ZombiesRuntimeStateKeys.PLAYER_CONNECTION_STATE)) {
            Map<String, ModePlayerValue> values = owned("double_health");
            values.remove(missingKey);
            requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values),
                    "missing " + missingKey + " must hide sodas");
            values.put(missingKey, ModePlayerValue.ofString(""));
            requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values),
                    "blank " + missingKey + " must hide sodas");
        }
        requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", owned()),
                "valid player without buffs must have no icons");
    }

    private static void onlyActiveAndIntermissionPhasesShowSodas() {
        for (String phase : List.of("WAVE_ACTIVE", "INTERMISSION")) {
            requireVisible(ROOM_KEY, snapshot(ROOM_KEY, phase, owned("speed_boost")), List.of("speed_boost"),
                    "phase " + phase + " should show owned sodas");
        }
        for (String phase : List.of("WAITING", "START_VOTE", "OPENING_COUNTDOWN", "VICTORY", "FAILED", "ENDING", "UNKNOWN", "")) {
            requireHidden(ROOM_KEY, snapshot(ROOM_KEY, phase, owned("speed_boost")),
                    "phase " + phase + " must hide sodas");
        }
    }

    private static void deadOrDisconnectedPlayersHideSodas() {
        Map<String, ModePlayerValue> values = owned("double_health");
        values.put(ZombiesRuntimeStateKeys.PLAYER_LIFE_STATE, ModePlayerValue.ofString("DEAD_SPECTATING"));
        requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values),
                "death must hide icons even while server retains buff ownership");
        values.put(ZombiesRuntimeStateKeys.PLAYER_LIFE_STATE, ModePlayerValue.ofString("ALIVE"));
        for (String connection : List.of("OFFLINE", "LEFT", "UNKNOWN")) {
            values.put(ZombiesRuntimeStateKeys.PLAYER_CONNECTION_STATE, ModePlayerValue.ofString(connection));
            requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values),
                    "connection state " + connection + " must hide sodas");
        }
    }

    private static void knownOwnedBuffsUseStableOrder() {
        Map<String, ModePlayerValue> values = owned();
        for (int i = BUFF_ORDER.size() - 1; i >= 0; i--) {
            values.put(ZombiesRuntimeStateKeys.playerBuff(BUFF_ORDER.get(i)), ModePlayerValue.ofBoolean(true));
        }
        values.put(ZombiesRuntimeStateKeys.playerBuff("unknown_soda"), ModePlayerValue.ofBoolean(true));
        requireVisible(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values), BUFF_ORDER,
                "known buffs must use fixed order and exclude unknown ids");
    }

    private static void removingOwnershipUpdatesTheVisibleList() {
        Map<String, ModePlayerValue> values = owned("double_health", "speed_boost", "headshot_damage");
        ModeRuntimeStateSnapshot beforeRemoval = snapshot(ROOM_KEY, "WAVE_ACTIVE", values);
        requireVisible(ROOM_KEY, beforeRemoval, List.of("double_health", "speed_boost", "headshot_damage"),
                "all three owned sodas should show");
        values.put(ZombiesRuntimeStateKeys.playerBuff("speed_boost"), ModePlayerValue.ofBoolean(false));
        values.remove(ZombiesRuntimeStateKeys.playerBuff("headshot_damage"));
        requireVisible(ROOM_KEY, snapshot(ROOM_KEY, "WAVE_ACTIVE", values), List.of("double_health"),
                "false or removed ownership must remove the corresponding icon");
        requireHidden(ROOM_KEY, snapshot(ROOM_KEY, "INTERMISSION", owned()),
                "reviving with cleared buffs must not restore old icons");
    }

    private static Map<String, ModePlayerValue> owned(String... buffIds) {
        Map<String, ModePlayerValue> values = new LinkedHashMap<>();
        values.put(ZombiesRuntimeStateKeys.PLAYER_LIFE_STATE, ModePlayerValue.ofString("ALIVE"));
        values.put(ZombiesRuntimeStateKeys.PLAYER_CONNECTION_STATE, ModePlayerValue.ofString("ONLINE"));
        for (String buffId : buffIds) {
            values.put(ZombiesRuntimeStateKeys.playerBuff(buffId), ModePlayerValue.ofBoolean(true));
        }
        return values;
    }

    private static ModeRuntimeStateSnapshot snapshot(String roomKey, String phase, Map<String, ModePlayerValue> values) {
        return new ModeRuntimeStateSnapshot(roomKey, phase, 0, List.of(), values, List.of(), 1L);
    }

    private static void requireHidden(String roomKey, ModeRuntimeStateSnapshot snapshot, String message) {
        requireVisible(roomKey, snapshot, List.of(), message);
    }

    private static void requireVisible(
            String roomKey, ModeRuntimeStateSnapshot snapshot, List<String> expected, String message) {
        List<String> actual = ClientZombiesState.visibleSodaBuffIds(roomKey, snapshot);
        if (!actual.equals(expected)) {
            throw new AssertionError(message + "; expected=" + expected + "; actual=" + actual);
        }
    }
}
