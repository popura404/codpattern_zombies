package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercise vanilla Path mutability against the production short-segment boundary. */
public final class NativePathCompatTest {
    public static void main(String[] args) {
        SurfaceNode from = new SurfaceNode(new Vec3(-.5, 1, .5), 1);
        SurfaceNode to = new SurfaceNode(new Vec3(.5, 1.5, .5), 1);
        TraversalEdge shared = new TraversalEdge(from, to, TraversalEdge.Action.STEP,
                List.of(from.feet(), to.feet()), 1, Map.of());
        Path first = LayeredNavigationRuntime.shortPath(shared);
        Path second = LayeredNavigationRuntime.shortPath(shared);
        require(first != second && first.getNode(0) != second.getNode(0)
                        && first.getNode(1) != second.getNode(1),
                "two consumers of one immutable edge must own distinct paths and mutable nodes");
        require(first.sameAs(second) && LayeredNavigationRuntime.sameSegment(second, shared),
                "an equivalent native-retained Path remains valid without identity equality");
        require(first.getNode(0).asBlockPos().equals(new BlockPos(-1, 1, 0))
                        && first.getEndNode().asBlockPos().equals(new BlockPos(0, 2, 0)),
                "negative coordinates and slab feet must map to their native supported node");
        first.advance();
        first.replaceNode(1, new Node(8, 2, 0));
        require(second.getNextNodeIndex() == 0 && second.getEndNode().x == 0,
                "advancing or replacing one path must not alter another consumer");
        require(!LayeredNavigationRuntime.sameSegment(first, shared),
                "native replacement outside the proven corridor must be rejected");
        second.truncateNodes(1);
        require(!LayeredNavigationRuntime.sameSegment(second, shared),
                "native clipping before the proven endpoint cannot count as segment completion");
        Path shortSuffix = new Path(new ArrayList<>(List.of(new Node(0, 2, 0))), new BlockPos(0, 2, 0), true);
        require(LayeredNavigationRuntime.sameSegment(shortSuffix, shared),
                "a native suffix that preserves the proven endpoint is still the same segment");
        Path shortcut = new Path(new ArrayList<>(List.of(new Node(-1, 1, 0), new Node(0, 2, 1),
                new Node(0, 2, 0))), new BlockPos(0, 2, 0), true);
        require(!LayeredNavigationRuntime.sameSegment(shortcut, shared),
                "a native detour outside the validated two-node edge needs a fresh proof");
        List<TraversalEdge> delayedPlan = new ArrayList<>();
        for (int x = 0; x < 8; x++) delayedPlan.add(walk(x + .5, 1, x + 1.5, 1));
        require(LayeredNavigationRuntime.routeEntryIndex(delayedPlan, new Vec3(5.75, 1, .5)) == 5,
                "native motion during cold planning must adopt the nearby proven suffix instead of restarting at a stale origin");
        require(LayeredNavigationRuntime.routeEntryIndex(delayedPlan, new Vec3(.5, 1, .5)) == 0,
                "an unchanged start must preserve the full route");
        require(LayeredNavigationRuntime.routeEntryIndex(delayedPlan, new Vec3(5.75, 6, .5)) == 0,
                "a different floor cannot adopt a segment merely sharing X/Z");
        require(LayeredNavigationRuntime.routeEntryIndex(delayedPlan, new Vec3(30, 1, .5)) == 0,
                "a distant unmatched pose must use ordinary budgeted replanning");
        SurfaceNode top = new SurfaceNode(new Vec3(5.5, 65, .5), 1);
        SurfaceNode floor = new SurfaceNode(new Vec3(6.5, 1, .5), 1);
        TraversalEdge drop = new TraversalEdge(top, floor, TraversalEdge.Action.DROP,
                List.of(top.feet(), new Vec3(6.5, 65, .5), floor.feet()), 65, Map.of());
        require(LayeredNavigationRuntime.routeEntryIndex(List.of(shared, drop), new Vec3(6, 33, .5)) == 0,
                "a projection through a deep shaft cannot manufacture an adopted departure");
        preciseArrivalSurvivesTheNativePathBeingCleared();
        aLongPartialNativeRouteKeepsControlWhileMakingProgress();
        provenRoutesKeepControlWithoutSuppressingNativeLeaps();
        System.out.println("NativePathCompatTest: passed (including 3 movement continuity scenarios)");
    }

