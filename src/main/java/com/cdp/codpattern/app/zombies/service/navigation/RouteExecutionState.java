package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.level.pathfinder.Path;

/** Mutable, private-to-one-entity cursor. Immutable RoutePlans never carry native Path cursors. */
public final class RouteExecutionState {
    public enum Phase { WAIT, FOLLOW, PRECISE, APPROACH, COMMIT, FALL, LAND, NATIVE }
    RoutePlan plan;
    private RoutePlan.Retention planRetention;
    int edgeIndex;
    Phase phase = Phase.WAIT;
    Path path;
    long edgeStarted, controlledSince, nativeUntil, nextRequest, graceUntil, nextGrace;
    long lastCommandMotion;
    long dropProofRevision = Long.MIN_VALUE;
    double previousY, closest = Double.POSITIVE_INFINITY;
    double furthestCommandProjection;
    boolean clearAfterLanding;
    boolean commandStarted;
    String edgeKey;

    public boolean committedDrop() { return phase == Phase.FALL || phase == Phase.LAND; }
    TraversalEdge edge() {
        return plan == null || edgeIndex >= plan.edges().size() ? null : plan.edges().get(edgeIndex);
    }
    void usePlan(RoutePlan next) {
        RoutePlan.Retention retained = next.retain();
        clearRoute();
        plan = next;
        planRetention = retained;
    }
    void clearRoute() {
        plan = null;
        if (planRetention != null) { planRetention.close(); planRetention = null; }
        edgeIndex = 0; path = null; edgeKey = null; phase = Phase.WAIT;
        dropProofRevision = Long.MIN_VALUE;
    }
}
