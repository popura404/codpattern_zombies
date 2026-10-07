package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Resumable room planner. There is no radius or accumulated-path-length cutoff. */
public final class NavigationPlanner implements AutoCloseable {
    private final NavigationContext context;
    private final NavigationTuning tuning;
    private final NavigationGraphCache cache;
    private final TraversalValidator validator;
    private final NavigationScheduler scheduler;
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, ValidationJob> validations = new HashMap<>();
    private final Map<UUID, JumpValidationJob> jumpValidations = new HashMap<>();
    private final Map<UUID, FailureProbeJob> failureProbes = new HashMap<>();
    private final Map<UUID, LinkedHashMap<TraversalEdge, Long>> avoided = new HashMap<>();
    private int searchRecords;
    private boolean closed;
    private long requests, expansions, geometry, nanos, chunkWaits, resourceWaits;
    private long repairAttempts, repairSucceeded, repairFallbacks, reusedSuffixEdges;
    public record RepairStats(long attempts, long succeeded, long fallbacks, long reusedEdges) { }
    public RepairStats repairStats() { return new RepairStats(repairAttempts, repairSucceeded, repairFallbacks, reusedSuffixEdges); }
    public record Stats(long requests, long expansions, long geometry, long nanos, long cacheHits,
            long chunkWaits, long resourceWaits, int activeRequests, int searchRecords) { }
    public Stats stats() { return new Stats(requests, expansions, geometry, nanos, cache.cacheHits(),
            chunkWaits, resourceWaits, jobs.size(), searchRecords); }

    public NavigationPlanner(NavigationContext context) { this(context, NavigationTuning.DEFAULT); }
    public NavigationPlanner(NavigationContext context, NavigationTuning tuning) {
        this(context, tuning, null);
    }
    NavigationPlanner(NavigationContext context, NavigationTuning tuning, Runnable shutdown) {
        this.context = context; this.tuning = tuning;
        scheduler = NavigationScheduler.forServer(context.level().getServer());
        cache = new NavigationGraphCache(tuning, scheduler.transientCapacity()); validator = new TraversalValidator(context, cache);
        scheduler.register(context.lifecycleId(), context.level(), this::invalidate, this::prune,
                shutdown == null ? this::close : shutdown);
    }
    public NavigationGraphCache cache() { return cache; }
    public TraversalValidator validator() { return validator; }
    public int requestCount() { return jobs.size(); }
    public int searchRecordCount() { return searchRecords; }
    public String describe(UUID mobId) {
        Job job = jobs.get(mobId);
        if (job == null) return "request=none";
        String destination = "targetId=" + job.targetId + ", target=" + job.target + ", repair=" + (job.repair != null)
                + ", repairStats=" + repairStats();
        if (job.search == null) return "request=locate, start=" + job.startNode + ", " + destination;
        Search search = job.search;
        PendingDescription pending = search.pendingDescription();
        return destination + ", snapshot=" + search.target + ", completionFrozen=" + search.goalFrozen
                + ", records=" + search.records.size() + ", frontier=" + search.frontier.size()
                + ", scans=" + search.pending.size() + ", deferred=" + search.deferred.size()
                + ", scan=" + pending + ", refining=" + (search.reconstructionCursor != null);
    }
    private record PendingDescription(Vec3 feet, TraversalValidator.ScanStatus status, boolean terminal) { }
    public TraversalValidator.Verdict requestValidation(Mob mob, TraversalEdge edge, MovementProfile profile) {
        if (closed || !context.owns(mob)) return TraversalValidator.Verdict.UNKNOWN;
        cancelJumpValidation(mob.getUUID());
        ValidationJob existing = validations.get(mob.getUUID());
        if (existing != null && !existing.arrival && existing.edge.equals(edge) && existing.profile.equals(profile)
                && !(existing.done && existing.verdict == TraversalValidator.Verdict.UNKNOWN))
            return existing.currentVerdict();
        cancelValidation(mob.getUUID());
        ValidationJob job = new ValidationJob(mob, edge, profile);
        validations.put(mob.getUUID(), job); scheduler.submit(context.lifecycleId(), job.queueId, job);
        return TraversalValidator.Verdict.UNKNOWN;
    }
    public TraversalValidator.Verdict requestArrivalValidation(Mob mob, SurfaceNode node,
            MovementProfile profile, double tolerance) {
        if (closed || !context.owns(mob)) return TraversalValidator.Verdict.UNKNOWN;
        cancelJumpValidation(mob.getUUID());
        ValidationJob existing = validations.get(mob.getUUID());
        if (existing != null && existing.arrival && existing.edge.to().equals(node) && existing.profile.equals(profile)
                && existing.arrivalTolerance == tolerance && validator.arrivalSnapshotApplies(existing.cursor)
                && !(existing.done && existing.verdict == TraversalValidator.Verdict.UNKNOWN)) return existing.currentVerdict();
        cancelValidation(mob.getUUID());
        ValidationJob job = new ValidationJob(mob, node, profile, tolerance);
        validations.put(mob.getUUID(), job); scheduler.submit(context.lifecycleId(), job.queueId, job);
        return TraversalValidator.Verdict.UNKNOWN;
    }
    public TraversalValidator.Verdict validationResult(UUID mobId) {
        ValidationJob job = validations.get(mobId);
        return job == null ? TraversalValidator.Verdict.UNKNOWN : job.currentVerdict();
    }
    public TraversalValidator.Verdict requestJumpValidation(Mob mob, TraversalEdge edge,
            MovementProfile profile, DropJump.Plan plan) {
        if (closed || !context.owns(mob)) return TraversalValidator.Verdict.UNKNOWN;
        JumpValidationJob existing = jumpValidations.get(mob.getUUID());
        if (existing != null && existing.edge.equals(edge) && existing.profile.equals(profile)
                && existing.plan.equals(plan) && validator.jumpSnapshotApplies(existing.cursor)
                && !(existing.done && existing.verdict == TraversalValidator.Verdict.UNKNOWN))
            return existing.currentVerdict();
        cancelJumpValidation(mob.getUUID());
        if (!validator.jumpSnapshotApplies(mob, plan)) return TraversalValidator.Verdict.UNKNOWN;
        JumpValidationJob job = new JumpValidationJob(mob, edge, profile, plan);
        jumpValidations.put(mob.getUUID(), job);
        scheduler.submit(context.lifecycleId(), job.queueId, job);
        return TraversalValidator.Verdict.UNKNOWN;
    }
    private void cancelJumpValidation(UUID mobId) {
        JumpValidationJob job = jumpValidations.remove(mobId);
        if (job != null) { job.done = true; job.cursor.close(); scheduler.cancel(context.lifecycleId(), job.queueId); }
    }
    public void cancelValidation(UUID mobId) {
        ValidationJob job = validations.remove(mobId);
        if (job != null) { job.done = true; job.cursor.close(); scheduler.cancel(context.lifecycleId(), job.queueId); }
        cancelJumpValidation(mobId);
    }
    public void avoidEdge(UUID mobId, TraversalEdge edge, long untilGameTime) {
        LinkedHashMap<TraversalEdge, Long> blocked = avoided.computeIfAbsent(mobId, ignored -> new LinkedHashMap<>());
        blocked.put(edge, untilGameTime);
        while (blocked.size() > 256) blocked.remove(blocked.keySet().iterator().next());
        Job job = jobs.get(mobId);
        if (job != null) { job.restart(); scheduler.submit(context.lifecycleId(), mobId, job); }
    }

