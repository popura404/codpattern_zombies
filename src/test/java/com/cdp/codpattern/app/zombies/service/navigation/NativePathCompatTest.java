package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        System.out.println("NativePathCompatTest: passed");
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