    private static void preciseArrivalSurvivesTheNativePathBeingCleared() {
        TraversalEdge edge = walk(.5, 1, 1.5, 1);
        Path nativePath = LayeredNavigationRuntime.shortPath(edge);
        Vec3 nearEnd = new Vec3(1.15, 1, .5);
        require(!LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.FOLLOW,
                        nativePath, edge, nearEnd, true),
                "an unfinished native path still owns its approach to the endpoint");
        while (!nativePath.isDone()) nativePath.advance();
        require(LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.FOLLOW,
                        nativePath, edge, nearEnd, true),
                "vanilla waypoint completion hands the remaining physical approach to precise movement");

        // controlledMove clears native navigation on the first precise tick. On a slow
        // surface the remaining approach lasts many ticks and must not reinstall the
        // two-node path whose first node is now behind the mob.
        nativePath = null;
        for (int tick = 1; tick <= 20; tick++) {
            Vec3 approaching = nearEnd.add(tick * .004, 0, 0);
            require(LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.PRECISE,
                            nativePath, edge, approaching, true),
                    "cleared navigation must retain a slow precise approach on tick " + tick);
        }
        require(!LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.FOLLOW,
                        null, edge, nearEnd, true),
                "a missing native path alone cannot manufacture a completed approach");
        require(!LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.PRECISE,
                        null, edge, new Vec3(.69, 1, .5), true),
                "external displacement beyond the local endpoint tolerance requires a fresh approach");
        require(!LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.PRECISE,
                        null, edge, nearEnd, false),
                "airborne displacement cannot inherit a grounded precise approach");
        Path wrongPath = LayeredNavigationRuntime.shortPath(walk(.5, 1, 2.5, 1));
        while (!wrongPath.isDone()) wrongPath.advance();
        require(!LayeredNavigationRuntime.finishPrecisely(RouteExecutionState.Phase.FOLLOW,
                        wrongPath, edge, nearEnd, true),
                "finishing an unrelated native path cannot claim this proven endpoint");
    }

    private static void aLongPartialNativeRouteKeepsControlWhileMakingProgress() {
        RouteExecutionState state = new RouteExecutionState();
        RouteProgress progress = new RouteProgress(0, .5, 1, .5);
        progress.target("distant-player", 160.5, 1, .5);
        Path partial = new Path(new ArrayList<>(List.of(new Node(0, 1, 0), new Node(16, 1, 0))),
                new BlockPos(160, 1, 0), false);
        require(!partial.canReach(), "the native route is useful even though its final target is distant");
        for (int tick = 1; tick <= 400; tick++) {
            double distance = tick * .025;
            progress.sample(tick, .5 + distance, 1, .5);
            progress.advance(tick, "native-corridor", distance, .5 + distance, 1, .5);
            require(!state.needsRecovery(tick, progress.lastRoute(), progress.lastCombat(), progress.loopDetected()),
                    "real forward progress must preserve native control beyond its initial grace on tick " + tick);
        }
        require(progress.lastRoute() == 400, "the last simulated forward motion earns real route credit");
        for (int tick = 401; tick <= 440; tick++) {
            progress.sample(tick, 10.5, 1, .5);
            progress.advance(tick, "native-corridor", 10, 10.5, 1, .5);
            require(state.needsRecovery(tick, progress.lastRoute(), progress.lastCombat(), progress.loopDetected())
                            == (tick == 440),
                    "recovery waits for forty ticks of actual stalled movement, tick " + tick);
        }
        progress.engagement(441);
        require(!state.needsRecovery(441, progress.lastRoute(), progress.lastCombat(), false),
                "real combat is useful activity even when walking has stopped");
        for (String edge : List.of("east", "west", "east", "west")) progress.completed(442, edge);
        require(progress.loopDetected()
                        && state.needsRecovery(442, progress.lastRoute(), progress.lastCombat(), progress.loopDetected()),
                "a proven repeated circuit requests recovery without waiting for a fresh timeout");
        state.usePlan(plan(List.of(walk(.5, 1, 1.5, 1))));
        require(state.needsRecovery(443, 443, 443, false),
                "a retained managed plan resumes immediately after an external goal releases control");
        state.clearRoute();
    }

    private static void provenRoutesKeepControlWithoutSuppressingNativeLeaps() {
        RouteExecutionState state = new RouteExecutionState();
        state.controlledSince = 100;
        List<TraversalEdge> corridor = new ArrayList<>();
        for (int x = 0; x < 80; x++) corridor.add(walk(x + .5, 1, x + 1.5, 1));
        state.usePlan(plan(corridor));
        for (int tick = 100; tick <= 500; tick++) {
            state.edgeIndex = Math.min(corridor.size() - 1, (tick - 100) / 5);
            require(!state.needsNativeOpportunity(tick, false, false),
                    "ordinary distant pursuit must not interrupt a proven route at periodic native handoffs, tick " + tick);
        }
        require(state.needsNativeOpportunity(500, false, true),
                "an explicit nearby wolf leap opportunity can interrupt an otherwise useful route");
        require(!state.needsNativeOpportunity(500, true, true),
                "a nearby leap cannot bypass the missing-chunk guard");

        state.clearRoute();
        state.controlledSince = 500;
        for (int tick = 500; tick < 520; tick++) {
            require(!state.needsNativeOpportunity(tick, false, false),
                    "cold planning keeps its initial bounded opportunity to finish");
        }
        require(state.needsNativeOpportunity(520, false, false),
                "cold planning without a usable route still gives vanilla a chance after twenty ticks");
        require(!state.needsNativeOpportunity(520, true, false),
                "cold planning cannot hand control to native navigation while its terrain is missing");
    }

    private static RoutePlan plan(List<TraversalEdge> edges) {
        MovementProfile profile = new MovementProfile(.6, 1.8, 1, false, true, true, "movement-continuity");
        return new RoutePlan(UUID.randomUUID(), UUID.randomUUID(), edges.get(edges.size() - 1).to().feet(),
                profile, edges, List.of(), Map.of());
    }
    private static TraversalEdge walk(double x, double y, double endX, double endY) {
        SurfaceNode start = new SurfaceNode(new Vec3(x, y, .5), 1);
        SurfaceNode end = new SurfaceNode(new Vec3(endX, endY, .5), 1);
        return new TraversalEdge(start, end, TraversalEdge.Action.WALK,
                List.of(start.feet(), end.feet()), start.feet().distanceTo(end.feet()), Map.of());
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
