package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Mutable, private-to-one-entity cursor. Immutable RoutePlans never carry native Path cursors. */
public final class RouteExecutionState {
    public enum Phase { WAIT, FOLLOW, PRECISE, APPROACH, COMMIT, JUMP, FALL, LAND, NATIVE }
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
    DropJump.Plan dropJump;
    Vec3 jumpOffset = Vec3.ZERO;
    long jumpProofStarted, jumpLaunchedAt;
    boolean jumpDeclined, jumpBraked;
    String edgeKey;

    public boolean committedDrop() { return phase == Phase.JUMP || phase == Phase.FALL || phase == Phase.LAND; }
    void resetDropJump() {
        dropJump = null; jumpOffset = Vec3.ZERO;
        jumpProofStarted = 0; jumpLaunchedAt = 0; jumpDeclined = false; jumpBraked = false;
    }
    boolean needsRecovery(long now, long lastRouteProgress, long lastCombat, boolean loopDetected) {
        // A partial native path can still carry the mob towards a distant target.
        // Only lack of actual progress, rather than its canReach flag, warrants takeover.
        return plan != null || loopDetected || now - Math.max(lastRouteProgress, lastCombat) >= 40;
    }
    boolean needsNativeOpportunity(long now, boolean waitingForChunk, boolean nativeLeapOpportunity) {
        // A usable route keeps MOVE unless a nearby wolf can use its equal-priority leap goal.
        return (plan == null || nativeLeapOpportunity) && !waitingForChunk && now - controlledSince >= 20;
    }
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
        resetDropJump();
    }
}
