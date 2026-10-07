package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        portalPathsRetainFineEvidenceAndInvalidateLocally();
        fractionalFloorsAndNegativeTilesDoNotAlias();
        targetMotionKeepsOpenFrontier();
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
        System.out.println("NavigationPlannerCompatTest: 31 checks passed");
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
        search.retarget(previous.feet());
        require(search.recordCount() == count, "moving target must retain existing open/closed records");
        run(search, 1, 100);
        require(search.result().status() == PlanningResult.Status.READY && search.result().plan().edges().size() == 7,
                "refinement follows the updated endpoint in the same standing tile");
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
        Vec3 feet = new Vec3(x, y, 0); return new SurfaceNode(feet, cache.version(NavigationGraphCache.TileKey.at(feet)));
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
