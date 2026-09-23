package com.cdp.codpattern.client.zombies;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeploySnapshot;

/** Client-side mirror used by the world deployment HUD after the screen closes. */
public final class ZombiesDeployClientState {
    private static volatile State state = State.EMPTY;

    private static ZombiesDeploySnapshot lastSnapshot;
    private static long noticeAt;
    public static void reset() { state = State.EMPTY; lastSnapshot = null; }
    public static ZombiesDeploySnapshot snapshot() { return lastSnapshot; }
    public static long noticeAt() { return noticeAt; }

    private ZombiesDeployClientState() {
    }

    public static void update(ZombiesDeploySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        lastSnapshot = snapshot;
        noticeAt = System.currentTimeMillis();
        int count = 0;
        int required = 0;
        for (ZombiesDeploySnapshot.ObjectTypeCount value : snapshot.objectCounts()) {
            if (snapshot.selectedObjectType().equals(value.objectType())) {
                count = value.count();
                required = value.required() ? 1 : 0;
                break;
            }
        }
        state = new State(
                true,
                snapshot.selectedMap(),
                snapshot.currentWorkflowStep(),
                snapshot.selectedObjectType(),
                count,
                required,
                snapshot.dirty(),
                snapshot.revision());
    }

    public static State current() {
        return state;
    }

    public record State(
            boolean active,
            String map,
            String step,
            String objectType,
            int count,
            int required,
            boolean dirty,
            int revision
    ) {
        private static final State EMPTY = new State(false, "", "", "", 0, 0, false, -1);
    }
}