    public PlanningResult requestPlan(Mob mob, Vec3 target, UUID targetId, MovementProfile profile) {
        if (closed) throw new IllegalStateException("Navigation planner is closed");
        if (!context.owns(mob) || !mob.isAlive() || !context.contains(mob.position()) || !context.contains(target))
            return PlanningResult.pending(PlanningResult.Reason.BUILD);
        Job previous = jobs.get(mob.getUUID());
        if (previous != null && previous.targetId.equals(targetId) && previous.profile.equals(profile)
                && previous.repair != null && (sameRepairTarget(previous.repair.target(), target)
                        || previous.search != null && previous.search.completionInProgress())) {
            previous.target = target; previous.activate(); return previous.result();
        }
        if (previous != null && previous.targetId.equals(targetId) && previous.profile.equals(profile) && previous.repair != null) {
            previous.fallbackRepair(); previous.target = target; previous.activate(); return previous.result();
        }
        if (previous != null && previous.targetId.equals(targetId) && previous.profile.equals(profile)
                && previous.search != null && previous.search.goalFrozen && !previous.search.finished()) {
            previous.retarget(target); previous.activate(); return previous.result();
        }
        if (previous != null && previous.targetId.equals(targetId) && previous.profile.equals(profile)
                && previous.result().status() != PlanningResult.Status.READY
                && previous.result().status() != PlanningResult.Status.UNREACHABLE
                && NavigationGraphCache.TileKey.at(previous.target).equals(NavigationGraphCache.TileKey.at(target))
                && Math.abs(previous.target.y - target.y) <= .25) {
            previous.retarget(target); previous.activate(); return previous.result();
        }
        if (previous != null && previous.targetId.equals(targetId) && previous.profile.equals(profile)
                && previous.target.distanceToSqr(target) <= .75 * .75
                && Math.abs(previous.target.y - target.y) <= .25
                && (previous.result().plan() == null || cache.valid(previous.result().plan().dependencies())))
            { previous.activate(); return previous.result(); }
        cancelRequest(mob.getUUID());
        requests++;
        Job job = new Job(mob, target, targetId, profile);
        jobs.put(mob.getUUID(), job); scheduler.submit(context.lifecycleId(), mob.getUUID(), job);
        return job.result();
    }
    private static boolean sameRepairTarget(Vec3 snapshot, Vec3 current) {
        return snapshot.distanceToSqr(current) < 4 && Math.abs(snapshot.y - current.y) <= .25
                && NavigationGraphCache.TileKey.at(snapshot).equals(NavigationGraphCache.TileKey.at(current));
    }
    private boolean allowed(UUID mobId, TraversalEdge edge) {
        Map<TraversalEdge, Long> exclusions = avoided.get(mobId);
        return exclusions == null || exclusions.getOrDefault(edge, 0L) <= context.level().getGameTime();
    }
    /** Retain before the controller clears its owner; work starts only at its next requestPlan call. */
    public boolean prepareRepair(Mob mob, RoutePlan oldPlan, int edgeIndex, UUID targetId,
            Vec3 target, MovementProfile profile) {
        if (closed || oldPlan == null || !mob.onGround() || !context.owns(mob) || !mob.isAlive()
                || !oldPlan.targetId().equals(targetId) || !oldPlan.profile().equals(profile)
                || !sameRepairTarget(oldPlan.targetSnapshot(), target) || edgeIndex + 1 >= oldPlan.edges().size()) return false;
        RouteSuffix suffix = new RouteSuffix(oldPlan, edgeIndex, cache, edge -> allowed(mob.getUUID(), edge));
        if (suffix.status() == RouteSuffix.Status.UNUSABLE) return false;
        cancelRequest(mob.getUUID());
        Job job = new Job(mob, target, targetId, profile);
        job.repair = suffix; job.activated = false;
        jobs.put(mob.getUUID(), job); requests++; repairAttempts++;
        return true;
    }
    public PlanningResult result(UUID mobId) {
        Job job = jobs.get(mobId);
        return job == null ? PlanningResult.pending(PlanningResult.Reason.BUILD) : job.result();
    }
    public void cancelMob(UUID mobId) {
        cancelRequest(mobId); avoided.remove(mobId);
        FailureProbeJob probe = failureProbes.remove(mobId);
        if (probe != null) { probe.close(); scheduler.cancel(context.lifecycleId(), probe.queueId); }
    }
    public void cancelRequest(UUID mobId) {
        Job job = jobs.remove(mobId);
        if (job != null) job.release();
        cache.unpin(mobId); scheduler.cancel(context.lifecycleId(), mobId);
        cancelValidation(mobId);
    }
    public void invalidate(AABB bounds, String reason) {
        if (!context.bounds().inflate(1).intersects(bounds)) return;
        if (!cache.invalidate(bounds, reason)) return;
        // Completed validation proofs recheck their dependencies through the same shared
        // scheduler; an invalidation callback never synchronously walks every deep shaft.
        for (ValidationJob validation : List.copyOf(validations.values())) validation.currentVerdict();
        for (JumpValidationJob validation : List.copyOf(jumpValidations.values())) validation.currentVerdict();
        for (Job job : jobs.values()) {
            // Retain completed unaffected routes; open searches may have seen a changed frontier.
            RoutePlan route = job.result().plan();
            if (route == null && (job.conservativeDependencies || job.readTiles.stream().anyMatch(cache.lastInvalidatedTiles()::contains))
                    || route != null && !cache.valid(route.dependencies())) {
                if (route != null) job.retireReady();
                else { job.restart(); if (job.activated) job.activate(); }
            }
        }
    }
    public void reportExecutionFailure(TraversalEdge edge, MovementProfile profile,
            NavigationGraphCache.FailureEvidence evidence) {
        cache.reportExecutionFailure(edge, profile, evidence);
        if (evidence != NavigationGraphCache.FailureEvidence.STATIC_COLLISION) return;
        for (ValidationJob validation : validations.values()) {
            if (!validation.arrival && validation.edge.equals(edge) && validation.profile.equals(profile))
                cache.setFailureBaseline(edge, profile, validator.validationEvidence(validation.cursor));
        }
        for (Job job : jobs.values()) {
            if (job.profile.equals(profile)) { ensureFailureProbe(job.mob, profile); break; }
        }
        for (Job job : jobs.values()) {
            if (!job.profile.equals(profile)) continue;
            RoutePlan plan = job.result().plan();
            if (plan == null || plan.edges().contains(edge)) {
                if (plan != null) job.retireReady();
                else { job.restart(); if (job.activated) job.activate(); }
            }
        }
    }
    public void prune() {
        long tick = context.level().getGameTime();
        cache.updateTime(tick);
        for (Job job : List.copyOf(jobs.values())) {
            if (!job.mob.isAlive() || job.mob.isRemoved() || !context.owns(job.mob)) cancelMob(job.mob.getUUID());
            else if (job.result().status() == PlanningResult.Status.UNREACHABLE && tick - job.finishedTick >= 40) {
                // Revisit expired adjacency so an eventless door opening can be discovered.
                job.restart(); scheduler.submit(context.lifecycleId(), job.mob.getUUID(), job);
            }
        }
        for (ValidationJob job : List.copyOf(validations.values()))
            if (!job.mob.isAlive() || job.mob.isRemoved() || !context.owns(job.mob)) cancelValidation(job.mob.getUUID());
        for (JumpValidationJob job : List.copyOf(jumpValidations.values()))
            if (!job.mob.isAlive() || job.mob.isRemoved() || !context.owns(job.mob)) cancelJumpValidation(job.mob.getUUID());
        for (FailureProbeJob probe : List.copyOf(failureProbes.values())) {
            Job job = jobs.get(probe.mob.getUUID());
            if (!probe.mob.isAlive() || probe.mob.isRemoved() || !context.owns(probe.mob)
                    || job != null && !job.profile.equals(probe.profile)) {
                probe.close(); scheduler.cancel(context.lifecycleId(), probe.queueId); failureProbes.remove(probe.mob.getUUID());
            }
        }
        avoided.values().forEach(map -> map.values().removeIf(expiry -> expiry <= tick));
        avoided.values().removeIf(Map::isEmpty);
    }
    public void reset() {
        for (UUID id : List.copyOf(jobs.keySet())) cancelMob(id);
        for (UUID id : List.copyOf(validations.keySet())) cancelValidation(id);
        for (UUID id : List.copyOf(jumpValidations.keySet())) cancelJumpValidation(id);
        for (FailureProbeJob probe : failureProbes.values()) { probe.close(); scheduler.cancel(context.lifecycleId(), probe.queueId); }
        failureProbes.clear();
        avoided.clear();
        cache.reset(); searchRecords = 0;
    }
    @Override public void close() {
        if (closed) return;
        reset(); scheduler.unregister(context.lifecycleId()); closed = true;
    }

