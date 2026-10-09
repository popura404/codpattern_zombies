package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Deterministic pure graph tests; never create, teleport, or drive a real entity. */
public final class NavigationPlannerCompatTest {
    private static final MovementProfile PROFILE = new MovementProfile(.6, 1.8, 1, false, true, true, "test");
    public static void main(String[] args) {
        longRouteHasNoRadiusOrLengthCutoff();
        unknownFrontierResumesAfterChunkLoads();
        resourcePressureIsNotUnreachable();
        dropIsDirectedAndDoesNotMergeRegions();
        failureMemoryIsDirectedVersionedAndProfileScoped();
        temporaryAvoidanceCannotBecomeGeometricFailure();
        schedulerSharesBudgetAndRotatesRoomsAndMobs();
        singleRoomBorrowsIdleTimeWithinOriginalQuantityLimits();
        everyRoomReceivesItsBaseAllowanceBeforeFairBorrowing();
        fullyLoadedRoomsKeepRotatingWithinTheServerDeadline();
        waitingJobsAndEmptyRoomsCannotSpinOrSpendTheSameTickTwice();
        cancellationDuringASlicePreservesReplacementAndNewRoomWork();
        schedulerAndUnitTimingUseTheSameClock();
        portalPathsRetainFineEvidenceAndInvalidateLocally();
        fractionalFloorsAndNegativeTilesDoNotAlias();
        targetMotionKeepsOpenFrontier();
        crossTileRetargetRequestsRestartWithoutChangingSearch();
        frozenGoalKeepsCompletionAcrossTileBoundaries();
        failureProbeRequiresObservedShapeChange();
        nearTargetNeedsAProvenConnection();
        partialScansCanReceiveAnotherFairSliceInSameTick();
        shapeCellsAreBoundedAndVersioned();
        unreadWorldEventsCannotInvalidateObservedTopology();
        temporaryGeometryAndSearchMemoryIsSharedAndReleasable();
        searchResumesWhenSharedTemporaryCapacityReturns();
        cachedReadsTrackOnlyTheirActualDependencies();
        completedScansReleaseNonPredecessorEdges();
        targetReturningToClosedNodesOnlyRefreshesItsTail();
        continuouslyMovingTargetCannotStarveLongReconstruction();
        readyRoutesKeepSharedCapacityUntilEveryOwnerReleases();
        evictedCompletionEvidenceRequestsFreshPlanning();
        repairReusesLongSuffixAcrossBudgets();
        suffixSelectionSkipsEveryInvalidPredecessor();
        impossibleRepairRequestsFullPlanning();
        repairQuotaIsNotAReachabilityLimit();
        repairPressureReleasesItsOwnSnapshot();
        repairWaitsForChunksAndRejectsChangedEvidence();
        repeatedRepairsHaveNoReservationAncestry();
        verifiedSuffixCannotOutliveEvictedVersionMetadata();
        ascentPreferenceRetainsARequiredDescentPastAHigherDeadEnd();
        ascentPreferenceExploresLessOfAWideLowerFloor();
        provenCoarseRouteDoesNotOpenDetailedGeometry();
        deferredDetailsCompleteMissingConnectionsBeforeDeclaringUnreachable();
        blockedDetailedBranchDoesNotStarveAnotherExit();
        reopenedCoarseNodePropagatesItsImprovedDistance();
        System.out.println("NavigationPlannerCompatTest: 45 checks passed");
    }
    private static void provenCoarseRouteDoesNotOpenDetailedGeometry() {
        for (int work : new int[]{1, 64}) {
            NavigationGraphCache cache = new NavigationGraphCache();
            Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
            SurfaceNode start = node(cache, 0, 0), previous = start;
            for (int i = 1; i <= 48; i++) {
                SurfaceNode next = node(cache, i, i / 4.0);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.STEP));
                previous = next;
            }
            AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
            NavigationPlanner.Search search = new NavigationPlanner.Search(start, previous.feet(), UUID.randomUUID(),
                    PROFILE, cache, new NavigationPlanner.NeighborSource() {
                        @Override public NavigationPlanner.NeighborScan open(SurfaceNode from) {
                            opened.incrementAndGet();
                            return stagedScan(graph.getOrDefault(from, List.of()), false, closed);
                        }
                        @Override public NavigationPlanner.NeighborScan openDetailed(SurfaceNode from) {
                            throw new AssertionError("a fully proven coarse route must not inspect irrelevant fractional geometry");
                        }
                    }, NavigationPlanner.RecordPool.bounded(128));
            try {
                run(search, work, 5000);
                require(search.result().status() == PlanningResult.Status.READY && search.result().plan().edges().size() == 48,
                        "proven coarse edges must form a complete executable route at either work budget");
                require(cache.edges(start, PROFILE) == null,
                        "a non-exhaustive coarse pass must never enter the complete adjacency cache");
                require(opened.get() == closed.get() && search.recordCount() == 0,
                        "READY must close all coarse scans and release records even with many queued detailed nodes");
            } finally {
                search.close();
                require(cache.stats().transientEntries() == 0,
                        "READY and cancellation must release every queued-detail reservation and route payload");
            }
        }
    }
    private static void deferredDetailsCompleteMissingConnectionsBeforeDeclaringUnreachable() {
        for (boolean connected : new boolean[]{true, false}) {
            NavigationGraphCache cache = new NavigationGraphCache();
            SurfaceNode start = node(cache, 0, 0), fraction = node(cache, .2, .5), goal = node(cache, 2, 8);
            TraversalEdge precise = edge(cache, start, fraction, TraversalEdge.Action.PRECISE_MOVE);
            TraversalEdge finish = edge(cache, fraction, goal, TraversalEdge.Action.STEP);
            AtomicInteger details = new AtomicInteger();
            NavigationPlanner.Search search = new NavigationPlanner.Search(start, goal.feet(), UUID.randomUUID(), PROFILE,
                    cache, new NavigationPlanner.NeighborSource() {
                        @Override public NavigationPlanner.NeighborScan open(SurfaceNode from) {
                            return stagedScan(from.equals(fraction) ? List.of(finish) : List.of(),
                                    from.equals(fraction), null);
                        }
                        @Override public NavigationPlanner.NeighborScan openDetailed(SurfaceNode from) {
                            details.incrementAndGet();
                            return graphScan(connected ? List.of(precise) : List.of());
                        }
                    }, NavigationPlanner.RecordPool.bounded(32));
            try {
                search.advance(budget(1, 1));
                require(search.result().status() == PlanningResult.Status.PENDING && cache.edges(start, PROFILE) == null,
                        "an empty coarse pass is outstanding work, not disconnection or cached empty adjacency");
                run(search, 1, 200);
                require(search.result().status() == (connected ? PlanningResult.Status.READY : PlanningResult.Status.UNREACHABLE)
                                && details.get() == 1,
                        "only the complete detailed pass may finish the route or conclude disconnection");
                require(cache.edges(start, PROFILE).equals(connected ? List.of(precise) : List.of()),
                        "the detailed pass must publish its whole proven adjacency");
                if (connected) require(search.result().plan().edges().equals(List.of(precise, finish)),
                        "the same search must retain and reconstruct the fractional-only connection");
            } finally {
                search.close();
                require(cache.stats().transientEntries() == 0, "detailed success and exhaustion both release all capacity");
            }
        }
    }
    private static void blockedDetailedBranchDoesNotStarveAnotherExit() {
        for (TraversalValidator.ScanStatus blocked : List.of(TraversalValidator.ScanStatus.WAITING_CHUNK,
                TraversalValidator.ScanStatus.RESOURCE_LIMITED)) {
            NavigationGraphCache cache = new NavigationGraphCache();
            SurfaceNode start = node(cache, 0, 0), unknown = node(cache, 1, 0), alternate = node(cache, -1, 0), goal = node(cache, 2, 8);
            List<TraversalEdge> first = List.of(edge(cache, start, unknown, TraversalEdge.Action.WALK),
                    edge(cache, start, alternate, TraversalEdge.Action.WALK));
            TraversalEdge escape = edge(cache, alternate, goal, TraversalEdge.Action.STEP);
            AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
            NavigationPlanner.Search search = new NavigationPlanner.Search(start, goal.feet(), UUID.randomUUID(), PROFILE,
                    cache, new NavigationPlanner.NeighborSource() {
                        @Override public NavigationPlanner.NeighborScan open(SurfaceNode from) {
                            return stagedScan(from.equals(start) ? first : List.of(), from.equals(start), null);
                        }
                        @Override public NavigationPlanner.NeighborScan openDetailed(SurfaceNode from) {
                            if (from.equals(unknown)) {
                                opened.incrementAndGet();
                                return new NavigationPlanner.NeighborScan() {
                                    @Override public TraversalValidator.NeighborBatch advance(int units, long deadline) {
                                        return new TraversalValidator.NeighborBatch(List.of(), blocked, 1);
                                    }
                                    @Override public void close() { closed.incrementAndGet(); }
                                };
                            }
                            require(from.equals(alternate) && opened.get() > 0 && closed.get() == opened.get(),
                                    "a blocked detailed cursor must release its geometry before another deferred exit runs");
                            return graphScan(List.of(escape));
                        }
                    }, NavigationPlanner.RecordPool.bounded(32));
            try {
                run(search, 2, 200);
                require(search.result().status() == PlanningResult.Status.READY && search.result().plan().edges().contains(escape),
                        "unknown chunks or resource pressure on one detailed branch cannot hide another proven exit");
                require(cache.edges(unknown, PROFILE) == null,
                        "waiting detailed scans must not cache an incomplete empty adjacency");
            } finally {
                search.close();
                require(cache.stats().transientEntries() == 0, "blocked detail metadata is released when the route wins");
            }
        }
    }
    private static void reopenedCoarseNodePropagatesItsImprovedDistance() {
        for (int work : new int[]{1, 64}) {
            NavigationGraphCache cache = new NavigationGraphCache();
            SurfaceNode start = node(cache, 0, 0), high = node(cache, 90, 8), low = node(cache, 0, 0, 1);
            SurfaceNode join = node(cache, 95, 8), goal = node(cache, 100, 8);
            TraversalEdge expensive = pricedEdge(cache, start, high, 100), toLow = edge(cache, start, low, TraversalEdge.Action.WALK);
            TraversalEdge improved = edge(cache, low, high, TraversalEdge.Action.STEP), viaHigh = edge(cache, high, join, TraversalEdge.Action.WALK);
            TraversalEdge competing = pricedEdge(cache, low, join, 101), finish = edge(cache, join, goal, TraversalEdge.Action.WALK);
            AtomicInteger highScans = new AtomicInteger();
            NavigationPlanner.Search search = new NavigationPlanner.Search(start, goal.feet(), UUID.randomUUID(), PROFILE,
                    cache, new NavigationPlanner.NeighborSource() {
                        @Override public NavigationPlanner.NeighborScan open(SurfaceNode from) {
                            if (from.equals(start)) return graphScan(List.of(expensive, toLow));
                            if (from.equals(low)) return graphScan(List.of(improved, competing));
                            if (from.equals(high)) {
                                highScans.incrementAndGet();
                                return stagedScan(List.of(viaHigh), false, null);
                            }
                            return stagedScan(List.of(), false, null);
                        }
                        @Override public NavigationPlanner.NeighborScan openDetailed(SurfaceNode from) {
                            return graphScan(from.equals(high) ? List.of(viaHigh) : List.of(finish));
                        }
                    }, NavigationPlanner.RecordPool.bounded(32));
            try {
                run(search, work, 1000);
                require(search.result().status() == PlanningResult.Status.READY && highScans.get() >= 2,
                        "a closed coarse node reached more cheaply must re-propagate its proved native connections");
                require(search.result().plan().edges().equals(List.of(toLow, improved, viaHigh, finish)),
                        "the cheaper reopened route must displace the competing downstream predecessor");
            } finally {
                search.close();
                require(cache.stats().transientEntries() == 0, "reopening cannot duplicate deferred-node reservations");
            }
        }
    }
    private static NavigationPlanner.NeighborScan stagedScan(List<TraversalEdge> edges, boolean exhaustive, AtomicInteger closed) {
        NavigationPlanner.NeighborScan delegate = graphScan(edges);
        return new NavigationPlanner.NeighborScan() {
            @Override public TraversalValidator.NeighborBatch advance(int units, long deadline) {
                return delegate.advance(units, deadline);
            }
            @Override public boolean exhaustive() { return exhaustive; }
            @Override public void close() { if (closed != null) closed.incrementAndGet(); }
        };
    }
    private static TraversalEdge pricedEdge(NavigationGraphCache cache, SurfaceNode from, SurfaceNode to, double cost) {
        TraversalEdge edge = edge(cache, from, to, TraversalEdge.Action.WALK);
        return new TraversalEdge(from, to, edge.action(), edge.controlPoints(), cost, edge.dependencies());
    }
    private static void ascentPreferenceRetainsARequiredDescentPastAHigherDeadEnd() {
        for (int work : new int[]{1, 16, 64}) {
            NavigationGraphCache cache = new NavigationGraphCache();
            Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
            SurfaceNode start = node(cache, 0, 4, 0), low = node(cache, 0, 0, 1);
            SurfaceNode previous = start;
            for (int i = 1; i <= 8; i++) {
                SurfaceNode next = node(cache, i, 4 + i, 0);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.STEP));
                previous = next;
            }
            TraversalEdge descent = edge(cache, start, low, TraversalEdge.Action.DROP);
            connect(graph, descent);
            previous = low;
            for (int x = -1; x >= -10; x--) {
                SurfaceNode next = node(cache, x, 0, 1);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.WALK));
                previous = next;
            }
            for (int y = 1; y <= 12; y++) {
                SurfaceNode next = node(cache, -10, y, 1 + y);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.STEP));
                previous = next;
            }
            for (int x = -9; x <= 0; x++) {
                SurfaceNode next = node(cache, x, 12, 13);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.WALK));
                previous = next;
            }
            for (int z = 12; z >= 4; z--) {
                SurfaceNode next = node(cache, 0, 12, z);
                connect(graph, edge(cache, previous, next, TraversalEdge.Action.WALK));
                previous = next;
            }
            SurfaceNode destination = previous;
            AtomicBoolean highDeadEndExplored = new AtomicBoolean();
            NavigationPlanner.Search search = new NavigationPlanner.Search(start, destination.feet(), UUID.randomUUID(),
                    PROFILE, cache, from -> {
                        if (from.feet().x > 0) highDeadEndExplored.set(true);
                        return graphScan(graph.getOrDefault(from, List.of()));
                    }, NavigationPlanner.RecordPool.bounded(256));
            try {
                for (int tick = 1; tick <= 10000 && !search.finished(); tick++) {
                    var slice = budget(work, tick);
                    search.advance(slice);
                    require(slice.expansionsUsed() <= work && slice.geometryUsed() <= work,
                            "ascent preference must retain both per-slice work limits");
                }
                require(search.result().status() == PlanningResult.Status.READY && highDeadEndExplored.get(),
                        "an enticing higher dead end must not hide the only route, which first descends");
                List<TraversalEdge> route = search.result().plan().edges();
                require(route.contains(descent) && route.get(route.size() - 1).to().equals(destination)
                                && route.stream().allMatch(e -> graph.getOrDefault(e.from(), List.of()).contains(e)),
                        "the result must keep the proven directed drop and every original forward edge");
                require(search.recordCount() == 0, "completion must release ascent search records");
            } finally {
                search.close();
                require(cache.stats().transientEntries() == 0, "each work budget must return all transient capacity");
            }
            NavigationGraphCache reverseCache = new NavigationGraphCache();
            SurfaceNode reverseStart = node(reverseCache, 0, 0, 1), reverseTarget = node(reverseCache, 0, 4, 0);
            TraversalEdge onlyDrop = edge(reverseCache, reverseTarget, reverseStart, TraversalEdge.Action.DROP);
            NavigationPlanner.Search reverseDrop = search(reverseCache, reverseStart, reverseTarget,
                    Map.of(reverseTarget, List.of(onlyDrop)), 16);
            try {
                run(reverseDrop, work, 100);
                require(reverseDrop.result().status() == PlanningResult.Status.UNREACHABLE,
                        "an upper target cannot turn a downward-only drop into an invented climb");
            } finally {
                reverseDrop.close();
                require(reverseCache.stats().transientEntries() == 0, "failed directed ascent must also release capacity");
            }
        }
    }
    private static void ascentPreferenceExploresLessOfAWideLowerFloor() {
        NavigationGraphCache cache = new NavigationGraphCache();
        Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
        Map<Vec3, SurfaceNode> lower = new HashMap<>();
        for (int x = -24; x <= 24; x++) for (int z = -12; z <= 32; z++) {
            if (x == 0 && z >= 19 && z <= 30) continue; // Solid staircase occupies these lower cells.
            SurfaceNode node = node(cache, x, 0, z);
            lower.put(node.feet(), node);
        }
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (SurfaceNode from : lower.values()) for (int[] direction : directions) {
            SurfaceNode to = lower.get(from.feet().add(direction[0], 0, direction[1]));
            if (to == null) continue;
            if (direction[0] != 0 && direction[1] != 0
                    && (!lower.containsKey(from.feet().add(direction[0], 0, 0))
                            || !lower.containsKey(from.feet().add(0, 0, direction[1])))) continue;
            connect(graph, edge(cache, from, to, TraversalEdge.Action.WALK));
        }
        SurfaceNode previous = lower.get(new Vec3(0, 0, 18));
        for (int y = 1; y <= 12; y++) {
            SurfaceNode next = node(cache, 0, y, 18 + y);
            connect(graph, edge(cache, previous, next, TraversalEdge.Action.STEP));
            previous = next;
        }
        for (int z = 29; z >= 0; z--) {
            SurfaceNode next = node(cache, 0, 12, z);
            connect(graph, edge(cache, previous, next, TraversalEdge.Action.WALK));
            previous = next;
        }
        SurfaceNode start = lower.get(Vec3.ZERO), destination = previous;
        int unweightedExpansions = unweightedExpansions(graph, start, destination);
        AtomicInteger opened = new AtomicInteger();
        NavigationPlanner.Search search = new NavigationPlanner.Search(start, destination.feet(), UUID.randomUUID(),
                PROFILE, cache, from -> {
                    opened.incrementAndGet();
                    return graphScan(graph.getOrDefault(from, List.of()));
                }, NavigationPlanner.RecordPool.bounded(NavigationTuning.DEFAULT.searchRecords()));
        try {
            run(search, 64, 20000);
            require(search.result().status() == PlanningResult.Status.READY,
                    "a wide lower floor must connect to the distant upper target through its staircase");
            require(search.result().plan().edges().stream().anyMatch(e -> e.action() == TraversalEdge.Action.STEP),
                    "the accelerated search must retain the real staircase in its executable route");
            require(opened.get() < unweightedExpansions,
                    "prioritizing discovered ascent must scan fewer nodes than Euclidean A* on the same graph; guided="
                            + opened.get() + ", unweighted=" + unweightedExpansions);
        } finally {
            search.close();
            require(cache.stats().transientEntries() == 0, "the accelerated wide-floor search releases its retained route");
        }
    }
    /** Independent comparison counts complete outgoing expansions; it supplies no edges to production search. */
    private static int unweightedExpansions(Map<SurfaceNode, List<TraversalEdge>> graph,
            SurfaceNode start, SurfaceNode destination) {
        record Entry(SurfaceNode node, double distance, double score, long order) { }
        PriorityQueue<Entry> frontier = new PriorityQueue<>(Comparator.comparingDouble(Entry::score)
                .thenComparingLong(Entry::order));
        Map<SurfaceNode, Double> distances = new HashMap<>();
        Set<SurfaceNode> closed = new HashSet<>();
        long order = 0;
        distances.put(start, 0.0);
        frontier.add(new Entry(start, 0, start.feet().distanceTo(destination.feet()), order++));
        int expansions = 0;
        while (!frontier.isEmpty()) {
            Entry current = frontier.remove();
            if (closed.contains(current.node()) || Double.compare(current.distance(), distances.get(current.node())) != 0) continue;
            if (current.node().equals(destination)) return expansions;
            closed.add(current.node());
            expansions++;
            for (TraversalEdge edge : graph.getOrDefault(current.node(), List.of())) {
                double distance = current.distance() + edge.cost();
                if (distance >= distances.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) continue;
                distances.put(edge.to(), distance);
                closed.remove(edge.to());
                frontier.add(new Entry(edge.to(), distance,
                        distance + edge.to().feet().distanceTo(destination.feet()), order++));
            }
        }
        throw new AssertionError("the independent wide-floor graph must be reachable");
    }
    private static void connect(Map<SurfaceNode, List<TraversalEdge>> graph, TraversalEdge edge) {
        graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
    }
    private static void longRouteHasNoRadiusOrLengthCutoff() {
        List<TraversalEdge> small = longRoute(1), large = longRoute(64);
        require(small.size() == 192 && large.size() == 192, "192-block route must survive both work budgets");
        for (int i = 0; i < small.size(); i++) require(small.get(i).to().feet().equals(large.get(i).to().feet()),
                "budget may change completion time, not the deterministic corridor route");
    }
    private static List<TraversalEdge> longRoute(int budget) {
        NavigationGraphCache cache = new NavigationGraphCache();
        Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
        SurfaceNode first = node(cache, 0, 0), previous = first;
        for (int i = 1; i <= 192; i++) {
            SurfaceNode next = node(cache, i, 0); graph.put(previous, List.of(edge(cache, previous, next, TraversalEdge.Action.WALK)));
            previous = next;
        }
        NavigationPlanner.Search search = search(cache, first, previous, graph, 4096);
        run(search, budget, 3000);
        require(search.result().status() == PlanningResult.Status.READY, "long route must be ready");
        require(search.recordCount() == 0, "completed search must release open/closed records");
        return search.result().plan().edges();
    }
    private static void unknownFrontierResumesAfterChunkLoads() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge edge = edge(cache, a, b, TraversalEdge.Action.WALK);
        AtomicBoolean loaded = new AtomicBoolean();
        NavigationPlanner.Search search = new NavigationPlanner.Search(a, b.feet(), UUID.randomUUID(), PROFILE, cache,
                from -> (units, deadline) -> loaded.get()
                        ? new TraversalValidator.NeighborBatch(List.of(edge), TraversalValidator.ScanStatus.COMPLETE, 1)
                        : new TraversalValidator.NeighborBatch(List.of(), TraversalValidator.ScanStatus.WAITING_CHUNK, 1),
                NavigationPlanner.RecordPool.bounded(20));
        search.advance(budget(10, 1));
        require(search.result().status() == PlanningResult.Status.WAITING_CHUNK, "unknown frontier is retained");
        loaded.set(true); search.advance(budget(10, 20));
        require(search.result().status() == PlanningResult.Status.READY, "same cursor must resume after chunk load");
    }
    private static void resourcePressureIsNotUnreachable() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        NavigationPlanner.Search search = search(cache, a, b,
                Map.of(a, List.of(edge(cache, a, b, TraversalEdge.Action.WALK))), 1);
        run(search, 16, 10);
        require(search.result().status() == PlanningResult.Status.PENDING
                && search.result().reason() == PlanningResult.Reason.RESOURCE_LIMITED, "record exhaustion must remain pending");
        search.close(); require(search.recordCount() == 0, "cancel releases limited searches");
    }
    private static void dropIsDirectedAndDoesNotMergeRegions() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode high = node(cache, 0, 6), low = node(cache, 0, 0);
        TraversalEdge drop = edge(cache, high, low, TraversalEdge.Action.DROP);
        cache.putEdges(high, PROFILE, List.of(drop)); cache.putEdges(low, PROFILE, List.of());
        require(!cache.region(high, PROFILE).equals(cache.region(low, PROFILE)), "drop cannot union same-tile floors");
        NavigationPlanner.Search forward = search(cache, high, low, Map.of(high, List.of(drop)), 20);
        run(forward, 16, 50); require(forward.result().status() == PlanningResult.Status.READY, "forward drop is reachable");
        NavigationPlanner.Search reverse = search(cache, low, high, Map.of(high, List.of(drop)), 20);
        run(reverse, 16, 50); require(reverse.result().status() == PlanningResult.Status.UNREACHABLE, "drop cannot invent reverse climb");
    }
    private static void failureMemoryIsDirectedVersionedAndProfileScoped() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge forward = edge(cache, a, b, TraversalEdge.Action.WALK), reverse = edge(cache, b, a, TraversalEdge.Action.WALK);
        cache.reportExecutionFailure(forward, PROFILE, NavigationGraphCache.FailureEvidence.DYNAMIC_CONGESTION);
        require(!cache.isFailed(forward, PROFILE), "congestion must not poison static geometry");
        cache.reportExecutionFailure(forward, PROFILE, NavigationGraphCache.FailureEvidence.STATIC_COLLISION);
        require(cache.isFailed(forward, PROFILE) && !cache.isFailed(reverse, PROFILE), "failure is directed");
        MovementProfile shortMob = new MovementProfile(.4, .3, 1, false, true, true, "short");
        require(!cache.isFailed(forward, shortMob), "failure must respect body/context capability");
        cache.invalidate(a.tile().bounds(), "door changed");
        require(!cache.isFailed(forward, PROFILE) && !cache.valid(forward), "geometry revision releases old failure and evidence");
    }
    private static void temporaryAvoidanceCannotBecomeGeometricFailure() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge edge = edge(cache, a, b, TraversalEdge.Action.WALK);
        AtomicBoolean allowed = new AtomicBoolean();
        NavigationPlanner.Search search = new NavigationPlanner.Search(a, b.feet(), UUID.randomUUID(), PROFILE, cache,
                from -> (units, deadline) -> new TraversalValidator.NeighborBatch(from.equals(a) ? List.of(edge) : List.of(),
                        TraversalValidator.ScanStatus.COMPLETE, 1), NavigationPlanner.RecordPool.bounded(20), ignored -> allowed.get());
        search.advance(budget(10, 1));
        require(search.result().status() == PlanningResult.Status.PENDING, "temporary exclusion is not disconnected terrain");
        allowed.set(true); search.advance(budget(10, 2));
        require(search.result().status() == PlanningResult.Status.READY, "expired exclusion reopens the retained edge");
    }
    private static void schedulerSharesBudgetAndRotatesRoomsAndMobs() {
        NavigationTuning tuning = new NavigationTuning(1_000_000_000L, 1_000_000_000L,
                1, 2, 2, 4, 1, 1, 20, 100, 100, 100, 20);
        NavigationScheduler scheduler = new NavigationScheduler(tuning);
        List<AtomicInteger> work = new ArrayList<>();
        for (int room = 0; room < 2; room++) {
            UUID roomId = UUID.randomUUID();
            for (int mob = 0; mob < 2; mob++) {
                AtomicInteger count = new AtomicInteger(); work.add(count);
                scheduler.submit(roomId, UUID.randomUUID(), new NavigationScheduler.Work() {
                    @Override public void advance(NavigationScheduler.Budget budget) { if (budget.takeExpansion()) count.incrementAndGet(); }
                    @Override public boolean finished() { return false; }
                });
            }
        }
        for (int tick = 0; tick < 8; tick++) {
            scheduler.advance(tick);
            require(scheduler.metrics().expansions() == 2, "server limit is shared across rooms");
            int before = work.stream().mapToInt(AtomicInteger::get).sum();
            scheduler.advance(tick);
            require(before == work.stream().mapToInt(AtomicInteger::get).sum(), "same tick cannot spend twice");
        }
        require(work.stream().allMatch(count -> count.get() == 4), "both rooms and all entities get a turn");
        scheduler.reset(); require(scheduler.jobCount() == 0 && scheduler.roomCount() == 0, "reset releases queues");
    }
    private static void singleRoomBorrowsIdleTimeWithinOriginalQuantityLimits() {
        for (long unitCost : new long[]{50_000L, 0L}) {
            SchedulerClock clock = new SchedulerClock();
            NavigationScheduler scheduler = new NavigationScheduler(NavigationTuning.DEFAULT, clock);
            AtomicInteger count = new AtomicInteger();
            scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), geometryWork(clock, unitCost, count, null, 0));
            scheduler.advance(1);
            require(count.get() == (unitCost == 0 ? 256 : 80),
                    "one room may use idle server time but cannot exceed its original 256 geometry units");
            require(scheduler.metrics().elapsedNanos() == (unitCost == 0 ? 0L : 4_000_000L)
                            && scheduler.metrics().geometry() == count.get(),
                    "borrowed work must remain inside the original 4ms server deadline and be accounted once");
            require(scheduler.timing().maxGeometryUnitNanos() == unitCost && scheduler.timing().geometryOverruns() == 0,
                    "geometry timing must share the deterministic scheduler clock");
            require(scheduler.timing().borrowedSlices() == (unitCost == 0 ? 0 : 4)
                            && scheduler.timing().borrowedNanos() == (unitCost == 0 ? 0L : 3_000_000L),
                    "borrowing counters must include only second-phase slices and their cumulative wall time");
        }
        SchedulerClock clock = new SchedulerClock();
        NavigationScheduler scheduler = new NavigationScheduler(NavigationTuning.DEFAULT, clock);
        AtomicInteger count = new AtomicInteger();
        scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), new NavigationScheduler.Work() {
            @Override public void advance(NavigationScheduler.Budget budget) {
                int inSlice = 0;
                while (budget.takeExpansion()) { count.incrementAndGet(); inSlice++; }
                require(inSlice <= 64, "borrowing must preserve the 64 expansion slice cap");
            }
            @Override public boolean finished() { return false; }
        });
        scheduler.advance(1);
        require(count.get() == 1024 && scheduler.metrics().expansions() == 1024,
                "idle server time must not increase the original per-room expansion cap");
    }
    private static void everyRoomReceivesItsBaseAllowanceBeforeFairBorrowing() {
        SchedulerClock clock = new SchedulerClock();
        NavigationScheduler scheduler = new NavigationScheduler(schedulerTuning(90, 360), clock);
        List<Integer> order = new ArrayList<>();
        List<AtomicInteger> counts = new ArrayList<>();
        for (int room = 0; room < 3; room++) {
            AtomicInteger count = new AtomicInteger(); counts.add(count);
            scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), geometryWork(clock, 10, count, order, room));
        }
        AtomicInteger waitingCalls = new AtomicInteger(), cappedUnits = new AtomicInteger();
        scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), new NavigationScheduler.Work() {
            @Override public void advance(NavigationScheduler.Budget budget) {
                require(waitingCalls.incrementAndGet() == 1, "waiting rooms must not participate in borrowed time shares");
            }
            @Override public boolean finished() { return false; }
        });
        scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), geometryWork(clock, 0, cappedUnits, null, 3));
        scheduler.advance(1);
        require(cappedUnits.get() == 256, "a quantity-capped room must be excluded from remaining time shares");
        require(counts.stream().allMatch(count -> count.get() == 12),
                "three busy rooms must each receive nine base and three borrowed geometry units");
        for (int index = 0; index < 27; index++)
            require(order.get(index) == index / 9, "every base room allowance must precede the first borrowed slice");
        for (int index = 27; index < 36; index++)
            require(order.get(index) == (index - 27) / 3, "the first borrower must not consume its peers' remaining time");
        require(scheduler.metrics().elapsedNanos() == 360 && scheduler.metrics().geometry() == 292,
                "all fair shares together must respect the server deadline");
    }
    private static void fullyLoadedRoomsKeepRotatingWithinTheServerDeadline() {
        for (int rooms : new int[]{4, 5}) {
            SchedulerClock clock = new SchedulerClock();
            NavigationScheduler scheduler = new NavigationScheduler(schedulerTuning(90, 360), clock);
            List<AtomicInteger> counts = new ArrayList<>();
            for (int room = 0; room < rooms; room++) {
                AtomicInteger count = new AtomicInteger(); counts.add(count);
                scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), geometryWork(clock, 10, count, null, room));
            }
            for (int tick = 1; tick <= rooms; tick++) {
                scheduler.advance(tick);
                require(scheduler.metrics().elapsedNanos() == 360 && scheduler.metrics().geometry() == 36,
                        "a saturated server must stop at its original time budget on every tick");
                require(scheduler.timing().borrowedSlices() == 0 && scheduler.timing().borrowedNanos() == 0,
                        "a fully saturated base round must never borrow extra server time");
                int min = counts.stream().mapToInt(AtomicInteger::get).min().orElseThrow();
                int max = counts.stream().mapToInt(AtomicInteger::get).max().orElseThrow();
                require(max - min <= 9, "the next tick must begin with rooms denied the previous tick's base share");
            }
            require(counts.stream().allMatch(count -> count.get() == 36),
                    "four or five continuously busy rooms must receive equal total work across a full rotation");
        }
    }
    private static void waitingJobsAndEmptyRoomsCannotSpinOrSpendTheSameTickTwice() {
        SchedulerClock clock = new SchedulerClock();
        NavigationScheduler scheduler = new NavigationScheduler(schedulerTuning(90, 360), clock);
        AtomicInteger prunes = new AtomicInteger();
        UUID liveRoom = UUID.randomUUID(), emptyRoom = UUID.randomUUID();
        scheduler.register(liveRoom, null, (bounds, reason) -> { }, prunes::incrementAndGet);
        List<AtomicInteger> calls = new ArrayList<>();
        for (int job = 0; job < 3; job++) {
            AtomicInteger count = new AtomicInteger(); calls.add(count);
            scheduler.submit(liveRoom, UUID.randomUUID(), new NavigationScheduler.Work() {
                @Override public void advance(NavigationScheduler.Budget budget) {
                    require(count.incrementAndGet() <= budget.serverTick(),
                            "a waiting job must never be retried in a zero-time busy loop or in the borrowing phase");
                }
                @Override public boolean finished() { return false; }
            });
        }
        scheduler.submit(emptyRoom, UUID.randomUUID(), new NavigationScheduler.Work() {
            @Override public void advance(NavigationScheduler.Budget budget) { throw new AssertionError("finished work must not run"); }
            @Override public boolean finished() { return true; }
        });
        scheduler.advance(1); scheduler.advance(1);
        require(prunes.get() == 1 && calls.stream().allMatch(count -> count.get() == 1),
                "same-tick deduplication must cover pruning and both scheduling phases");
        require(scheduler.metrics().slices() == 3 && scheduler.metrics().elapsedNanos() == 0
                        && scheduler.roomCount() == 1 && scheduler.jobCount() == 3,
                "empty rooms must be removed and waiting jobs must finish the tick without consuming fake time");
        scheduler.advance(2);
        require(prunes.get() == 2 && calls.stream().allMatch(count -> count.get() == 2),
                "waiting work must become eligible again on the next server tick");
        scheduler.reset(); scheduler.advance(3);
        require(scheduler.metrics().slices() == 0 && scheduler.registeredRoomCount() == 0,
                "a fully empty scheduler must leave both phases immediately");
    }
    private static void cancellationDuringASlicePreservesReplacementAndNewRoomWork() {
        SchedulerClock clock = new SchedulerClock();
        NavigationScheduler scheduler = new NavigationScheduler(schedulerTuning(90, 360), clock);
        UUID room = UUID.randomUUID(), mob = UUID.randomUUID();
        AtomicInteger replacements = new AtomicInteger(), newRoom = new AtomicInteger();
        NavigationScheduler.Work replacement = oneExpansionWork(replacements);
        scheduler.submit(room, mob, new NavigationScheduler.Work() {
            private boolean done;
            @Override public void advance(NavigationScheduler.Budget budget) {
                require(budget.takeExpansion(), "the canceled slice must still account its original unit");
                scheduler.cancel(room, mob);
                scheduler.submit(room, mob, replacement);
                scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), oneExpansionWork(newRoom));
                done = true;
            }
            @Override public boolean finished() { return done; }
        });
        scheduler.advance(1);
        require(replacements.get() == 1 && newRoom.get() == 1 && scheduler.metrics().expansions() == 3,
                "finishing a canceled job must not cancel its same-ID replacement or starve a newly submitted room");
        require(scheduler.jobCount() == 0 && scheduler.roomCount() == 0,
                "self-cancellation, replacement completion and new-room completion must leave no rotation entries");
    }
    private static void schedulerAndUnitTimingUseTheSameClock() {
        SchedulerClock clock = new SchedulerClock();
        NavigationScheduler scheduler = new NavigationScheduler(schedulerTuning(9, 9), clock);
        scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), geometryWork(clock, 10, new AtomicInteger(), null, 0));
        scheduler.advance(1);
        require(scheduler.metrics().geometry() == 1 && scheduler.metrics().elapsedNanos() == 10
                        && scheduler.timing().maxSliceNanos() == 10 && scheduler.timing().maxGeometryUnitNanos() == 10
                        && scheduler.timing().geometryOverruns() == 1 && scheduler.timing().exceededSlices() == 1,
                "an indivisible unit crossing the deadline must be measured once in the same clock domain");
    }
    private static NavigationTuning schedulerTuning(long roomNanos, long serverNanos) {
        NavigationTuning defaults = NavigationTuning.DEFAULT;
        return new NavigationTuning(roomNanos, serverNanos, defaults.roomExpansions(), defaults.roomGeometry(),
                defaults.serverExpansions(), defaults.serverGeometry(), defaults.sliceExpansions(), defaults.sliceGeometry(),
                defaults.residentTiles(), defaults.surfaceNodes(), defaults.fineEdges(), defaults.searchRecords(), defaults.failedEdges());
    }
    private static NavigationScheduler.Work geometryWork(SchedulerClock clock, long cost, AtomicInteger count,
            List<Integer> order, int room) {
        return new NavigationScheduler.Work() {
            @Override public void advance(NavigationScheduler.Budget budget) {
                int inSlice = 0;
                while (budget.geometryRemaining() > 0) {
                    long started = clock.getAsLong();
                    budget.accountGeometry(1); count.incrementAndGet(); inSlice++;
                    if (order != null) order.add(room);
                    clock.nanos += cost;
                    budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, started);
                }
                require(inSlice <= 16, "borrowing must preserve the 16 geometry slice cap");
            }
            @Override public boolean finished() { return false; }
        };
    }
    private static NavigationScheduler.Work oneExpansionWork(AtomicInteger count) {
        return new NavigationScheduler.Work() {
            @Override public void advance(NavigationScheduler.Budget budget) { if (budget.takeExpansion()) count.incrementAndGet(); }
            @Override public boolean finished() { return count.get() != 0; }
        };
    }
    private static final class SchedulerClock implements LongSupplier {
        long nanos;
        @Override public long getAsLong() { return nanos; }
    }
    private static void portalPathsRetainFineEvidenceAndInvalidateLocally() {
        NavigationGraphCache cache = new NavigationGraphCache();
        List<TraversalEdge> edges = new ArrayList<>(); SurfaceNode first = node(cache, 0, 0), previous = first;
        for (int i = 1; i <= 20; i++) { SurfaceNode next = node(cache, i, 0);
            edges.add(edge(cache, previous, next, TraversalEdge.Action.WALK)); previous = next; }
        RoutePlan plan = new RoutePlan(UUID.randomUUID(), UUID.randomUUID(), previous.feet(), PROFILE, edges, List.of(first, previous), Map.of());
        cache.rememberRoute(plan);
        List<NavigationGraphCache.PortalPath> firstPortal = cache.portalPaths(first, PROFILE);
        require(firstPortal.size() == 1 && firstPortal.get(0).edges().size() == 8, "tile portal caches actual entry/exit fine evidence");
        SurfaceNode remote = edges.get(16).from();
        require(!cache.portalPaths(remote, PROFILE).isEmpty(), "other region has a directed portal path");
        NavigationPlanner.Search hierarchical = search(cache, first, previous, Map.of(), 4);
        run(hierarchical, 16, 50);
        require(hierarchical.result().status() == PlanningResult.Status.READY
                && hierarchical.result().plan().edges().size() == 20,
                "four portal records must refine into all twenty executable fine edges without fresh geometry");
        cache.invalidate(first.tile().bounds(), "local change");
        require(cache.portalPaths(first, PROFILE).isEmpty(), "affected portal path invalidates");
        require(!cache.portalPaths(remote, PROFILE).isEmpty(), "unaffected distant portal evidence remains reusable");
    }
    private static void fractionalFloorsAndNegativeTilesDoNotAlias() {
        NavigationGraphCache cache = new NavigationGraphCache();
        require(!node(cache, 0, 0).equals(node(cache, 0, .5)), "half slab surfaces are separate nodes");
        require(NavigationGraphCache.TileKey.at(new Vec3(-.1, -.1, -.1)).equals(new NavigationGraphCache.TileKey(-1, -1, -1)),
                "negative coordinates use floor division");
    }
    private static void targetMotionKeepsOpenFrontier() {
        NavigationGraphCache cache = new NavigationGraphCache();
        Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
        SurfaceNode first = node(cache, 0, 0), previous = first;
        for (int i = 1; i <= 7; i++) { SurfaceNode next = node(cache, i, 0);
            graph.put(previous, List.of(edge(cache, previous, next, TraversalEdge.Action.WALK))); previous = next; }
        NavigationPlanner.Search search = search(cache, first, node(cache, 5, 0), graph, 20);
        search.advance(budget(1, 1)); int count = search.recordCount();
        require(search.retarget(previous.feet()), "same-tile motion must reuse the search");
        require(search.recordCount() == count, "moving target must retain existing open/closed records");
        run(search, 1, 100);
        require(search.result().status() == PlanningResult.Status.READY && search.result().plan().edges().size() == 7,
                "refinement follows the updated endpoint in the same standing tile");
    }
    private static void crossTileRetargetRequestsRestartWithoutChangingSearch() {
        for (Vec3 next : List.of(new Vec3(8, 7.5, 0), new Vec3(-.1, 7.5, 0),
                new Vec3(5.1, 8.1, 0), new Vec3(5, -.1, 0), new Vec3(5, 7.5, 8), new Vec3(5, 7.5, -.1))) {
            NavigationGraphCache cache = new NavigationGraphCache();
            Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
            SurfaceNode start = node(cache, 0, 7.5), target = node(cache, 5, 7.5), previous = start;
            for (int x = 1; x <= 7; x++) {
                SurfaceNode node = node(cache, x, 7.5);
                graph.put(previous, List.of(edge(cache, previous, node, TraversalEdge.Action.WALK)));
                previous = node;
            }
            NavigationPlanner.Search search = search(cache, start, target, graph, 32);
            search.advance(budget(1, 1));
            int records = search.recordCount(), memory = cache.stats().transientEntries();
            PlanningResult before = search.result();
            require(records > 0 && !search.completionInProgress(), "fixture has a live, unfrozen frontier");
            require(!search.retarget(next), "cross-tile motion must request a restart without throwing");
            require(search.target().equals(target.feet()) && search.recordCount() == records
                            && cache.stats().transientEntries() == memory && search.result() == before,
                    "a refused retarget cannot partially change the old target, frontier or resource ownership");
            require(search.retarget(previous.feet()), "a refused target cannot poison subsequent same-tile reuse");
            run(search, 1, 100);
            require(search.result().status() == PlanningResult.Status.READY
                            && search.result().plan().targetSnapshot().equals(previous.feet()),
                    "the retained frontier must still complete to an accepted target");
            search.close();
            require(cache.stats().transientEntries() == 0, "closing a retargeted search releases all reservations");
        }
    }
    private static void frozenGoalKeepsCompletionAcrossTileBoundaries() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode start = node(cache, 0, 0), target = node(cache, 7, 0);
        require(cache.putEdges(start, PROFILE, List.of(edge(cache, start, target, TraversalEdge.Action.WALK))),
                "fixture caches a proven connection to the target");
        NavigationPlanner.Search search = search(cache, start, target, Map.of(), 32);
        search.advance(budget(1, 1));
        require(search.completionInProgress() && search.recordCount() == 2,
                "one expansion freezes the proven goal before route reconstruction starts");
        require(search.retarget(new Vec3(8.1, 0, 0)) && search.target().equals(target.feet()),
                "cross-tile motion cannot cancel a frozen goal or relabel its proven endpoint");
        for (int tick = 2; tick < 100 && !search.finished(); tick++) {
            require(search.retarget(new Vec3(8.1 + tick, 0, 0)), "completion must accept continued target motion");
            search.advance(budget(1, tick));
        }
        require(search.result().status() == PlanningResult.Status.READY
                        && search.result().plan().targetSnapshot().equals(target.feet()),
                "frozen completion publishes its proven snapshot despite movement across tiles");
        search.close();
        require(cache.stats().transientEntries() == 0, "closing the frozen route releases all reservations");
    }
    private static void failureProbeRequiresObservedShapeChange() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge edge = edge(cache, a, b, TraversalEdge.Action.WALK);
        cache.reportExecutionFailure(edge, PROFILE, NavigationGraphCache.FailureEvidence.STATIC_COLLISION);
        NavigationGraphCache.FailedEdge failure = cache.nextFailureToProbe(PROFILE, 0);
        require(failure != null && !cache.observeFailureShape(failure, 123, 0), "first probe records shape evidence");
        require(cache.nextFailureToProbe(PROFILE, 39) == null, "probe cadence does not reset on replanning");
        failure = cache.nextFailureToProbe(PROFILE, 40);
        require(failure != null && !cache.observeFailureShape(failure, 123, 40) && cache.isFailed(edge, PROFILE),
                "unchanged geometry remains failed after the timer expires");
        failure = cache.nextFailureToProbe(PROFILE, 80);
        require(cache.observeFailureShape(failure, 124, 80), "observed new shape triggers local invalidation");
        cache.invalidate(a.tile().bounds(), "shape changed");
        require(!cache.isFailed(edge, PROFILE), "changed geometry permits discovery again");
    }
    private static void nearTargetNeedsAProvenConnection() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode start = node(cache, 0, 0), target = node(cache, .5, 0);
        NavigationPlanner.Search blocked = search(cache, start, target, Map.of(), 10);
        run(blocked, 16, 10);
        require(blocked.result().status() == PlanningResult.Status.UNREACHABLE,
                "half-block separation across a thin wall is not an empty completed route");
        TraversalEdge connection = edge(cache, start, target, TraversalEdge.Action.PRECISE_MOVE);
        NavigationPlanner.Search connected = new NavigationPlanner.Search(start, target.feet(), UUID.randomUUID(), PROFILE,
                cache, from -> (units, deadline) -> new TraversalValidator.NeighborBatch(List.of(), TraversalValidator.ScanStatus.COMPLETE, 1),
                NavigationPlanner.RecordPool.bounded(10), ignored -> true,
                from -> (units, deadline) -> new TraversalValidator.NeighborBatch(List.of(connection), TraversalValidator.ScanStatus.COMPLETE, 1));
        run(connected, 16, 10);
        require(connected.result().status() == PlanningResult.Status.READY && connected.result().plan().edges().size() == 1,
                "a proven fractional final connection is included in executable route evidence");
    }
    private static void partialScansCanReceiveAnotherFairSliceInSameTick() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge edge = edge(cache, a, b, TraversalEdge.Action.WALK);
        AtomicInteger steps = new AtomicInteger();
        NavigationPlanner.Search search = new NavigationPlanner.Search(a, b.feet(), UUID.randomUUID(), PROFILE,
                cache, from -> (units, deadline) -> steps.incrementAndGet() < 4
                        ? new TraversalValidator.NeighborBatch(List.of(), TraversalValidator.ScanStatus.PENDING, 1)
                        : new TraversalValidator.NeighborBatch(List.of(edge), TraversalValidator.ScanStatus.COMPLETE, 1),
                NavigationPlanner.RecordPool.bounded(10));
        NavigationTuning tuning = new NavigationTuning(1_000_000_000L, 1_000_000_000L,
                100, 8, 100, 8, 4, 1, 20, 100, 100, 100, 20);
        NavigationScheduler scheduler = new NavigationScheduler(tuning);
        scheduler.submit(UUID.randomUUID(), UUID.randomUUID(), search);
        scheduler.advance(1);
        require(search.result().status() == PlanningResult.Status.READY && scheduler.metrics().geometry() == 4,
                "fair slices do not impose an accidental one-shape-slice-per-tick ceiling");
    }
    private static void shapeCellsAreBoundedAndVersioned() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L, 10, 10, 20, 20, 2, 2, 2, 16, 32, 16, 4);
        NavigationGraphCache cache = new NavigationGraphCache(tuning);
        BlockPos first = new BlockPos(0, 0, 0), second = new BlockPos(1, 0, 0), third = new BlockPos(2, 0, 0);
        cache.putGeometryCell(PROFILE, 1, first, "first"); cache.putGeometryCell(PROFILE, 1, second, "second");
        cache.putGeometryCell(PROFILE, 1, third, "third");
        require(cache.stats().geometryCells() == 2 && cache.geometryCell(PROFILE, 1, first) == null,
                "shape cells obey the room-wide LRU capacity");
        require(cache.geometryCell(PROFILE, 2, second) == null, "projected collision feet must participate in the key");
        cache.updateTime(40);
        require(cache.geometryCell(PROFILE, 1, second) == null, "expired shape evidence is reread even without a block event");
        cache.invalidate(new net.minecraft.world.phys.AABB(third), "change");
        require(cache.stats().geometryCells() == 0, "tile invalidation releases shape cells");
    }
    private static void unreadWorldEventsCannotInvalidateObservedTopology() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        TraversalEdge edge = edge(cache, a, b, TraversalEdge.Action.WALK);
        cache.putEdges(a, PROFILE, List.of(edge));
        long revision = cache.revision();
        require(!cache.invalidate(new net.minecraft.world.phys.AABB(new BlockPos(1000, 0, 0)), "remote-neighbor"),
                "an event in unread world space has no topology evidence to invalidate");
        require(cache.revision() == revision && cache.valid(edge) && cache.edges(a, PROFILE) != null,
                "remote events must retain local search dependencies and cached adjacency");
        require(cache.stats().invalidationEvents() == 1 && cache.stats().invalidations() == 0
                && cache.stats().invalidationsByReason().get("remote-neighbor") == 1,
                "diagnostics distinguish incoming events from changes to observed geometry");
        require(cache.invalidate(new net.minecraft.world.phys.AABB(new BlockPos(1, 0, 0)), "local-change")
                && !cache.valid(edge) && cache.stats().invalidations() == 1,
                "a changed observed tile still invalidates all dependent movement evidence immediately");
    }
    private static void temporaryGeometryAndSearchMemoryIsSharedAndReleasable() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L,
                10, 10, 20, 20, 2, 2, 2, 16, 32, 16, 4);
        var shared = new NavigationGraphCache.TransientCapacity(20);
        NavigationGraphCache first = new NavigationGraphCache(tuning, shared);
        NavigationGraphCache second = new NavigationGraphCache(tuning, shared);
        var a = first.transientLease(); var b = first.transientLease(); var c = second.transientLease();
        require(a.reserve(12) && !b.reserve(5) && b.reserve(4), "all cursors in one room share its temporary ceiling");
        require(c.reserve(4) && !c.reserve(1), "separate rooms also share the server temporary ceiling");
        a.close(); require(c.reserve(12), "a released cursor returns capacity to another room");
        first.reset(); second.reset(); a.close(); b.release(4); c.close();
        require(shared.used() == 0 && shared.peak() == 20 && first.stats().transientEntries() == 0,
                "reset and late cursor cleanup release capacity exactly once");
        var producer = first.transientLease(); var consumer = first.transientLease();
        require(producer.reserve(7) && producer.transferTo(consumer, 7) && shared.used() == 7,
                "geometry output ownership transfers without double counting the room/server budget");
        producer.close(); require(shared.used() == 7, "closing a geometry cursor must retain edges already owned by search");
        consumer.close(); require(shared.used() == 0, "closing search finally releases transferred output");
    }
    private static void searchResumesWhenSharedTemporaryCapacityReturns() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L,
                10, 10, 20, 20, 2, 2, 2, 256, 1024, 16, 4);
        NavigationGraphCache cache = new NavigationGraphCache(tuning);
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0);
        var occupied = cache.transientLease(); require(occupied.reserve(256), "fixture consumes temporary capacity");
        NavigationPlanner.Search search = search(cache, a, b, Map.of(a, List.of(edge(cache, a, b, TraversalEdge.Action.WALK))), 16);
        search.advance(budget(16, 1));
        require(search.result().reason() == PlanningResult.Reason.RESOURCE_LIMITED && !search.finished(),
                "temporary cursor pressure is a retained request, never geometric disconnection");
        occupied.close(); run(search, 16, 20);
        require(search.result().status() == PlanningResult.Status.READY && search.recordCount() == 0,
                "the same search resumes after pressure is released and releases search records");
        search.close(); require(cache.stats().transientEntries() == 0, "closing the ready route releases its payload");
    }
    private static void cachedReadsTrackOnlyTheirActualDependencies() {
        NavigationGraphCache cache = new NavigationGraphCache();
        SurfaceNode a = node(cache, 0, 0), b = node(cache, 1, 0), remote = node(cache, 100, 0);
        cache.putEdges(a, PROFILE, List.of(edge(cache, a, b, TraversalEdge.Action.WALK)));
        java.util.Set<NavigationGraphCache.TileKey> observed = new java.util.HashSet<>();
        cache.observeReads(observed::add); cache.edges(a, PROFILE); cache.observeReads(null);
        cache.invalidate(remote.tile().bounds(), "other-request-area");
        require(java.util.Collections.disjoint(observed, cache.lastInvalidatedTiles()),
                "world changes used only by another request must not restart this cached frontier");
        cache.invalidate(a.tile().bounds(), "this-request-area");
        require(!java.util.Collections.disjoint(observed, cache.lastInvalidatedTiles()),
                "cached adjacency reads register their dependency tiles just like fresh geometry reads");
    }
    private static void completedScansReleaseNonPredecessorEdges() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L,
                100, 100, 100, 100, 16, 16, 64, 2048, 16384, 512, 32);
        NavigationGraphCache cache = new NavigationGraphCache(tuning);
        List<SurfaceNode> nodes = new ArrayList<>();
        for (int x = 0; x <= 192; x++) nodes.add(node(cache, x, 0));
        for (int x = 0; x < 192; x++) {
            List<TraversalEdge> outgoing = new ArrayList<>();
            outgoing.add(edge(cache, nodes.get(x), nodes.get(x + 1), TraversalEdge.Action.WALK));
            for (int back = Math.max(0, x - 8); back < x; back++)
                outgoing.add(edge(cache, nodes.get(x), nodes.get(back), TraversalEdge.Action.WALK));
            require(cache.putEdges(nodes.get(x), PROFILE, outgoing), "fixture fits the bounded resident graph");
        }
        NavigationPlanner.Search search = search(cache, nodes.get(0), nodes.get(192), Map.of(), 512);
        run(search, 16, 1000);
        require(search.result().status() == PlanningResult.Status.READY
                        && search.result().plan().edges().size() == 192,
                "many rejected back edges must not exhaust temporary memory after their expansions complete");
        require(cache.stats().peakTransientEntries() < 2048 && search.recordCount() == 0,
                "only live predecessors/scans and the retained final route consume transient capacity");
        search.close(); require(cache.stats().transientEntries() == 0, "closing the route releases remaining capacity");
    }
    private static void targetReturningToClosedNodesOnlyRefreshesItsTail() {
        for (int work : new int[]{1, 64}) {
            NavigationGraphCache cache = new NavigationGraphCache();
            List<SurfaceNode> nodes = new ArrayList<>();
            Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
            for (int x = 0; x <= 7; x++) nodes.add(node(cache, x, 0));
            for (int x = 0; x < 7; x++) graph.put(nodes.get(x), List.of(edge(cache, nodes.get(x), nodes.get(x+1), TraversalEdge.Action.WALK)));
            AtomicInteger startScans = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Vec3> target = new java.util.concurrent.atomic.AtomicReference<>(nodes.get(7).feet());
            NavigationPlanner.Search search = new NavigationPlanner.Search(nodes.get(0), target.get(), UUID.randomUUID(), PROFILE,
                    cache, from -> {
                        if (from.equals(nodes.get(0))) startScans.incrementAndGet();
                        return (units, deadline) -> new TraversalValidator.NeighborBatch(graph.getOrDefault(from, List.of()),
                                TraversalValidator.ScanStatus.COMPLETE, 1);
                    }, NavigationPlanner.RecordPool.bounded(100), ignored -> true,
                    from -> {
                        Vec3 snapshot = target.get();
                        SurfaceNode destination = new SurfaceNode(snapshot, cache.version(NavigationGraphCache.TileKey.at(snapshot)));
                        return (units, deadline) -> new TraversalValidator.NeighborBatch(
                                List.of(edge(cache, from, destination, TraversalEdge.Action.WALK)), TraversalValidator.ScanStatus.COMPLETE, 1);
                    });
            for (int tick = 1; tick <= 4; tick++) search.advance(budget(1, tick));
            require(!search.finished() && startScans.get() == 1, "the original start is already closed while distant frontier remains");
            target.set(new Vec3(.25, 0, 0)); search.retarget(target.get());
            run(search, work, 100);
            require(search.result().status() == PlanningResult.Status.READY
                            && search.result().plan().edges().size() == 1
                            && search.result().plan().targetSnapshot().equals(target.get()),
                    "a goal returning behind the closed frontier needs a fresh proven final connection, not UNREACHABLE");
            require(startScans.get() == 1 && search.recordCount() == 0,
                    "retargeting rechecks only the final connection and releases canceled stale terminal scans");
            search.close(); require(cache.stats().transientEntries() == 0, "closing the retargeted route releases capacity");
        }
    }
    private static void continuouslyMovingTargetCannotStarveLongReconstruction() {
        NavigationGraphCache cache = new NavigationGraphCache();
        List<SurfaceNode> nodes = new ArrayList<>();
        Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
        for (int x = 0; x <= 192; x++) nodes.add(node(cache, x, 0));
        for (int x = 0; x < 192; x++) graph.put(nodes.get(x),
                List.of(edge(cache, nodes.get(x), nodes.get(x+1), TraversalEdge.Action.WALK)));
        java.util.concurrent.atomic.AtomicReference<Vec3> latest = new java.util.concurrent.atomic.AtomicReference<>(new Vec3(192.2, 0, 0));
        NavigationPlanner.Search search = new NavigationPlanner.Search(nodes.get(0), latest.get(), UUID.randomUUID(), PROFILE,
                cache, from -> (units, deadline) -> new TraversalValidator.NeighborBatch(graph.getOrDefault(from, List.of()),
                        TraversalValidator.ScanStatus.COMPLETE, 1), NavigationPlanner.RecordPool.bounded(1024), ignored -> true,
                from -> {
                    Vec3 snapshot = latest.get();
                    SurfaceNode destination = new SurfaceNode(snapshot, cache.version(NavigationGraphCache.TileKey.at(snapshot)));
                    return (units, deadline) -> new TraversalValidator.NeighborBatch(
                            List.of(edge(cache, from, destination, TraversalEdge.Action.WALK)), TraversalValidator.ScanStatus.COMPLETE, 1);
                });
        int firstCompletion = -1, finishedAt = -1;
        Vec3 frozen = null;
        for (int tick = 1; tick <= 4000 && !search.finished(); tick++) {
            latest.set(new Vec3(192.2 + .1 * Math.sin(tick * .37), 0, 0));
            search.retarget(latest.get()); search.advance(budget(1, tick));
            if (search.completionInProgress() && firstCompletion < 0) {
                firstCompletion = tick; frozen = search.target();
            }
            if (search.finished()) finishedAt = tick;
        }
        require(search.result().status() == PlanningResult.Status.READY && firstCompletion > 0
                        && finishedAt - firstCompletion >= 128,
                "continuous target micro-motion cannot starve a long route's deliberately multi-tick reconstruction");
        RoutePlan plan = search.result().plan();
        require(plan.edges().size() >= 192 && plan.targetSnapshot().equals(frozen)
                        && plan.edges().get(plan.edges().size()-1).to().feet().equals(frozen),
                "READY must identify the finite proven target snapshot, not falsely relabel its route with the latest player coordinate");
        require(search.recordCount() == 0 && cache.stats().transientEntries() > 0,
                "completion releases the moving-target frontier while retaining its route payload");
        search.close(); require(cache.stats().transientEntries() == 0, "closing the moving-target route releases capacity");
    }
    private static void readyRoutesKeepSharedCapacityUntilEveryOwnerReleases() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L,
                100, 100, 100, 100, 16, 16, 32, 640, 4096, 128, 32);
        var shared = new NavigationGraphCache.TransientCapacity(640);
        NavigationGraphCache cache = new NavigationGraphCache(tuning, shared);
        List<SurfaceNode> nodes = new ArrayList<>();
        Map<SurfaceNode, List<TraversalEdge>> graph = new HashMap<>();
        for (int x = 0; x <= 24; x++) nodes.add(node(cache, x, 0));
        for (int x = 0; x < 24; x++) graph.put(nodes.get(x),
                List.of(edge(cache, nodes.get(x), nodes.get(x+1), TraversalEdge.Action.WALK)));
        NavigationPlanner.Search first = search(cache, nodes.get(0), nodes.get(24), graph, 128);
        run(first, 16, 300);
        require(first.result().status() == PlanningResult.Status.READY, "first retained route is ready");
        int oneRoute = cache.stats().transientEntries();
        require(oneRoute > 0 && shared.used() == oneRoute && first.recordCount() == 0,
                "READY route payload remains charged to the original room and server ceilings");
        RouteExecutionState execution = new RouteExecutionState();
        execution.usePlan(first.result().plan()); execution.phase = RouteExecutionState.Phase.FALL;
        require(cache.stats().transientEntries() == oneRoute, "a controller shares immutable route payload without charging twice");
        NavigationPlanner.Search second = search(cache, nodes.get(0), nodes.get(24), graph, 128);
        run(second, 16, 300);
        require(second.result().status() == PlanningResult.Status.READY && cache.stats().transientEntries() > oneRoute,
                "sequential READY results cannot make retained outputs disappear from the capacity ledger");
        NavigationPlanner.Search third = search(cache, nodes.get(0), nodes.get(24), graph, 128);
        run(third, 16, 300);
        require(third.result().status() == PlanningResult.Status.PENDING
                        && third.result().reason() == PlanningResult.Reason.RESOURCE_LIMITED,
                "more retained routes than the unchanged pool permits wait instead of exceeding capacity or declaring disconnection");
        int beforeClose = cache.stats().transientEntries();
        first.close(); first.close();
        require(first.result().plan() == null && cache.stats().transientEntries() == beforeClose
                        && execution.phase == RouteExecutionState.Phase.FALL && execution.plan != null,
                "planner invalidation may release its owner while an executing fall still retains the charged route");
        execution.clearRoute(); execution.clearRoute();
        require(cache.stats().transientEntries() == beforeClose - oneRoute,
                "landing or controller cancellation releases the final owner exactly once");
        run(third, 16, 300);
        require(third.result().status() == PlanningResult.Status.READY,
                "the same queued search resumes when an old executing route releases capacity");
        second.close(); third.close();
        require(cache.stats().transientEntries() == 0 && shared.used() == 0,
                "all route owners released returns both room and server usage to zero");
    }
    private static void evictedCompletionEvidenceRequestsFreshPlanning() {
        NavigationTuning tuning = new NavigationTuning(1_000_000L, 4_000_000L,
                10, 10, 20, 20, 2, 2, 1, 256, 1024, 16, 4);
        NavigationGraphCache cache = new NavigationGraphCache(tuning);
        SurfaceNode start = node(cache, 0, 0), end = node(cache, 1, 0);
        TraversalEdge original = edge(cache, start, end, TraversalEdge.Action.WALK);
        NavigationPlanner.Search stale = search(cache, start, end, Map.of(start, List.of(original)), 16);
        for (int tick = 1; tick <= 10 && !stale.completionInProgress(); tick++) stale.advance(budget(1, tick));
        require(stale.completionInProgress(), "fixture pauses reconstruction after a connection is actually proved");
        for (int x = 1; x <= 4; x++) cache.version(new NavigationGraphCache.TileKey(x * 10, 0, 0));
        require(!cache.valid(original), "other requests can evict a distant route version from bounded metadata");
        run(stale, 1, 40);
        require(stale.needsRestart() && !stale.finished()
                        && stale.result().status() == PlanningResult.Status.PENDING
                        && stale.result().reason() == PlanningResult.Reason.RESOURCE_LIMITED,
                "expired completion evidence requests reacquisition, never permanent invisible waiting or UNREACHABLE");
        stale.close();
        require(cache.stats().transientEntries() == 0, "restarting releases both old frontier and unpublishable route payload");
        SurfaceNode freshStart = node(cache, 0, 0), freshEnd = node(cache, 1, 0);
        require(freshStart.version() != start.version(), "reacquisition must use new geometry evidence");
        NavigationPlanner.Search fresh = search(cache, freshStart, freshEnd,
                Map.of(freshStart, List.of(edge(cache, freshStart, freshEnd, TraversalEdge.Action.WALK))), 16);
        run(fresh, 16, 40);
        require(fresh.result().status() == PlanningResult.Status.READY && !fresh.needsRestart(),
                "a newly located and validated route can complete once transient metadata pressure has passed");
        fresh.close(); require(cache.stats().transientEntries() == 0, "reacquired result also releases on cancellation");
    }
    private static void repairReusesLongSuffixAcrossBudgets() {
        for (int work : new int[]{1,64}) {
            NavigationGraphCache cache = new NavigationGraphCache(NavigationTuning.DEFAULT);
            RoutePlan old = chain(cache,160);
            TraversalEdge rejected = old.edges().get(0);
            cache.reportExecutionFailure(rejected,PROFILE,NavigationGraphCache.FailureEvidence.STATIC_COLLISION);
            RouteSuffix suffix = copiedSuffix(cache,old,0,work);
            require(suffix.size()==159,"a failed first connection retains the entire unaffected long suffix");
            SurfaceNode start=rejected.from(), detour=node(cache,0,1), rejoin=suffix.rejoin();
            Map<SurfaceNode,List<TraversalEdge>> graph=Map.of(start,List.of(rejected,edge(cache,start,detour,TraversalEdge.Action.WALK)),
                    detour,List.of(edge(cache,detour,rejoin,TraversalEdge.Action.WALK)));
            AtomicInteger suffixReads=new AtomicInteger();
            NavigationPlanner.Search repair=new NavigationPlanner.Search(start,rejoin.feet(),old.targetId(),PROFILE,cache,
                    from -> { if(from.feet().x>=1)suffixReads.incrementAndGet(); return graphScan(graph.getOrDefault(from,List.of())); },
                    NavigationPlanner.RecordPool.bounded(1024)).withSuffix(suffix,256);
            for(int tick=1;tick<5000&&!repair.finished();tick++) {
                // A moving player must not retarget the local query away from its fixed rejoin point.
                repair.retarget(old.targetSnapshot().add(Math.sin(tick)*.1,0,0));
                var budget=budget(work,tick);repair.advance(budget);
                require(budget.expansionsUsed()<=work&&budget.geometryUsed()<=work,"repair copying/refinement obey the original per-slice budget");
            }
            RoutePlan joined=repair.result().plan();
            require(joined!=null&&joined.edges().size()==161&&!joined.edges().contains(rejected)
                            &&joined.targetSnapshot().equals(old.targetSnapshot())&&suffixReads.get()==0,
                    "only a short detour is searched; >128 old edges are reused without reopening their geometry");
            require(joined.edges().get(2)==old.edges().get(1),"the result reuses immutable suffix evidence rather than copying geometry");
            require(joined.edges().get(0).from().equals(start),"the repaired route begins at the current start");
            for(int i=1;i<joined.edges().size();i++) require(joined.edges().get(i-1).to().equals(joined.edges().get(i).from()),"joined edges remain continuous");
            RouteExecutionState state=new RouteExecutionState();state.usePlan(joined);state.phase=RouteExecutionState.Phase.FALL;
            int charged=cache.stats().transientEntries();repair.close();
            require(charged>0&&cache.stats().transientEntries()==charged,"a committed executor retains the final repaired payload after planner cancellation");
            state.clearRoute();require(cache.stats().transientEntries()==0,"the last repaired-route owner releases the pool");
        }
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan old=chain(cache,160);
        RouteSuffix suffix=copiedSuffix(cache,old,0,1);
        NavigationPlanner.Search alreadyAtRejoin=search(cache,suffix.rejoin(),suffix.rejoin(),Map.of(),32).withSuffix(suffix,1);
        run(alreadyAtRejoin,1,3000);
        require(alreadyAtRejoin.result().status()==PlanningResult.Status.READY&&alreadyAtRejoin.result().plan().edges().size()==159,
                "an already reached rejoin freezes completion; a tiny optimization allowance cannot truncate long suffix stitching");
        alreadyAtRejoin.close();require(cache.stats().transientEntries()==0,"zero-prefix repair releases its suffix ownership");
    }
    private static void suffixSelectionSkipsEveryInvalidPredecessor() {
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan old=chain(cache,12);
        cache.reportExecutionFailure(old.edges().get(7),PROFILE,NavigationGraphCache.FailureEvidence.STATIC_COLLISION);
        RouteSuffix suffix=copiedSuffix(cache,old,0,1);
        require(suffix.size()==4&&suffix.rejoin().equals(old.edges().get(8).from()),"selection begins after the last invalid edge, not merely after the current edge");
        suffix.close();require(cache.stats().transientEntries()==0,"selection owns no old-plan ancestry");
        RouteSuffix excluded=new RouteSuffix(old,0,cache,edge->!edge.equals(old.edges().get(11)));
        excluded.advance(budget(64,1));
        require(excluded.status()==RouteSuffix.Status.UNUSABLE&&cache.stats().transientEntries()==0,"an excluded final edge cannot be repaired by preserving a stale suffix");
    }
    private static void impossibleRepairRequestsFullPlanning() {
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan old=chain(cache,8);
        RouteSuffix suffix=copiedSuffix(cache,old,0,1);SurfaceNode start=old.edges().get(0).from();
        SurfaceNode target=old.edges().get(7).to();
        Map<SurfaceNode,List<TraversalEdge>> graph=Map.of(start,List.of(edge(cache,start,target,TraversalEdge.Action.WALK)));
        NavigationPlanner.Search repair=search(cache,start,suffix.rejoin(),graph,32).withSuffix(suffix,256);
        run(repair,8,100);
        require(repair.needsFullPlan()&&repair.result().status()==PlanningResult.Status.PENDING
                        &&cache.stats().transientEntries()==0,"failure to reach one old rejoin point cannot declare the player unreachable");
        repair.close();
        NavigationPlanner.Search complete=search(cache,start,target,graph,32);
        run(complete,32,100);require(complete.result().status()==PlanningResult.Status.READY,"full planning can select an unrelated valid exit after local failure");
        complete.close();require(cache.stats().transientEntries()==0,"full fallback also releases memory");
    }
    private static void repairQuotaIsNotAReachabilityLimit() {
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan old=chain(cache,8);
        RouteSuffix suffix=copiedSuffix(cache,old,6,8);
        Map<SurfaceNode,List<TraversalEdge>> graph=new HashMap<>();for(var edge:old.edges())graph.put(edge.from(),List.of(edge));
        NavigationPlanner.Search local=search(cache,old.edges().get(0).from(),suffix.rejoin(),graph,64).withSuffix(suffix,1);
        local.advance(budget(1,1));
        require(local.needsFullPlan()&&local.result().status()!=PlanningResult.Status.UNREACHABLE,"the optional local-work ceiling requests a full search");
        local.close();NavigationPlanner.Search complete=search(cache,old.edges().get(0).from(),old.edges().get(7).to(),graph,64);
        run(complete,1,500);require(complete.result().status()==PlanningResult.Status.READY,"the global route has no inherited local-repair cutoff");complete.close();
    }
    private static void repairPressureReleasesItsOwnSnapshot() {
        NavigationTuning tuning=new NavigationTuning(1_000_000L,4_000_000L,100,100,100,100,16,16,32,80,4096,128,32);
        NavigationGraphCache cache=new NavigationGraphCache(tuning);RoutePlan old=chain(cache,24);
        RouteSuffix suffix=new RouteSuffix(old,0,cache,edge->true);
        for(int tick=1;tick<100&&suffix.status()==RouteSuffix.Status.COPYING;tick++)suffix.advance(budget(1,tick));
        require(suffix.status()==RouteSuffix.Status.UNUSABLE&&cache.stats().transientEntries()==0
                        &&cache.stats().peakTransientEntries()<=80,"suffix-copy pressure frees its own retained snapshot instead of waiting for itself");
        RoutePlan tiny=chain(cache,3);RouteSuffix small=copiedSuffix(cache,tiny,1,1);
        var occupied=cache.transientLease();require(occupied.reserve(occupied.available()),"fill the remaining original capacity");
        NavigationPlanner.Search repair=search(cache,tiny.edges().get(0).from(),small.rejoin(),Map.of(),16).withSuffix(small,256);
        repair.advance(budget(1,1));require(repair.needsFullPlan()&&repair.result().status()!=PlanningResult.Status.UNREACHABLE,"search memory pressure abandons only the optional repair");
        repair.close();occupied.close();require(cache.stats().transientEntries()==0,"capacity-pressure cleanup is complete");
    }
    private static void repairWaitsForChunksAndRejectsChangedEvidence() {
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan old=chain(cache,24);
        RouteSuffix suffix=copiedSuffix(cache,old,0,1);SurfaceNode start=old.edges().get(0).from();
        AtomicBoolean loaded=new AtomicBoolean();
        NavigationPlanner.Search repair=new NavigationPlanner.Search(start,suffix.rejoin().feet(),old.targetId(),PROFILE,cache,
                from->(units,deadline)->new TraversalValidator.NeighborBatch(loaded.get()?List.of(old.edges().get(0)):List.of(),
                        loaded.get()?TraversalValidator.ScanStatus.COMPLETE:TraversalValidator.ScanStatus.WAITING_CHUNK,1),
                NavigationPlanner.RecordPool.bounded(128)).withSuffix(suffix,256);
        run(repair,1,10);require(repair.result().status()==PlanningResult.Status.WAITING_CHUNK&&!repair.needsFullPlan(),"an unknown repair frontier remains resumable");
        loaded.set(true);
        for(int tick=11;tick<200&&!repair.completionInProgress();tick++)repair.advance(budget(1,tick));
        require(repair.completionInProgress(),"the chunk load allows the same local query to reach its rejoin point");
        cache.reportExecutionFailure(old.edges().get(20),PROFILE,NavigationGraphCache.FailureEvidence.STATIC_COLLISION);
        run(repair,1,1000);
        require(repair.needsFullPlan()&&repair.result().plan()==null&&cache.stats().transientEntries()==0,"new failure evidence during multi-tick stitching prevents stale suffix publication");
        repair.close();
        NavigationGraphCache changedCache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan stable=chain(changedCache,24);
        RouteSuffix changed=copiedSuffix(changedCache,stable,0,1);
        NavigationPlanner.Search stale=search(changedCache,changed.rejoin(),changed.rejoin(),Map.of(),128).withSuffix(changed,256);
        stale.advance(budget(1,1));
        changedCache.invalidate(new net.minecraft.world.phys.AABB(20,0,0,21,1,1),"repair-test-terrain-change");
        run(stale,1,1000);
        require(stale.needsFullPlan()&&stale.result().plan()==null&&changedCache.stats().transientEntries()==0,
                "a changed suffix version during stitching requests fresh full planning, never a stale READY route");
        stale.close();
    }
    private static void repeatedRepairsHaveNoReservationAncestry() {
        NavigationGraphCache cache=new NavigationGraphCache(NavigationTuning.DEFAULT);RoutePlan plan=chain(cache,24);
        NavigationPlanner.Search previous=null;int retained=-1;
        for(int round=0;round<12;round++) {
            RouteSuffix suffix=copiedSuffix(cache,plan,0,8);
            if(previous!=null) previous.close();
            SurfaceNode start=plan.edges().get(0).from(),rejoin=suffix.rejoin();
            NavigationPlanner.Search next=search(cache,start,rejoin,Map.of(start,List.of(edge(cache,start,rejoin,TraversalEdge.Action.WALK))),128).withSuffix(suffix,256);
            run(next,16,1000);require(next.result().status()==PlanningResult.Status.READY,"each sequential repair completes");
            int current=cache.stats().transientEntries();
            if(retained>=0)require(current==retained,"repaired routes cannot accumulate leases from historical plans");
            retained=current;previous=next;plan=next.result().plan();
        }
        previous.close();require(cache.stats().transientEntries()==0,"all sequential repair generations are released");
    }
    private static RouteSuffix copiedSuffix(NavigationGraphCache cache,RoutePlan plan,int first,int work) {
        RouteSuffix suffix=new RouteSuffix(plan,first,cache,edge->true);
        for(int tick=1;tick<5000&&suffix.status()==RouteSuffix.Status.COPYING;tick++) {
            var budget=budget(work,tick);suffix.advance(budget);
            require(budget.expansionsUsed()<=work,"suffix dependency checks are individually budgeted");
        }
        require(suffix.status()==RouteSuffix.Status.READY,"fixture must produce a valid independently owned suffix");return suffix;
    }
    private static void verifiedSuffixCannotOutliveEvictedVersionMetadata() {
        NavigationTuning tuning=new NavigationTuning(1_000_000L,4_000_000L,
                10,10,20,20,2,2,1,256,1024,16,4);
        NavigationGraphCache cache=new NavigationGraphCache(tuning);
        RoutePlan original=chain(cache,3);
        RouteSuffix suffix=copiedSuffix(cache,original,0,1);
        require(Boolean.TRUE.equals(suffix.verify(budget(64,1))),
                "the immutable suffix must actually finish verification before metadata pressure");
        long revision=cache.revision();
        for (int x=1;x<=4;x++) cache.version(new NavigationGraphCache.TileKey(x*10,0,0));
        require(!cache.valid(original.dependencies()),"the old evidence must really be evicted from the bounded version map");
        require(cache.revision()>revision && Boolean.FALSE.equals(suffix.verify(budget(64,2))),
                "a previously verified suffix cannot bypass dependency checks after version metadata eviction");
        suffix.close();
        require(cache.stats().transientEntries()==0,"rejecting an expired suffix releases its independently owned proof");
    }
    private static RoutePlan chain(NavigationGraphCache cache,int length) {
        List<TraversalEdge> edges=new ArrayList<>();Map<NavigationGraphCache.TileKey,Long> versions=new HashMap<>();
        SurfaceNode from=node(cache,0,0),start=from;
        for(int x=1;x<=length;x++) {SurfaceNode to=node(cache,x,0);TraversalEdge edge=edge(cache,from,to,TraversalEdge.Action.WALK);edges.add(edge);versions.putAll(edge.dependencies());from=to;}
        return new RoutePlan(UUID.randomUUID(),UUID.randomUUID(),from.feet(),PROFILE,edges,List.of(start,from),versions);
    }
    private static NavigationPlanner.Search search(NavigationGraphCache cache, SurfaceNode start, SurfaceNode end,
            Map<SurfaceNode, List<TraversalEdge>> graph, int capacity) {
        return new NavigationPlanner.Search(start, end.feet(), UUID.randomUUID(), PROFILE, cache,
                from -> graphScan(graph.getOrDefault(from,List.of())), NavigationPlanner.RecordPool.bounded(capacity));
    }
    private static NavigationPlanner.NeighborScan graphScan(List<TraversalEdge> edges) {
        return new NavigationPlanner.NeighborScan() {
            int cursor;
            @Override public TraversalValidator.NeighborBatch advance(int units,long deadline) {
                int start=cursor;cursor=Math.min(edges.size(),cursor+units);
                return new TraversalValidator.NeighborBatch(edges.subList(start,cursor),cursor==edges.size()
                        ?TraversalValidator.ScanStatus.COMPLETE:TraversalValidator.ScanStatus.PENDING,Math.max(1,cursor-start));
            }
        };
    }
    private static SurfaceNode node(NavigationGraphCache cache, double x, double y) {
        return node(cache, x, y, 0);
    }
    private static SurfaceNode node(NavigationGraphCache cache, double x, double y, double z) {
        Vec3 feet = new Vec3(x, y, z); return new SurfaceNode(feet, cache.version(NavigationGraphCache.TileKey.at(feet)));
    }
    private static TraversalEdge edge(NavigationGraphCache cache, SurfaceNode a, SurfaceNode b, TraversalEdge.Action action) {
        Map<NavigationGraphCache.TileKey, Long> versions = new HashMap<>();
        versions.put(a.tile(), cache.version(a.tile())); versions.put(b.tile(), cache.version(b.tile()));
        return new TraversalEdge(a, b, action, List.of(a.feet(), b.feet()), a.feet().distanceTo(b.feet()), versions);
    }
    private static NavigationScheduler.Budget budget(int work, long tick) {
        return new NavigationScheduler.Budget(Long.MAX_VALUE, work, work, tick);
    }
    private static void run(NavigationPlanner.Search search, int work, int ticks) {
        for (int tick = 1; tick <= ticks && !search.finished(); tick++) search.advance(budget(work, tick));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