    private final class Job implements NavigationScheduler.Work {
        final Mob mob; Vec3 target; final UUID targetId; final MovementProfile profile;
        Search search;
        RouteSuffix repair;
        boolean activated = true;
        int repairEdges;
        PlanningResult initial = PlanningResult.pending(PlanningResult.Reason.BUILD);
        boolean cancelled;
        long nextLocateTick;
        long finishedTick;
        TraversalValidator.LocateCursor startCursor, destinationCursor;
        SurfaceNode startNode;
        final Set<NavigationGraphCache.TileKey> readTiles = new HashSet<>();
        final NavigationGraphCache.TransientLease dependencyMemory = cache.transientLease();
        boolean conservativeDependencies;
        void observeTile(NavigationGraphCache.TileKey tile) {
            if (conservativeDependencies || readTiles.contains(tile)) return;
            if (dependencyMemory.reserve(1)) readTiles.add(tile);
            else { conservativeDependencies = true; dependencyMemory.release(readTiles.size()); readTiles.clear(); }
        }
        Job(Mob mob, Vec3 target, UUID targetId, MovementProfile profile) {
            this.mob = mob; this.target = target; this.targetId = targetId; this.profile = profile;
        }
        void activate() { activated = true; scheduler.submit(context.lifecycleId(), mob.getUUID(), this); }
        void retireReady() {
            restart(); activated = false;
            scheduler.cancel(context.lifecycleId(), mob.getUUID());
        }
        PlanningResult result() { return search == null ? initial : search.result(); }
        void retarget(Vec3 newTarget) {
            target = newTarget;
            // Keep an in-flight locate cursor; restarting it each moving-player tick starves acquisition.
            // Its standing destination may be in a different tile from the requested target,
            // so the search itself must decide whether its original frontier can be reused.
            if (search != null && repair == null && !search.retarget(newTarget)) restart();
        }
        @Override public boolean finished() { return cancelled || !activated || search != null && search.finished(); }
        @Override public void advance(NavigationScheduler.Budget budget) {
            long before = System.nanoTime(); int e = budget.expansionsUsed(), g = budget.geometryUsed();
            var previousObserver = cache.observeReads(this::observeTile);
            try { advanceWork(budget); } finally {
                cache.observeReads(previousObserver);
                nanos += System.nanoTime() - before;
                expansions += budget.expansionsUsed() - e; geometry += budget.geometryUsed() - g;
                if (result().status() == PlanningResult.Status.WAITING_CHUNK) chunkWaits++;
                if (result().reason() == PlanningResult.Reason.RESOURCE_LIMITED) resourceWaits++;
            }
        }
        private void advanceWork(NavigationScheduler.Budget budget) {
            if (cancelled || !mob.isAlive() || !context.owns(mob)) { cancelMob(mob.getUUID()); return; }
            if (!activated) return;
            if (repair != null && repair.status() == RouteSuffix.Status.COPYING) {
                repair.advance(budget);
                if (repair.status() == RouteSuffix.Status.COPYING) return;
            }
            if (repair != null && repair.status() == RouteSuffix.Status.UNUSABLE) { fallbackRepair(); return; }
            if (search == null) {
                if (budget.serverTick() < nextLocateTick || budget.geometryRemaining() == 0) return;
                if (!context.level().hasChunkAt(BlockPos.containing(mob.position()))
                        || !context.level().hasChunkAt(BlockPos.containing(target))) {
                    initial = PlanningResult.waitingChunk(); nextLocateTick = budget.serverTick() + 10; return;
                }
                if (startNode == null) {
                    if (startCursor == null) startCursor = validator.beginLocate(mob, mob.position(), profile);
                    long unitStarted = System.nanoTime();
                    TraversalValidator.LocateBatch located = validator.advanceLocate(startCursor,
                            budget.geometryRemaining(), budget.deadlineNanos());
                    budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, unitStarted);
                    budget.accountGeometry(located.workUsed());
                    if (located.status() != TraversalValidator.ScanStatus.COMPLETE) {
                        initial = waiting(located.status()); return;
                    }
                    startNode = located.node(); startCursor.close(); startCursor = null;
                    if (startNode == null) { startCursor = null; nextLocateTick = budget.serverTick() + 10; return; }
                }
                SurfaceNode destination;
                if (repair != null) destination = repair.rejoin();
                else {
                    if (budget.geometryRemaining() == 0) return;
                    if (destinationCursor == null) destinationCursor = validator.beginLocate(mob, target, profile);
                    long unitStarted = System.nanoTime();
                    TraversalValidator.LocateBatch located = validator.advanceLocate(destinationCursor,
                            budget.geometryRemaining(), budget.deadlineNanos());
                    budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, unitStarted);
                    budget.accountGeometry(located.workUsed());
                    if (located.status() != TraversalValidator.ScanStatus.COMPLETE) {
                        initial = waiting(located.status()); return;
                    }
                    destination = located.node(); destinationCursor.close(); destinationCursor = null;
                }
                if (destination == null) {
                    // A moving/airborne target, missing neighbor chunk or inconclusive support is not unreachable.
                    initial = PlanningResult.pending(PlanningResult.Reason.BUILD);
                    destinationCursor = null; nextLocateTick = budget.serverTick() + 10; return;
                }
                search = new Search(startNode, destination.feet(), targetId, profile, cache, node -> {
                    TraversalValidator.NeighborCursor cursor = validator.beginNeighbours(mob, node, profile, node.version());
                    return new NeighborScan() {
                        @Override public TraversalValidator.NeighborBatch advance(int units, long deadline) {
                            return validator.advance(cursor, units, deadline);
                        }
                        @Override public void close() { cursor.close(); }
                        @Override public boolean transfersMemory() { return true; }
                        @Override public boolean transferEdge(TraversalEdge edge, NavigationGraphCache.TransientLease destination) {
                            return cursor.transferEdge(edge, destination);
                        }
                    };
                }, new RecordPool() {
                    @Override public boolean acquire() {
                        if (searchRecords >= tuning.searchRecords()) return false;
                        searchRecords++; return true;
                    }
                    @Override public void release(int count) { searchRecords -= count; }
                }, edge -> allowed(mob.getUUID(), edge), node -> {
                    TraversalValidator.ConnectionCursor cursor = validator.beginConnection(mob, node, search.target(), profile);
                    return new NeighborScan() {
                        @Override public TraversalValidator.NeighborBatch advance(int units, long deadline) {
                            return validator.advanceConnection(cursor, units, deadline);
                        }
                        @Override public void close() { cursor.close(); }
                        @Override public boolean transfersMemory() { return true; }
                        @Override public boolean transferEdge(TraversalEdge edge, NavigationGraphCache.TransientLease destination) {
                            return cursor.transferEdge(edge, destination);
                        }
                    };
                });
                if (repair != null) {
                    repairEdges = repair.size(); search.withSuffix(repair, tuning.sliceExpansions() * 4);
                } else if (NavigationGraphCache.TileKey.at(destination.feet()).equals(NavigationGraphCache.TileKey.at(target))
                        && Math.abs(destination.feet().y - target.y) <= .25) search.retarget(target);
            }
            search.advance(budget);
            if (search.needsFullPlan()) { fallbackRepair(); return; }
            if (search.needsRestart()) {
                // A monotonically replaced/evicted version cannot become the old version
                // again. Reacquire standing nodes and geometry instead of waiting forever
                // on a stale completion snapshot, or treating it as disconnected terrain.
                restart(); initial = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
            }
            if (search.finished()) {
                finishedTick = context.level().getGameTime();
                if (repair != null && search.result().status() == PlanningResult.Status.READY) {
                    repairSucceeded++; reusedSuffixEdges += repairEdges; repair = null;
                }
            }
        }
        void fallbackRepair() { repairFallbacks++; restart(); }
        void release() {
            cancelled = true; closeCursors(); dependencyMemory.close(); if (search != null) search.close();
            if (repair != null) { repair.close(); repair = null; }
        }
        void closeCursors() {
            if (startCursor != null) startCursor.close();
            if (destinationCursor != null) destinationCursor.close();
            startCursor = null; destinationCursor = null;
        }
        void restart() {
            closeCursors(); dependencyMemory.release(readTiles.size()); readTiles.clear(); conservativeDependencies = false;
            if (search != null) search.close();
            if (repair != null) { repair.close(); repair = null; }
            search = null; initial = PlanningResult.pending(PlanningResult.Reason.BUILD); nextLocateTick = 0;
            startCursor = null; destinationCursor = null; startNode = null;
        }
    }
    private static PlanningResult waiting(TraversalValidator.ScanStatus status) {
        return status == TraversalValidator.ScanStatus.WAITING_CHUNK ? PlanningResult.waitingChunk()
                : PlanningResult.pending(status == TraversalValidator.ScanStatus.RESOURCE_LIMITED
                        ? PlanningResult.Reason.RESOURCE_LIMITED : PlanningResult.Reason.BUILD);
    }
    private final class ValidationJob implements NavigationScheduler.Work {
        final UUID queueId = UUID.randomUUID();
        final Mob mob;
        final TraversalEdge edge;
        final MovementProfile profile;
        final TraversalValidator.ValidationCursor cursor;
        final boolean arrival;
        final double arrivalTolerance;
        TraversalValidator.Verdict verdict = TraversalValidator.Verdict.UNKNOWN;
        boolean done;
        long retryTick;
        long completedRevision=Long.MIN_VALUE;
        ValidationJob(Mob mob, TraversalEdge edge, MovementProfile profile) {
            this.mob = mob; this.edge = edge; this.profile = profile;
            arrival = false; arrivalTolerance = 0;
            cursor = validator.beginValidation(mob, edge, profile);
        }
        ValidationJob(Mob mob, SurfaceNode node, MovementProfile profile, double tolerance) {
            this.mob = mob; this.profile = profile; arrival = true; arrivalTolerance = tolerance;
            edge = new TraversalEdge(node, node, TraversalEdge.Action.WALK, List.of(node.feet()), 0,
                    Map.of(node.tile(), node.version()));
            cursor = validator.beginStanding(mob, node, profile, tolerance);
        }
        @Override public boolean finished() { return done; }
        TraversalValidator.Verdict currentVerdict() {
            if (done && arrival && !validator.arrivalSnapshotApplies(cursor)) {
                cursor.close();verdict=TraversalValidator.Verdict.UNKNOWN;return verdict;
            }
            if (done && completedRevision!=cache.revision()
                    && verdict!=TraversalValidator.Verdict.CLEAR && verdict!=TraversalValidator.Verdict.NATIVE_INTERACTION) {
                // Negative proofs already released their scans; fresh evidence is required.
                verdict=TraversalValidator.Verdict.UNKNOWN;return verdict;
            }
            if (done && validator.recheckValidationEvidence(cursor)) {
                done=false;verdict=TraversalValidator.Verdict.UNKNOWN;
                scheduler.submit(context.lifecycleId(),queueId,this);
            }
            return verdict;
        }
        @Override public void advance(NavigationScheduler.Budget budget) {
            if (budget.serverTick() < retryTick || budget.geometryRemaining() == 0) return;
            long before = System.nanoTime();
            TraversalValidator.ValidationBatch batch = validator.advanceValidation(cursor,
                    budget.geometryRemaining(), budget.deadlineNanos());
            budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, before);
            nanos += System.nanoTime() - before; geometry += batch.workUsed();
            budget.accountGeometry(batch.workUsed()); verdict = batch.verdict();
            done = batch.status() == TraversalValidator.ScanStatus.COMPLETE;
            if (done) {
                completedRevision=cache.revision();validator.releaseValidationGeometry(cursor);
            }
            if (batch.status() == TraversalValidator.ScanStatus.WAITING_CHUNK) retryTick = budget.serverTick() + 10;
        }
    }
    /** Optional forward jumps consume the same geometry queue as ordinary DROP evidence. */
    private final class JumpValidationJob implements NavigationScheduler.Work {
        final UUID queueId = UUID.randomUUID();
        final Mob mob;
        final TraversalEdge edge;
        final MovementProfile profile;
        final DropJump.Plan plan;
        final TraversalValidator.JumpCursor cursor;
        TraversalValidator.Verdict verdict = TraversalValidator.Verdict.UNKNOWN;
        boolean done;
        long retryTick, completedRevision = Long.MIN_VALUE;
        JumpValidationJob(Mob mob, TraversalEdge edge, MovementProfile profile, DropJump.Plan plan) {
            this.mob = mob; this.edge = edge; this.profile = profile; this.plan = plan;
            cursor = validator.beginJumpValidation(mob, edge, profile, plan);
        }
        @Override public boolean finished() { return done; }
        TraversalValidator.Verdict currentVerdict() {
            if (!validator.jumpSnapshotApplies(cursor)) return TraversalValidator.Verdict.UNKNOWN;
            if (done && completedRevision != cache.revision() && verdict != TraversalValidator.Verdict.CLEAR) {
                verdict = TraversalValidator.Verdict.UNKNOWN; return verdict;
            }
            if (done && validator.recheckJumpValidationEvidence(cursor)) {
                done = false; verdict = TraversalValidator.Verdict.UNKNOWN;
                scheduler.submit(context.lifecycleId(), queueId, this);
            }
            return verdict;
        }
        @Override public void advance(NavigationScheduler.Budget budget) {
            if (budget.serverTick() < retryTick || budget.geometryRemaining() == 0) return;
            long before = System.nanoTime();
            TraversalValidator.ValidationBatch batch = validator.advanceJumpValidation(cursor,
                    budget.geometryRemaining(), budget.deadlineNanos());
            budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, before);
            nanos += System.nanoTime() - before; geometry += batch.workUsed();
            budget.accountGeometry(batch.workUsed()); verdict = batch.verdict();
            done = batch.status() == TraversalValidator.ScanStatus.COMPLETE;
            if (done) { completedRevision = cache.revision(); validator.releaseJumpValidationGeometry(cursor); }
            if (batch.status() == TraversalValidator.ScanStatus.WAITING_CHUNK) retryTick = budget.serverTick() + 10;
        }
    }
    private void ensureFailureProbe(Mob mob, MovementProfile profile) {
        FailureProbeJob previous = failureProbes.get(mob.getUUID());
        if (previous != null && previous.profile.equals(profile)) return;
        if (previous != null) { previous.close(); scheduler.cancel(context.lifecycleId(), previous.queueId); }
        FailureProbeJob job = new FailureProbeJob(mob, profile);
        failureProbes.put(mob.getUUID(), job); scheduler.submit(context.lifecycleId(), job.queueId, job);
    }
    private final class FailureProbeJob implements NavigationScheduler.Work {
        final UUID queueId = UUID.randomUUID();
        final Mob mob;
        final MovementProfile profile;
        NavigationGraphCache.FailedEdge failure;
        TraversalValidator.ProbeCursor cursor;
        boolean done;
        FailureProbeJob(Mob mob, MovementProfile profile) { this.mob = mob; this.profile = profile; }
        @Override public boolean finished() { return done; }
        void close() { done = true; if (cursor != null) cursor.close(); cursor = null; failure = null; }
        @Override public void advance(NavigationScheduler.Budget budget) {
            if (budget.geometryRemaining() == 0) return;
            long tick = context.level().getGameTime();
            if (failure == null) {
                failure = cache.nextFailureToProbe(profile, tick);
                if (failure == null) {
                    if (!cache.hasFailures(profile)) { done = true; failureProbes.remove(mob.getUUID()); }
                    return;
                }
                TraversalValidator.ShapeEvidence evidence = cache.failureEvidence(failure);
                cursor = evidence == null ? validator.beginProbe(mob, failure.edge(), profile) : validator.beginProbe(mob, evidence);
            }
            long before = System.nanoTime();
            TraversalValidator.ProbeBatch batch = validator.advanceProbe(cursor, budget.geometryRemaining(), budget.deadlineNanos());
            budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, before);
            nanos += System.nanoTime() - before; geometry += batch.workUsed(); budget.accountGeometry(batch.workUsed());
            if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
            NavigationGraphCache.FailedEdge checked = failure; failure = null; cursor.close(); cursor = null;
            if (cache.observeFailureShape(checked, batch.fingerprint(), tick)) {
                AABB area = TraversalValidator.bodyAt(profile, checked.edge().from().feet())
                        .minmax(TraversalValidator.bodyAt(profile, checked.edge().to().feet()));
                for (Vec3 control : checked.edge().controlPoints()) area = area.minmax(TraversalValidator.bodyAt(profile, control));
                invalidate(area, "observed collision-shape change in failed edge");
            }
        }
    }

    @FunctionalInterface public interface NeighborSource { NeighborScan open(SurfaceNode node); }
    @FunctionalInterface public interface NeighborScan extends AutoCloseable {
        TraversalValidator.NeighborBatch advance(int geometryUnits, long deadlineNanos);
        default boolean transfersMemory() { return false; }
        default boolean transferEdge(TraversalEdge edge, NavigationGraphCache.TransientLease destination) { return false; }
        /** Unleased pure-graph sources declare a conservative retained-output bound per work unit. */
        default int maxUnleasedEdgeEntries() { return 64; }
        @Override default void close() { }
    }
    public interface RecordPool {
        boolean acquire();
        void release(int count);
        static RecordPool bounded(int limit) {
            return new RecordPool() {
                int used;
                @Override public boolean acquire() { if (used >= limit) return false; used++; return true; }
                @Override public void release(int count) { used -= count; }
            };
        }
    }

    /** World-independent search core: tests can provide delayed or unloaded adjacency explicitly. */
    public static final class Search implements NavigationScheduler.Work, AutoCloseable {
        private record QueueEntry(SurfaceNode node, double distance, double score, long order) { }
        private static final class Record {
            double distance = Double.POSITIVE_INFINITY;
            List<TraversalEdge> predecessor;
            boolean closed;
        }
        private static final class Pending {
            final SurfaceNode node;
            final NeighborScan scan;
            final boolean terminal;
            Vec3 terminalTarget;
            final List<TraversalEdge> edges = new ArrayList<>();
            final Set<TraversalEdge> seen = new HashSet<>();
            long retryTick;
            TraversalValidator.ScanStatus status = TraversalValidator.ScanStatus.PENDING;
            Pending(SurfaceNode node, NeighborScan scan) { this(node, scan, false); }
            Pending(SurfaceNode node, NeighborScan scan, boolean terminal) { this.node = node; this.scan = scan; this.terminal = terminal; }
        }
        private final SurfaceNode start;
        private Vec3 target;
        private Vec3 latestTarget;
        private final Vec3 heuristicAnchor;
        private final UUID targetId;
        private final MovementProfile profile;
        private final NavigationGraphCache cache;
        private final NeighborSource source;
        private final NeighborSource goalSource;
        private final RecordPool pool;
        private final Predicate<TraversalEdge> allowed;
        private final NavigationGraphCache.TransientLease memory;
        private final NavigationGraphCache.TransientLease routeMemory;
        private RoutePlan.Retention routeRetention;
        private boolean routePublished, searchStateReleased, routeListReserved;
        private int routeTransferIndex;
        private final Set<TraversalEdge> transferredRouteEdges = new HashSet<>();
        private final Map<TraversalEdge, Integer> retainedEdges = new HashMap<>();
        private final Map<SurfaceNode, Record> records = new HashMap<>();
        private final Map<NavigationGraphCache.TileKey, List<SurfaceNode>> recordsByTile = new HashMap<>();
        private final TreeSet<QueueEntry> frontier = new TreeSet<>(Comparator
                .comparingDouble(QueueEntry::score).thenComparingLong(QueueEntry::order));
        private final Map<SurfaceNode, QueueEntry> queued = new HashMap<>();
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private final Map<SurfaceNode, Pending> pendingByNode = new HashMap<>();
        private final Map<SurfaceNode, Vec3> goalChecked = new HashMap<>();
        private final ArrayDeque<TraversalEdge> deferred = new ArrayDeque<>();
        private final Set<TraversalEdge> temporarilyExcluded = new HashSet<>();
        private PlanningResult result = PlanningResult.pending(PlanningResult.Reason.BUILD);
        private long order;
        private boolean initialized, closed, goalFrozen, tailTurn, evidenceExpired;
        private TailRefresh tailRefresh;
        private boolean tailDirty, tailResourceLimited;
        private int ownedRecords;
        private SurfaceNode reconstructionCursor, reconstructionEnd;
        private List<TraversalEdge> reconstructionPredecessor, refinedEdges;
        private int predecessorIndex, refinementIndex;
        private final ArrayDeque<TraversalEdge> reversed = new ArrayDeque<>();
        private final Map<NavigationGraphCache.TileKey, Long> routeDependencies = new LinkedHashMap<>();
        private final List<SurfaceNode> routePortals = new ArrayList<>();
        private final List<TraversalEdge> portalSegment = new ArrayList<>();
        private RouteSuffix suffix;
        private int suffixAppended, prefixEdgeCount = -1, repairWork, repairLimit;
        private boolean fullPlanRequired;
        private Vec3 publicationTarget;
        private Iterator<Map.Entry<NavigationGraphCache.TileKey, Long>> refinementDependencies, completionDependencies;
        private Map.Entry<NavigationGraphCache.TileKey, Long> nextDependency;
        private TraversalEdge refiningEdge;
        private long completionRevision = Long.MIN_VALUE;

        public Search(SurfaceNode start, Vec3 target, UUID targetId, MovementProfile profile,
                NavigationGraphCache cache, NeighborSource source, RecordPool pool) {
            this(start, target, targetId, profile, cache, source, pool, edge -> true);
        }
        public Search(SurfaceNode start, Vec3 target, UUID targetId, MovementProfile profile,
                NavigationGraphCache cache, NeighborSource source, RecordPool pool, Predicate<TraversalEdge> allowed) {
            this(start, target, targetId, profile, cache, source, pool, allowed, null);
        }
        public Search(SurfaceNode start, Vec3 target, UUID targetId, MovementProfile profile,
                NavigationGraphCache cache, NeighborSource source, RecordPool pool, Predicate<TraversalEdge> allowed,
                NeighborSource goalSource) {
            this.start = start; this.target = target; this.targetId = targetId; this.profile = profile;
            this.latestTarget = target;
            this.heuristicAnchor = target;
            this.cache = cache; this.source = source; this.pool = pool;
            memory = cache.transientLease();
            routeMemory = cache.transientLease();
            this.allowed = allowed;
            this.goalSource = goalSource;
        }
        public PlanningResult result() { return result; }
        Search withSuffix(RouteSuffix suffix, int workLimit) {
            if (initialized || suffix.status() != RouteSuffix.Status.READY || !target.equals(suffix.rejoin().feet()))
                throw new IllegalArgumentException("Repair suffix must begin at this search's exact destination");
            this.suffix = suffix; publicationTarget = suffix.target(); repairLimit = Math.max(1, workLimit);
            return this;
        }
        public boolean needsFullPlan() { return fullPlanRequired; }
        public Vec3 target() { return target; }
        public boolean completionInProgress() { return goalFrozen && !finished(); }
        public boolean needsRestart() { return evidenceExpired; }
        private PendingDescription pendingDescription() {
            Pending first = pending.peekFirst();
            return first == null ? null : new PendingDescription(first.node.feet(), first.status, first.terminal);
        }
        /** Reuse the frontier when compatible; false tells the owner to restart from a fresh standing target. */
        public boolean retarget(Vec3 next) {
            if (finished()) return true;
            if (suffix == null && !goalFrozen && reconstructionEnd == null
                    && !NavigationGraphCache.TileKey.at(next).equals(NavigationGraphCache.TileKey.at(heuristicAnchor)))
                return false;
            latestTarget = next;
            if (suffix != null) return true; // A repair's search goal is the rejoin point, never the moving player.
            if (next.equals(target)) return true;
            // Preserve a finite completion phase. A continuously moving player cannot keep
            // canceling a proven endpoint or a long route's resumable reconstruction.
            // The resulting plan advertises this frozen snapshot; execution can refresh its tail.
            if (goalFrozen || reconstructionEnd != null) return true;
            target = next;
            tailDirty = true;
            return true;
        }
        public int recordCount() { return records.size(); }
        @Override public boolean finished() {
            return closed || result.status() == PlanningResult.Status.READY || result.status() == PlanningResult.Status.UNREACHABLE;
        }
        @Override public void advance(NavigationScheduler.Budget budget) {
            if (fullPlanRequired) return;
            int before = budget.expansionsUsed();
            advanceSearch(budget);
            if (suffix != null && !finished() && result.reason() == PlanningResult.Reason.RESOURCE_LIMITED) {
                abandonRepair(); return;
            }
            if (suffix != null && !goalFrozen && !finished()) {
                repairWork += budget.expansionsUsed() - before;
                if (repairWork >= repairLimit) abandonRepair();
            }
        }
        private void advanceSearch(NavigationScheduler.Budget budget) {
            if (finished() || evidenceExpired) return;
            if (reconstructionCursor != null) { advanceCompletion(budget); return; }
            for (TraversalEdge edge : List.copyOf(temporarilyExcluded)) {
                if (!budget.hasTime()) break;
                if (allowed.test(edge)) {
                    temporarilyExcluded.remove(edge);
                    if (!relax(edge)) defer(edge);
                    releaseEdge(edge);
                }
            }
            if (!initialized) {
                if (!acquireRecord()) { result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return; }
                ownedRecords++;
                Record record = new Record(); record.distance = 0; putRecord(start, record);
                enqueue(start, 0); initialized = true;
            }
            // With a one-expansion slice, continuously refreshed tail work must still leave
            // the main frontier a turn. Larger slices spend at most eight tail checks.
            tailTurn = !tailTurn;
            if (frontier.isEmpty() || tailTurn) advanceTailRefresh(budget);
            if (reconstructionCursor != null) { advanceCompletion(budget); return; }
            while (!deferred.isEmpty()) {
                if (!budget.hasTime()) { result = PlanningResult.pending(PlanningResult.Reason.BUDGET); return; }
                if (!relax(deferred.peekFirst())) { result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return; }
                releaseEdge(deferred.removeFirst());
            }
            int scanAttempts = pending.size();
            while (budget.hasTime()) {
                if (!frontier.isEmpty() && atTarget(frontier.first().node()) && budget.takeExpansion()) {
                    QueueEntry end = frontier.pollFirst(); queued.remove(end.node());
                    goalFrozen = true;
                    reconstructionCursor = end.node(); reconstructionEnd = end.node();
                    result = PlanningResult.pending(PlanningResult.Reason.BUILD);
                    advanceCompletion(budget); return;
                }
                if (!pending.isEmpty() && scanAttempts-- > 0 && budget.geometryRemaining() > 0) {
                    Pending expansion = pending.removeFirst();
                    if (expansion.terminal && !target.equals(expansion.terminalTarget)) {
                        if (!budget.takeExpansion()) {
                            pending.addFirst(expansion); result = PlanningResult.pending(PlanningResult.Reason.BUDGET); return;
                        }
                        discardPending(expansion); continue;
                    }
                    if (expansion.retryTick > budget.serverTick()) { pending.addLast(expansion); continue; }
                    int reservation = expansion.scan.transfersMemory() ? 0 : expansion.scan.maxUnleasedEdgeEntries();
                    int units = reservation == 0 ? budget.geometryRemaining()
                            : Math.min(budget.geometryRemaining(), memory.available() / reservation);
                    if (units == 0) {
                        pending.addFirst(expansion); result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
                    }
                    memory.reserve(units * reservation);
                    long unitStarted = System.nanoTime();
                    TraversalValidator.NeighborBatch batch = expansion.scan.advance(units, budget.deadlineNanos());
                    budget.recordUnit(NavigationScheduler.UnitKind.GEOMETRY, unitStarted);
                    if (batch.edges().size() > units) throw new IllegalStateException("Neighbor source exceeded its output budget");
                    int emittedEntries = batch.edges().stream().mapToInt(NavigationGraphCache::edgeTransientEntries).sum();
                    if (reservation != 0) {
                        if (emittedEntries > units * reservation) throw new IllegalStateException("Neighbor source exceeded its retained-output bound");
                        memory.release(units * reservation - emittedEntries);
                    }
                    budget.accountGeometry(batch.workUsed());
                    for (TraversalEdge edge : batch.edges()) {
                        if (expansion.scan.transfersMemory() && !expansion.scan.transferEdge(edge, memory))
                            throw new IllegalStateException("Neighbor source did not transfer retained edge memory");
                        if (retainedEdges.containsKey(edge)) memory.release(NavigationGraphCache.edgeTransientEntries(edge));
                        retainedEdges.merge(edge, 1, Integer::sum);
                        if (!expansion.seen.add(edge)) { releaseEdge(edge); continue; }
                        expansion.edges.add(edge);
                        if (!cache.isFailed(edge, profile) && !relax(edge)) defer(edge);
                    }
                    expansion.status = batch.status();
                    if (batch.status() == TraversalValidator.ScanStatus.COMPLETE) {
                        expansion.scan.close(); memory.release(1);
                        if (!expansion.terminal) pendingByNode.remove(expansion.node);
                        // Cache pressure does not change the scanned node's reachability evidence.
                        if (!expansion.terminal) cache.putEdges(expansion.node, profile, expansion.edges);
                        for (TraversalEdge edge : expansion.edges) releaseEdge(edge);
                    } else {
                        expansion.retryTick = budget.serverTick()
                                + (batch.status() == TraversalValidator.ScanStatus.WAITING_CHUNK ? 10
                                        : batch.status() == TraversalValidator.ScanStatus.RESOURCE_LIMITED ? 1 : 0);
                        if (batch.status() == TraversalValidator.ScanStatus.PENDING) pending.addFirst(expansion);
                        else pending.addLast(expansion);
                    }
                    if (!deferred.isEmpty()) { result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return; }
                    if (batch.status() == TraversalValidator.ScanStatus.PENDING) {
                        // Finish the best node's adjacency before opening many inferior partial scans.
                        // The scheduler may grant another fair slice in this SAME tick.
                        result = PlanningResult.pending(PlanningResult.Reason.BUILD); return;
                    }
                    continue;
                }
                if (!pending.isEmpty() && budget.geometryRemaining() == 0) break;
                if (!frontier.isEmpty() && budget.takeExpansion()) {
                    long unitStarted = System.nanoTime();
                    QueueEntry next = frontier.pollFirst(); queued.remove(next.node());
                    Record record = records.get(next.node());
                    if (record == null || record.closed || Double.compare(record.distance, next.distance()) != 0) continue;
                    record.closed = true;
                    if (atTarget(next.node())) {
                        goalFrozen = true;
                        reconstructionCursor = next.node(); reconstructionEnd = next.node();
                        result = PlanningResult.pending(PlanningResult.Reason.BUILD);
                        advanceCompletion(budget); return;
                    }
                    if (goalSource != null && next.node().feet().distanceToSqr(target) <= 1.5 * 1.5
                            && !target.equals(goalChecked.get(next.node()))) {
                        if (!memory.reserve(1)) { reopenForMemory(next, record); return; }
                        openGoalConnection(next.node()); scanAttempts++;
                    }
                    // Region-level entry/exit connections retain fine evidence for execution.
                    for (NavigationGraphCache.PortalPath path : cache.portalPaths(next.node(), profile))
                        relaxPortal(path);
                    List<TraversalEdge> cached = cache.edges(next.node(), profile);
                    if (cached != null) {
                        if (!retainEdges(cached)) { reopenForMemory(next, record); return; }
                        for (TraversalEdge edge : cached) {
                            if (!relax(edge)) defer(edge);
                            releaseEdge(edge);
                        }
                    } else {
                        if (!pendingByNode.containsKey(next.node())) {
                            if (!memory.reserve(1)) { reopenForMemory(next, record); return; }
                            Pending expansion = new Pending(next.node(), source.open(next.node()));
                            pending.addLast(expansion); pendingByNode.put(next.node(), expansion); scanAttempts++;
                        }
                    }
                    budget.recordUnit(NavigationScheduler.UnitKind.SEARCH, unitStarted);
                    if (!deferred.isEmpty()) { result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return; }
                    continue;
                }
                break;
            }
            if (frontier.isEmpty() && pending.isEmpty() && deferred.isEmpty() && temporarilyExcluded.isEmpty()
                    && tailRefresh == null && !tailDirty) {
                if (suffix != null) abandonRepair();
                else { result = PlanningResult.unreachable(); releaseRecords(); }
            } else if (frontier.isEmpty() && pending.stream().anyMatch(p -> p.status == TraversalValidator.ScanStatus.WAITING_CHUNK)) {
                result = PlanningResult.waitingChunk();
            } else if (tailResourceLimited || pending.stream().anyMatch(p -> p.status == TraversalValidator.ScanStatus.RESOURCE_LIMITED)) {
                result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED);
            } else {
                result = PlanningResult.pending(pending.isEmpty() ? PlanningResult.Reason.BUDGET : PlanningResult.Reason.BUILD);
            }
        }
        private boolean acquireRecord() {
            // The record, its tile index entry and an amortized tile bucket are retained together.
            if (!memory.reserve(3)) return false;
            if (pool.acquire()) return true;
            memory.release(3); return false;
        }
        private void putRecord(SurfaceNode node, Record record) {
            records.put(node, record);
            recordsByTile.computeIfAbsent(node.tile(), ignored -> new ArrayList<>()).add(node);
        }
        private void openGoalConnection(SurfaceNode node) {
            goalChecked.put(node, target);
            Pending terminal = new Pending(node, goalSource.open(node), true);
            terminal.terminalTarget = target; pending.addFirst(terminal);
        }
        private void discardPending(Pending expansion) {
            expansion.scan.close(); memory.release(1);
            for (TraversalEdge edge : expansion.edges) releaseEdge(edge);
        }
        private final class TailRefresh {
            private record TileSlice(List<SurfaceNode> nodes, int limit) { }
            final List<TileSlice> slices = new ArrayList<>();
            int sliceIndex, nodeIndex;
            TailRefresh(Vec3 destination) {
                var min = NavigationGraphCache.TileKey.at(destination.add(-1.5, -1.5, -1.5));
                var max = NavigationGraphCache.TileKey.at(destination.add(1.5, 1.5, 1.5));
                for (int x = min.x(); x <= max.x(); x++) for (int y = min.y(); y <= max.y(); y++)
                    for (int z = min.z(); z <= max.z(); z++) {
                        List<SurfaceNode> nodes = recordsByTile.get(new NavigationGraphCache.TileKey(x, y, z));
                        if (nodes != null) slices.add(new TileSlice(nodes, nodes.size()));
                    }
            }
            SurfaceNode next() {
                while (sliceIndex < slices.size()) {
                    TileSlice slice = slices.get(sliceIndex);
                    if (nodeIndex < slice.limit()) return slice.nodes().get(nodeIndex++);
                    sliceIndex++; nodeIndex = 0;
                }
                return null;
            }
        }
        private void advanceTailRefresh(NavigationScheduler.Budget budget) {
            tailResourceLimited = false;
            if (tailRefresh == null && tailDirty) { tailRefresh = new TailRefresh(target); tailDirty = false; }
            if (tailRefresh == null) return;
            long started = System.nanoTime();
            try {
                for (int checked = 0; checked < 8 && budget.takeExpansion(); checked++) {
                    SurfaceNode node = tailRefresh.next();
                    if (node == null) { tailRefresh = null; return; }
                    Record record = records.get(node);
                    if (record == null || !record.closed || node.feet().distanceToSqr(target) > 2.25) continue;
                    if (atTarget(node)) { goalFrozen = true; reconstructionCursor = reconstructionEnd = node; return; }
                    if (goalSource == null || target.equals(goalChecked.get(node))) continue;
                    if (!memory.reserve(1)) { tailRefresh.nodeIndex--; tailResourceLimited = true; return; }
                    openGoalConnection(node);
                }
            } finally { budget.recordUnit(NavigationScheduler.UnitKind.SEARCH, started); }
        }
        private boolean retainEdges(List<TraversalEdge> edges) {
            int entries = 0;
            Set<TraversalEdge> additional = new HashSet<>();
            for (TraversalEdge edge : edges) if (!retainedEdges.containsKey(edge) && additional.add(edge))
                entries += NavigationGraphCache.edgeTransientEntries(edge);
            if (!memory.reserve(entries)) return false;
            for (TraversalEdge edge : edges) retainedEdges.merge(edge, 1, Integer::sum);
            return true;
        }
        private void referenceEdge(TraversalEdge edge) {
            Integer references = retainedEdges.get(edge);
            if (references == null) throw new IllegalStateException("Referencing unaccounted search edge");
            retainedEdges.put(edge, references + 1);
        }
        private void releaseEdge(TraversalEdge edge) {
            Integer references = retainedEdges.get(edge);
            if (references == null) throw new IllegalStateException("Releasing unaccounted search edge");
            if (references == 1) { retainedEdges.remove(edge); memory.release(NavigationGraphCache.edgeTransientEntries(edge)); }
            else retainedEdges.put(edge, references - 1);
        }
        private void defer(TraversalEdge edge) { referenceEdge(edge); deferred.addLast(edge); }
        private void predecessor(Record record, List<TraversalEdge> edges) {
            for (TraversalEdge edge : edges) referenceEdge(edge);
            if (record.predecessor != null) for (TraversalEdge old : record.predecessor) releaseEdge(old);
            record.predecessor = edges;
        }
        private void reopenForMemory(QueueEntry next, Record record) {
            record.closed = false; enqueue(next.node(), record.distance);
            result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED);
        }
        private boolean relax(TraversalEdge edge) {
            if (cache.isFailed(edge, profile)) return true;
            if (!allowed.test(edge)) {
                if (temporarilyExcluded.add(edge)) referenceEdge(edge);
                return true;
            }
            Record from = records.get(edge.from());
            if (from == null) return true;
            double distance = from.distance + edge.cost();
            Record destination = records.get(edge.to());
            if (destination == null) {
                if (!acquireRecord()) return false;
                ownedRecords++; destination = new Record(); putRecord(edge.to(), destination);
            }
            if (distance + 1.0E-9 >= destination.distance) return true;
            destination.distance = distance; predecessor(destination, List.of(edge)); destination.closed = false;
            if (atTarget(edge.to())) goalFrozen = true;
            enqueue(edge.to(), distance); return true;
        }
        private void relaxPortal(NavigationGraphCache.PortalPath path) {
            if (path.edges().stream().anyMatch(edge -> !allowed.test(edge) || cache.isFailed(edge, profile))) return;
            if (!retainEdges(path.edges())) return; // Optional macro shortcut; retain the fine frontier.
            try { relaxRetainedPortal(path); }
            finally { for (TraversalEdge edge : path.edges()) releaseEdge(edge); }
        }
        private void relaxRetainedPortal(NavigationGraphCache.PortalPath path) {
            Record from = records.get(path.from());
            if (from == null) return;
            double distance = from.distance + path.cost();
            Record destination = records.get(path.to());
            if (destination == null) {
                if (!acquireRecord()) return; // Optional acceleration; fine frontier is preserved.
                ownedRecords++; destination = new Record(); putRecord(path.to(), destination);
            }
            if (distance + 1.0E-9 >= destination.distance) return;
            destination.distance = distance; predecessor(destination, path.edges()); destination.closed = false;
            if (atTarget(path.to())) goalFrozen = true;
            enqueue(path.to(), distance);
        }
        private void enqueue(SurfaceNode node, double distance) {
            QueueEntry previous = queued.remove(node);
            if (previous != null) frontier.remove(previous);
            // With a moved goal, subtracting anchor.distanceTo(target) makes this a
            // consistent lower bound by the triangle inequality. That global constant
            // does not affect ordering, so retargeting preserves this frontier intact.
            double heuristic = node.feet().distanceTo(heuristicAnchor);
            QueueEntry entry = new QueueEntry(node, distance, distance + heuristic, order++);
            frontier.add(entry); queued.put(node, entry);
        }
        private boolean atTarget(SurfaceNode node) {
            return node.feet().distanceToSqr(target) <= NavigationTuning.EPSILON * NavigationTuning.EPSILON;
        }
        private void advanceCompletion(NavigationScheduler.Budget budget) {
            long unitStarted = System.nanoTime();
            try { advanceCompletionWork(budget); }
            finally { budget.recordUnit(NavigationScheduler.UnitKind.RECONSTRUCT, unitStarted); }
        }
        private void advanceCompletionWork(NavigationScheduler.Budget budget) {
            while (!reconstructionCursor.equals(start)) {
                if (!budget.takeExpansion()) return;
                if (reconstructionPredecessor == null) {
                    Record record = records.get(reconstructionCursor);
                    if (record == null || record.predecessor == null) throw new IllegalStateException("Broken route predecessor");
                    reconstructionPredecessor = record.predecessor; predecessorIndex = reconstructionPredecessor.size() - 1;
                }
                reversed.addFirst(reconstructionPredecessor.get(predecessorIndex--));
                if (predecessorIndex < 0) {
                    reconstructionCursor = reconstructionPredecessor.get(0).from(); reconstructionPredecessor = null;
                }
            }
            if (prefixEdgeCount < 0) prefixEdgeCount = reversed.size();
            if (suffix != null) {
                while (suffixAppended < suffix.size()) {
                    if (!budget.takeExpansion()) return;
                    TraversalEdge edge = suffix.edge(suffixAppended);
                    SurfaceNode previous = reversed.isEmpty() ? start : reversed.peekLast().to();
                    if (!previous.equals(edge.from())) { abandonRepair(); return; }
                    if (!routeMemory.reserve(1)) { abandonRepair(); return; }
                    reversed.addLast(edge); suffixAppended++;
                }
                reconstructionEnd = reversed.isEmpty() ? start : reversed.peekLast().to();
            }
            if (refinedEdges == null) { refinedEdges = List.copyOf(reversed); routePortals.add(start); }
            // Transfer the already-accounted edge payload before freeing search records. This
            // avoids requiring a second copy's capacity and leaves room for immutable route
            // indices even when a search has almost filled the shared transient allowance.
            while (routeTransferIndex < refinedEdges.size()) {
                if (!budget.takeExpansion()) return;
                TraversalEdge edge = refinedEdges.get(routeTransferIndex);
                boolean unique = transferredRouteEdges.add(edge);
                if (routeTransferIndex++ < prefixEdgeCount) {
                    if (unique) memory.transferTo(routeMemory, NavigationGraphCache.edgeTransientEntries(edge));
                } else suffix.transferPayload(edge, routeMemory, unique);
            }
            if (!searchStateReleased) releaseSearchState();
            if (!routeListReserved) {
                if (!routeMemory.reserve(refinedEdges.size() - suffixAppended + 2)) {
                    result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
                }
                routeListReserved = true; // The edge list, the start portal, and the RoutePlan itself.
            }
            while (refinementIndex < refinedEdges.size()) {
                if (refiningEdge == null) {
                    if (!budget.takeExpansion()) return;
                    TraversalEdge edge = refinedEdges.get(refinementIndex);
                    boolean portal = !cache.region(edge.from(), profile).equals(cache.region(edge.to(), profile))
                            || edge.action() == TraversalEdge.Action.DROP || edge.action() == TraversalEdge.Action.NATIVE_INTERACTION;
                    if (portal && !routeMemory.reserve(1)) {
                        result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
                    }
                    if (portal) routePortals.add(edge.to());
                    refiningEdge = edge; refinementDependencies = edge.dependencies().entrySet().iterator();
                }
                while (nextDependency != null || refinementDependencies.hasNext()) {
                    if (!budget.takeExpansion()) return;
                    if (nextDependency == null) nextDependency = refinementDependencies.next();
                    Long existing = routeDependencies.get(nextDependency.getKey());
                    if (existing != null && !existing.equals(nextDependency.getValue())) { expiredCompletion(); return; }
                    if (existing == null && !routeMemory.reserve(1)) {
                        result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
                    }
                    routeDependencies.put(nextDependency.getKey(), nextDependency.getValue()); nextDependency = null;
                }
                TraversalEdge edge = refiningEdge; refiningEdge = null; refinementDependencies = null; refinementIndex++;
                if (!portalSegment.isEmpty() && (!portalSegment.get(0).from().tile().equals(edge.from().tile())
                        || edge.action() == TraversalEdge.Action.DROP || edge.action() == TraversalEdge.Action.NATIVE_INTERACTION)) {
                    cache.rememberSegment(profile, portalSegment); portalSegment.clear();
                }
                portalSegment.add(edge);
                if (!edge.from().tile().equals(edge.to().tile()) || edge.action() == TraversalEdge.Action.DROP
                        || edge.action() == TraversalEdge.Action.NATIVE_INTERACTION) {
                    cache.rememberSegment(profile, portalSegment); portalSegment.clear();
                }
            }
            if (!portalSegment.isEmpty()) cache.rememberSegment(profile, portalSegment);
            if (!routePortals.get(routePortals.size() - 1).equals(reconstructionEnd)) {
                if (!routeMemory.reserve(1)) {
                    result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED); return;
                }
                routePortals.add(reconstructionEnd);
            }
            if (suffix != null) {
                Boolean valid = suffix.verify(budget);
                if (valid == null) return;
                if (!valid) { abandonRepair(); return; }
            }
            if (completionDependencies == null || completionRevision != cache.revision()) {
                completionRevision = cache.revision(); completionDependencies = routeDependencies.entrySet().iterator();
            }
            while (completionDependencies.hasNext()) {
                if (!budget.takeExpansion()) return;
                var dependency = completionDependencies.next();
                if (!cache.valid(dependency.getKey(), dependency.getValue())) { expiredCompletion(); return; }
            }
            RoutePlan route = new RoutePlan(UUID.randomUUID(), targetId, publicationTarget == null ? target : publicationTarget,
                    profile, refinedEdges, routePortals,
                    routeDependencies, new RoutePlan.Reservation(routeMemory));
            routeRetention = route.retain(); routePublished = true;
            result = PlanningResult.ready(route);
            clearCompletionState();
        }
        private void expiredCompletion() {
            if (suffix != null) { abandonRepair(); return; }
            evidenceExpired = true;
            result = PlanningResult.pending(PlanningResult.Reason.RESOURCE_LIMITED);
        }
        private void abandonRepair() {
            fullPlanRequired = true;
            result = PlanningResult.pending(PlanningResult.Reason.BUILD);
            releaseRecords();
        }
        private void releaseSearchState() {
            if (searchStateReleased) return;
            searchStateReleased = true;
            pool.release(ownedRecords); ownedRecords = 0;
            for (Pending expansion : pending) expansion.scan.close();
            memory.close(); retainedEdges.clear();
            records.clear(); recordsByTile.clear(); tailRefresh = null; tailDirty = false;
            frontier.clear(); queued.clear(); pending.clear(); pendingByNode.clear(); goalChecked.clear(); deferred.clear(); temporarilyExcluded.clear();
        }
        private void clearCompletionState() {
            reconstructionCursor = null; reconstructionEnd = null; reconstructionPredecessor = null; refinedEdges = null;
            reversed.clear(); routeDependencies.clear(); routePortals.clear(); portalSegment.clear();
            transferredRouteEdges.clear();
            refinementDependencies = null; completionDependencies = null; nextDependency = null; refiningEdge = null;
            if (suffix != null) { suffix.close(); suffix = null; }
        }
        private void releaseRecords() {
            releaseSearchState(); clearCompletionState();
            if (!routePublished) routeMemory.close();
        }
        @Override public void close() {
            if (closed) return;
            releaseRecords();
            result = PlanningResult.pending(PlanningResult.Reason.BUILD);
            if (routeRetention != null) { routeRetention.close(); routeRetention = null; }
            closed = true;
        }
    }
}
