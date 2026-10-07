package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main-thread, shape-based directed movement evidence. A cursor owns its entity collision context;
 * neither the entity nor the world is moved while exploring a hypothetical surface.
 */
public final class TraversalValidator {
    public static final double EPSILON = TraversalMath.EPSILON;
    public static final double DROP_DRIFT = 0.20;
    private static final int SHAPE_BATCH = 16;
    private static final int BLOCK_BATCH = 8;
    private static final int CACHED_CELL_BATCH = 128;
    // Exact vanilla Block/AirBlock collision methods do not inspect CollisionContext.
    // Keep the full entity/profile key, but avoid rereading overlapping shaft layers solely
    // because the hypothetical feet moved down one block. All subclasses retain feetY.
    private static final double CONTEXT_INDEPENDENT_FEET = Double.NEGATIVE_INFINITY;
    private static final int MAX_LOCAL_BOXES = 1024;
    private static final int MAX_CANDIDATES = 1024;
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private final NavigationContext context;
    private final NavigationGraphCache cache;

    public enum Verdict { CLEAR, BLOCKED, WAITING_CHUNK, UNKNOWN, STALE, NATIVE_INTERACTION }
    public enum ScanStatus { PENDING, COMPLETE, WAITING_CHUNK, RESOURCE_LIMITED }
    public record NeighborBatch(List<TraversalEdge> edges, ScanStatus status, int workUsed) {
        public NeighborBatch { edges = List.copyOf(edges); }
    }
    public record ValidationBatch(Verdict verdict, ScanStatus status, int workUsed) { }
    public record LocateBatch(@Nullable SurfaceNode node,ScanStatus status,int workUsed) { }
    public record ProbeBatch(long fingerprint,ScanStatus status,int workUsed) { }
    public record ShapeEvidence(AABB area,double feetY,long fingerprint) { }

    public TraversalValidator(NavigationContext context, NavigationGraphCache cache) {
        this.context = context;
        this.cache = cache;
    }

    /** Submit this cursor to the same room/server geometry budget as neighbour generation. */
    public ValidationCursor beginValidation(Mob mob, TraversalEdge edge, MovementProfile profile) {
        return new ValidationCursor(mob,edge,profile);
    }
    public ValidationCursor beginStanding(Mob mob,SurfaceNode target,MovementProfile profile,double tolerance) {
        return new ValidationCursor(mob,target,profile,tolerance);
    }

    public ValidationBatch advanceValidation(ValidationCursor cursor,int workUnits,long deadlineNanos) {
        int used=0;
        while (!cursor.done && used<workUnits && System.nanoTime()<deadlineNanos) {
            used++;
            if (!context.owns(cursor.mob) || !cursor.mob.onGround()
                    || cursor.mob.position().distanceToSqr(cursor.actual)>0.04) {
                cursor.verdict=Verdict.UNKNOWN; cursor.done=true; break;
            }
            if (!cursor.inputReady || cursor.provisionalVerdict!=null || cursor.evidenceRevision!=cache.revision()) {
                cursor.verifyEvidenceBatch();
                continue;
            }
            if (!cursor.scan.done) {
                cursor.scan.advance();
                if (cursor.scan.waiting) return new ValidationBatch(Verdict.WAITING_CHUNK,ScanStatus.WAITING_CHUNK,used);
                if (cursor.scan.limited) return new ValidationBatch(Verdict.UNKNOWN,ScanStatus.RESOURCE_LIMITED,used);
                continue;
            }
            if (cursor.standingTarget!=null) {
                cursor.finish(isStanding(cursor.mob,cursor.standingTarget,cursor.profile,cursor.tolerance)
                        && standing(cursor.mob.position(),cursor.profile,cursor.scan)?Verdict.CLEAR:Verdict.BLOCKED);
                continue;
            }
            if (cursor.edge.action()!=TraversalEdge.Action.DROP) {
                if (cursor.mob.position().distanceToSqr(cursor.edge.to().feet())<=EPSILON*EPSILON) {
                    cursor.finish(standing(cursor.mob.position(),cursor.profile,cursor.scan)?Verdict.CLEAR:Verdict.BLOCKED);
                    continue;
                }
                TraversalEdge checked=localEdge(cursor.mob,node(cursor.mob.position()),cursor.edge.to().feet(),
                        cursor.profile,cursor.scan,cursor.edge.action()==TraversalEdge.Action.STEP);
                cursor.finish(checked==null?Verdict.BLOCKED:
                        checked.action()==TraversalEdge.Action.NATIVE_INTERACTION?Verdict.NATIVE_INTERACTION:Verdict.CLEAR);
                continue;
            }
            if (cursor.drop==null) {
                Vec3 departure=cursor.edge.controlPoints().get(1);
                if (!clearSweep(cursor.mob.position(),departure,cursor.profile,cursor.scan)
                        || supported(departure,cursor.profile,cursor.scan.boxes)) {
                    cursor.verdict=Verdict.BLOCKED; cursor.done=true; continue;
                }
                cursor.drop=new DropCursor(cursor.mob,cursor.edge.from(),departure,cursor.profile,cursor.scan.dependencies);
                continue;
            }
            TraversalEdge checked=cursor.drop.advance();
            if (cursor.drop.scan!=null && cursor.drop.scan.waiting)
                return new ValidationBatch(Verdict.WAITING_CHUNK,ScanStatus.WAITING_CHUNK,used);
            if (cursor.drop.limited || cursor.drop.scan!=null && cursor.drop.scan.limited)
                return new ValidationBatch(Verdict.UNKNOWN,ScanStatus.RESOURCE_LIMITED,used);
            if (cursor.drop.done) {
                cursor.finish(checked!=null && checked.to().feet().distanceToSqr(cursor.edge.to().feet())<EPSILON*EPSILON
                        ? Verdict.CLEAR : Verdict.BLOCKED);
            }
        }
        return new ValidationBatch(cursor.verdict,cursor.done?ScanStatus.COMPLETE:ScanStatus.PENDING,used);
    }

    public final class ValidationCursor implements AutoCloseable {
        private final Mob mob;
        private final TraversalEdge edge;
        private final SurfaceNode standingTarget;
        private final double tolerance;
        private final MovementProfile profile;
        private final Vec3 actual;
        private final ShapeScan scan;
        private DropCursor drop;
        private boolean done;
        private Verdict verdict=Verdict.UNKNOWN;
        private Verdict provisionalVerdict;
        private Iterator<Map.Entry<NavigationGraphCache.TileKey,Long>> checkingInput,checkingLocal,checkingDrop;
        private boolean inputReady;
        private long evidenceRevision=Long.MIN_VALUE;
        @Override public void close() {
            scan.close(); if (drop!=null) drop.close(); checkingInput=null;checkingLocal=null;checkingDrop=null;
        }
        // Package-private diagnostic used by the real multi-frame arrival regression.
        boolean verifyingEvidence() { return provisionalVerdict!=null && !done; }
        private void finish(Verdict result) {
            if (result==Verdict.CLEAR || result==Verdict.NATIVE_INTERACTION) {
                provisionalVerdict=result; restartEvidenceCheck();
            } else { verdict=result; done=true; }
        }
        private void restartEvidenceCheck() {
            evidenceRevision=cache.revision();
            inputReady=false;
            checkingInput=edge==null?java.util.Collections.emptyIterator():edge.dependencies().entrySet().iterator();
            checkingLocal=scan.dependencies.entrySet().iterator();
            checkingDrop=drop==null?java.util.Collections.emptyIterator():drop.dependencies.entrySet().iterator();
        }
        private void verifyEvidenceBatch() {
            if (evidenceRevision!=cache.revision()) restartEvidenceCheck();
            int checked=0;
            while (checked<BLOCK_BATCH) {
                if (!inputReady) {
                    if (edge==null) {
                        checked++;
                        if (!cache.valid(standingTarget.tile(),standingTarget.version())) {
                            verdict=Verdict.STALE;done=true;return;
                        }
                        inputReady=true;
                    } else if (checkingInput.hasNext()) {
                        var input=checkingInput.next();checked++;
                        if (!cache.valid(input.getKey(),input.getValue())) {
                            verdict=Verdict.STALE;done=true;return;
                        }
                        continue;
                    } else inputReady=true;
                }
                if (provisionalVerdict==null) return;
                if (checked>=BLOCK_BATCH) return;
                var next=checkingLocal.hasNext()?checkingLocal:checkingDrop;
                if (!next.hasNext()) {
                    verdict=provisionalVerdict;done=true;return;
                }
                var dependency=next.next();checked++;
                if (!cache.valid(dependency.getKey(),dependency.getValue())) {
                    verdict=Verdict.UNKNOWN;done=true;return;
                }
            }
        }
        private ValidationCursor(Mob mob,TraversalEdge edge,MovementProfile profile) {
            this.mob=mob; this.edge=edge; this.profile=profile; actual=mob.position();
            standingTarget=null; tolerance=0;
            Vec3 end=edge.action()==TraversalEdge.Action.DROP ? edge.controlPoints().get(1) : edge.to().feet();
            scan=new ShapeScan(mob,bodyAt(profile,actual).minmax(bodyAt(profile,end))
                    .expandTowards(0,1.25,0).inflate(0.25),actual.y);
            if (actual.distanceToSqr(end)>9 || !mob.onGround()) { done=true; verdict=Verdict.UNKNOWN; }
        }
        private ValidationCursor(Mob mob,SurfaceNode target,MovementProfile profile,double tolerance) {
            this.mob=mob; edge=null; standingTarget=target; this.profile=profile;
            this.tolerance=tolerance; actual=mob.position();
            scan=new ShapeScan(mob,bodyAt(profile,actual).inflate(0.25),actual.y);
            if (!isStanding(mob,target,profile,tolerance)) { done=true; verdict=Verdict.BLOCKED; }
        }
    }

    /** Arrival proves one actual standing body, unlike a movement proof's complete swept edge. */
    public boolean arrivalSnapshotApplies(ValidationCursor cursor) {
        return cursor.standingTarget==null || isStanding(cursor.mob,cursor.standingTarget,cursor.profile,cursor.tolerance)
                && Math.abs(cursor.mob.getY()-cursor.actual.y)<=EPSILON
                && cursor.mob.position().distanceToSqr(cursor.actual)<=0.04;
    }

    /** Reuse never synchronously walks a deep shaft's dependency map. */
    public boolean recheckValidationEvidence(ValidationCursor cursor) {
        if (!cursor.done || cursor.verdict!=Verdict.CLEAR && cursor.verdict!=Verdict.NATIVE_INTERACTION
                || cursor.evidenceRevision==cache.revision()) return false;
        cursor.done=false;cursor.verdict=Verdict.UNKNOWN;cursor.restartEvidenceCheck();return true;
    }

    /** Completed positive evidence keeps only its already charged dependency entries. */
    public void releaseValidationGeometry(ValidationCursor cursor) {
        if (!cursor.done) throw new IllegalStateException("Unfinished validation geometry");
        if (cursor.verdict==Verdict.CLEAR || cursor.verdict==Verdict.NATIVE_INTERACTION) {
            cursor.scan.releaseGeometry();
            if (cursor.drop!=null && cursor.drop.scan!=null) {
                cursor.drop.scan.close();cursor.drop.scan=null;
            }
        } else cursor.close();
    }

    public boolean versionsMatch(TraversalEdge edge) {
        for (var dependency : edge.dependencies().entrySet())
            if (cache.version(dependency.getKey())!=dependency.getValue()) return false;
        return true;
    }

    public LocateCursor beginLocate(Mob mob,Vec3 approximate,MovementProfile profile) {
        return new LocateCursor(mob,approximate,profile);
    }

    public ProbeCursor beginProbe(Mob mob,TraversalEdge edge,MovementProfile profile) {
        return new ProbeCursor(mob,edge,profile);
    }
    public ProbeCursor beginProbe(Mob mob,ShapeEvidence evidence) { return new ProbeCursor(mob,evidence); }
    @Nullable public ShapeEvidence validationEvidence(ValidationCursor cursor) {
        if (!cursor.done || !cursor.scan.done || cursor.verdict==Verdict.UNKNOWN
                || cursor.verdict==Verdict.WAITING_CHUNK) return null;
        return new ShapeEvidence(cursor.scan.area,cursor.scan.feetY,cursor.scan.fingerprint);
    }
    public ProbeBatch advanceProbe(ProbeCursor cursor,int workUnits,long deadlineNanos) {
        int used=0;
        while (!cursor.scan.done && used<workUnits && System.nanoTime()<deadlineNanos) {
            used++; cursor.scan.advance();
            if (cursor.scan.waiting) return new ProbeBatch(0,ScanStatus.WAITING_CHUNK,used);
            if (cursor.scan.limited) return new ProbeBatch(0,ScanStatus.RESOURCE_LIMITED,used);
        }
        return new ProbeBatch(cursor.scan.fingerprint,cursor.scan.done?ScanStatus.COMPLETE:ScanStatus.PENDING,used);
    }
    public final class ProbeCursor implements AutoCloseable {
        private final ShapeScan scan;
        @Override public void close() { scan.close(); }
        private ProbeCursor(Mob mob,ShapeEvidence evidence) {
            scan=new ShapeScan(mob,evidence.area(),evidence.feetY(),false);
        }
        private ProbeCursor(Mob mob,TraversalEdge edge,MovementProfile profile) {
            AABB bounds=bodyAt(profile,edge.from().feet()).minmax(bodyAt(profile,edge.to().feet()));
            for (Vec3 control : edge.controlPoints()) bounds=bounds.minmax(bodyAt(profile,control));
            if (edge.action()==TraversalEdge.Action.DROP) bounds=bounds.inflate(DROP_DRIFT,0,DROP_DRIFT);
            // A probe streams boxes into its digest. Deep shafts do not accumulate per-height geometry.
            scan=new ShapeScan(mob,bounds,edge.from().feet().y,false);
        }
    }

    public LocateBatch advanceLocate(LocateCursor cursor,int workUnits,long deadlineNanos) {
        int used=0;
        while (!cursor.done && used<workUnits && System.nanoTime()<deadlineNanos) {
            used++;
            if (!cursor.scan.done) {
                cursor.scan.advance();
                if (cursor.scan.waiting) return new LocateBatch(null,ScanStatus.WAITING_CHUNK,used);
                if (cursor.scan.limited) return new LocateBatch(null,ScanStatus.RESOURCE_LIMITED,used);
                continue;
            }
            if (cursor.index>=cursor.scan.boxes.size()) { cursor.done=true; continue; }
            AABB box=cursor.scan.boxes.get(cursor.index++);
            for (Vec3 point : List.of(cursor.approximate,new Vec3(Mth.floor(cursor.approximate.x)+0.5,
                    cursor.approximate.y,Mth.floor(cursor.approximate.z)+0.5))) {
                Vec3 feet=new Vec3(point.x,box.maxY,point.z);
                double distance=feet.distanceToSqr(cursor.approximate);
                if (Math.abs(feet.y-cursor.approximate.y)<=1.25 && distance<cursor.best
                        && standing(feet,cursor.profile,cursor.scan)) {
                    cursor.result=node(feet); cursor.best=distance;
                }
            }
        }
        return new LocateBatch(cursor.result,cursor.done?ScanStatus.COMPLETE:ScanStatus.PENDING,used);
    }

    public final class LocateCursor implements AutoCloseable {
        private final MovementProfile profile;
        private final Vec3 approximate;
        private final ShapeScan scan;
        private int index;
        private boolean done;
        private double best=Double.POSITIVE_INFINITY;
        private SurfaceNode result;
        @Override public void close() { scan.close(); }
        private LocateCursor(Mob mob,Vec3 approximate,MovementProfile profile) {
            this.profile=profile; this.approximate=approximate;
            if (!finite(approximate)) throw new IllegalArgumentException("Nonfinite feet");
            scan=new ShapeScan(mob,bodyAt(profile,approximate).inflate(0.51,1.25,0.51),approximate.y);
        }
    }

    public NeighborCursor beginNeighbours(Mob mob, SurfaceNode from, MovementProfile profile, long geometryVersion) {
        return new NeighborCursor(mob, from, profile);
    }

    public ConnectionCursor beginConnection(Mob mob,SurfaceNode from,Vec3 destination,MovementProfile profile) {
        return new ConnectionCursor(mob,from,destination,profile);
    }
    public NeighborBatch advanceConnection(ConnectionCursor cursor,int workUnits,long deadlineNanos) {
        int used=0;
        while (!cursor.done && used<workUnits && System.nanoTime()<deadlineNanos) {
            used++;
            if (!cursor.scan.done) {
                cursor.scan.advance();
                if (cursor.scan.waiting) return new NeighborBatch(List.of(),ScanStatus.WAITING_CHUNK,used);
                if (cursor.scan.limited) return new NeighborBatch(List.of(),ScanStatus.RESOURCE_LIMITED,used);
                continue;
            }
            TraversalEdge edge=localEdge(cursor.mob,cursor.from,cursor.destination,cursor.profile,cursor.scan);
            if (edge==null && cursor.from.feet().distanceToSqr(cursor.destination)<=EPSILON*EPSILON
                    && standing(cursor.destination,cursor.profile,cursor.scan))
                edge=new TraversalEdge(cursor.from,node(cursor.destination),TraversalEdge.Action.WALK,
                        List.of(cursor.from.feet(),cursor.destination),0,cursor.scan.dependencies);
            if (edge != null && !cursor.outputs.retain(edge))
                return new NeighborBatch(List.of(),ScanStatus.RESOURCE_LIMITED,used);
            cursor.done=true;
            return new NeighborBatch(edge==null?List.of():List.of(edge),ScanStatus.COMPLETE,used);
        }
        return new NeighborBatch(List.of(),cursor.done?ScanStatus.COMPLETE:ScanStatus.PENDING,used);
    }
    public final class ConnectionCursor implements AutoCloseable {
        private final Mob mob;
        private final SurfaceNode from;
        private final Vec3 destination;
        private final MovementProfile profile;
        private final ShapeScan scan;
        private final EdgeOutputs outputs = new EdgeOutputs();
        private boolean done;
        @Override public void close() { scan.close(); outputs.close(); }
        public boolean transferEdge(TraversalEdge edge, NavigationGraphCache.TransientLease destination) {
            return outputs.transfer(edge, destination);
        }
        private ConnectionCursor(Mob mob,SurfaceNode from,Vec3 destination,MovementProfile profile) {
            this.mob=mob; this.from=from; this.destination=destination; this.profile=profile;
            if (!finite(destination)) throw new IllegalArgumentException("Nonfinite destination");
            done=from.feet().distanceToSqr(destination)>2.25;
            scan=new ShapeScan(mob,bodyAt(profile,from.feet()).minmax(bodyAt(profile,destination))
                    .expandTowards(0,1.25,0),from.feet().y);
        }
    }

    public NeighborBatch advance(NeighborCursor cursor, int workUnits) {
        return advance(cursor, workUnits, Long.MAX_VALUE);
    }

    public NeighborBatch advance(NeighborCursor cursor, int workUnits, long deadlineNanos) {
        List<TraversalEdge> ready = new ArrayList<>();
        cursor.capacityLimited=false;
        if (cursor.limited) return new NeighborBatch(List.of(),ScanStatus.RESOURCE_LIMITED,0);
        int used = 0;
        while (used < workUnits && System.nanoTime() < deadlineNanos && !cursor.done) {
            used++;
            if (!cursor.local.done) {
                cursor.local.advance();
                if (cursor.local.waiting) return new NeighborBatch(ready, ScanStatus.WAITING_CHUNK, used);
                if (cursor.local.limited) return new NeighborBatch(ready, ScanStatus.RESOURCE_LIMITED, used);
                continue;
            }
            if (!cursor.nativeGenerated) {
                int end=Math.min(cursor.local.boxes.size(),cursor.nativeBoxIndex+SHAPE_BATCH);
                while (cursor.nativeBoxIndex<end) {
                    AABB box=cursor.local.boxes.get(cursor.nativeBoxIndex);
                    Set<Vec3> additions=new LinkedHashSet<>();
                    if (box.maxY>=cursor.from.feet().y-1-EPSILON
                            && box.maxY<=cursor.from.feet().y+cursor.profile.stepHeight()+EPSILON) {
                        for (int[] direction : DIRECTIONS) {
                            Vec3 feet=new Vec3(Mth.floor(cursor.from.feet().x)+direction[0]+0.5,box.maxY,
                                    Mth.floor(cursor.from.feet().z)+direction[1]+0.5);
                            if (overlapsHorizontal(bodyAt(cursor.profile,feet),box)
                                    && !cursor.nativeCandidates.contains(feet)) additions.add(feet);
                        }
                    }
                    if (cursor.nativeCandidates.size()+additions.size()>MAX_CANDIDATES) {
                        cursor.limited=true; return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    }
                    if (!cursor.reserve(additions.size())) return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    cursor.nativeCandidates.addAll(additions); cursor.nativeBoxIndex++;
                }
                if (cursor.nativeBoxIndex>=cursor.local.boxes.size()) {
                    Vec3 centre=new Vec3(Mth.floor(cursor.from.feet().x)+0.5,cursor.from.feet().y,
                            Mth.floor(cursor.from.feet().z)+0.5);
                    if (!nativePoint(cursor.from.feet()) && !cursor.nativeCandidates.contains(centre)) {
                        if (cursor.nativeCandidates.size()>=MAX_CANDIDATES) {
                            cursor.limited=true; return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                        }
                        if (!cursor.reserve(1)) return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                        cursor.nativeCandidates.add(centre);
                    }
                    if (!cursor.reserve(cursor.nativeCandidates.size()))
                        return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    cursor.nativePending.addAll(cursor.nativeCandidates); cursor.nativeGenerated=true;
                }
                continue;
            }
            if (cursor.nativeIndex<cursor.nativePending.size()) {
                Vec3 feet=cursor.nativePending.get(cursor.nativeIndex);
                TraversalEdge edge=localEdge(cursor.mob,cursor.from,feet,cursor.profile,cursor.local);
                if (edge!=null) {
                    if (!cursor.outputs.retain(edge)) return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    ready.add(edge);
                }
                cursor.nativeIndex++;
                continue;
            }
            if (!cursor.generated) {
                generateCandidateBatch(cursor);
                if (cursor.limited || cursor.capacityLimited) return new NeighborBatch(ready, ScanStatus.RESOURCE_LIMITED, used);
                continue;
            }
            if (cursor.candidateIndex < cursor.candidates.size()) {
                Vec3 feet = cursor.candidates.get(cursor.candidateIndex);
                if (cursor.nativeCandidates.contains(feet)) { cursor.candidateIndex++; continue; }
                TraversalEdge edge = localEdge(cursor.mob, cursor.from, feet, cursor.profile, cursor.local);
                if (edge != null) {
                    if (!cursor.outputs.retain(edge)) return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    ready.add(edge);
                }
                cursor.candidateIndex++;
                continue;
            }
            if (cursor.drop != null) {
                TraversalEdge edge = cursor.drop.advance();
                if (cursor.drop.scan != null && cursor.drop.scan.waiting)
                    return new NeighborBatch(ready, ScanStatus.WAITING_CHUNK, used);
                if (cursor.drop.limited || cursor.drop.scan != null && cursor.drop.scan.limited)
                    return new NeighborBatch(ready, ScanStatus.RESOURCE_LIMITED, used);
                if (edge != null) {
                    // A completed drop retains its final scan until this output can be handed off.
                    if (!cursor.outputs.retain(edge)) return new NeighborBatch(ready,ScanStatus.RESOURCE_LIMITED,used);
                    ready.add(edge);
                }
                if (cursor.drop.done) { cursor.drop.close(); cursor.drop = null; }
                continue;
            }
            if (cursor.dropIndex < cursor.departures.size()) {
                cursor.drop = new DropCursor(cursor.mob, cursor.from, cursor.departures.get(cursor.dropIndex++),
                        cursor.profile, cursor.local.dependencies,true);
                continue;
            }
            cursor.done = true;
        }
        return new NeighborBatch(ready, cursor.done ? ScanStatus.COMPLETE : ScanStatus.PENDING, used);
    }

    public final class NeighborCursor implements AutoCloseable {
        private final Mob mob;
        private final SurfaceNode from;
        private final MovementProfile profile;
        private final ShapeScan local;
        private final List<Vec3> candidates = new ArrayList<>();
        private final Set<Vec3> nativeCandidates=new LinkedHashSet<>();
        private final List<Vec3> nativePending=new ArrayList<>();
        private final Set<Vec3> candidateSet = new LinkedHashSet<>();
        private final List<Vec3> departures = new ArrayList<>();
        private int candidateIndex, dropIndex, directionIndex,nativeBoxIndex,nativeIndex;
        private boolean generated, limited, done,nativeGenerated;
        private DropCursor drop;
        private CandidateColumn column;
        private final NavigationGraphCache.TransientLease lease=cache.transientLease();
        private final EdgeOutputs outputs = new EdgeOutputs();
        private boolean capacityLimited;
        @Override public void close() {
            local.close(); if (drop!=null) drop.close(); lease.close(); outputs.close();
            candidates.clear(); nativeCandidates.clear(); nativePending.clear(); candidateSet.clear(); departures.clear();
            column=null;
        }
        private boolean reserve(int entries) {
            if (lease.reserve(entries)) return true; capacityLimited=true; return false;
        }
        public boolean transferEdge(TraversalEdge edge, NavigationGraphCache.TransientLease destination) {
            return outputs.transfer(edge, destination);
        }
        private NeighborCursor(Mob mob, SurfaceNode from, MovementProfile profile) {
            this.mob = mob;
            this.from = from;
            this.profile = profile;
            double radius = 1.5 + profile.width() / 2;
            Vec3 p = from.feet();
            local = new ShapeScan(mob, new AABB(p.x-radius, p.y-1.01, p.z-radius,
                    p.x+radius, p.y+profile.height()+Math.max(1.25,profile.stepHeight()), p.z+radius),p.y,true,profile);
        }
    }

    /** Output memory stays charged while crossing the geometry/search boundary. */
    private final class EdgeOutputs implements AutoCloseable {
        private final NavigationGraphCache.TransientLease lease = cache.transientLease();
        private final Map<TraversalEdge, Integer> allocations = new java.util.IdentityHashMap<>();
        boolean retain(TraversalEdge edge) {
            int entries = NavigationGraphCache.edgeTransientEntries(edge);
            if (!lease.reserve(entries)) return false;
            allocations.put(edge, entries); return true;
        }
        boolean transfer(TraversalEdge edge, NavigationGraphCache.TransientLease destination) {
            Integer entries = allocations.get(edge);
            if (entries == null || !lease.transferTo(destination, entries)) return false;
            allocations.remove(edge); return true;
        }
        @Override public void close() { allocations.clear(); lease.close(); }
    }

    /** Local acquisition is deliberately bounded; finding a remote floor belongs to the drop cursor. */
    @Nullable
    public SurfaceNode locate(Mob mob, Vec3 approximate, MovementProfile profile) {
        if (!finite(approximate)) return null;
        AABB region = bodyAt(profile, approximate).inflate(0.51, 1.25, 0.51);
        ShapeScan scan = immediateScan(mob, region, approximate.y);
        if (scan == null) return null;
        try {
        SurfaceNode nearest = null;
        double best = Double.POSITIVE_INFINITY;
        List<Vec3> points = List.of(approximate, new Vec3(Mth.floor(approximate.x)+0.5,
                approximate.y, Mth.floor(approximate.z)+0.5));
        for (AABB box : scan.boxes) for (Vec3 point : points) {
            Vec3 feet = new Vec3(point.x, box.maxY, point.z);
            double distance = feet.distanceToSqr(approximate);
            if (Math.abs(feet.y-approximate.y) <= 1.25 && distance < best && standing(feet, profile, scan)) {
                nearest = node(feet);
                best = distance;
            }
        }
        return nearest;
        } finally { scan.close(); }
    }

    /** Cheap arrival prefilter only. Complete an arrival with beginStanding's budgeted shape proof. */
    public boolean isStanding(Mob mob, SurfaceNode node, MovementProfile profile, double tolerance) {
        return mob.onGround() && Math.abs(mob.getY()-node.feet().y)<=EPSILON
                && mob.position().distanceToSqr(node.feet())<=tolerance*tolerance
                && inside(bodyAt(profile,mob.position()));
    }

    /** Synchronous compatibility diagnostic. Production uses beginValidation/advanceValidation;
     * this helper can walk the full input dependency map and must not run in entity execution. */
    public Verdict validateExecution(Mob mob, TraversalEdge edge, MovementProfile profile) {
        if (!context.owns(mob) || !finite(mob.position())) return Verdict.BLOCKED;
        for (var dependency : edge.dependencies().entrySet())
            if (cache.version(dependency.getKey()) != dependency.getValue()) return Verdict.UNKNOWN;
        Vec3 actual = mob.position();
        if (edge.action() == TraversalEdge.Action.DROP) {
            Vec3 end = mob.onGround() ? edge.controlPoints().get(1)
                    : new Vec3(actual.x, Math.max(edge.to().feet().y, actual.y-1), actual.z);
            if (actual.distanceToSqr(end) > 9) return Verdict.UNKNOWN;
            AABB area = bodyAt(profile, actual).minmax(bodyAt(profile, end));
            if (!inside(area)) return Verdict.BLOCKED;
            if (!loaded(area.inflate(1))) return Verdict.WAITING_CHUNK;
            ShapeScan scan = immediateScan(mob, area, actual.y);
            if (scan == null) return Verdict.UNKNOWN;
            try { return clearSweep(actual, end, profile, scan) ? Verdict.CLEAR : Verdict.BLOCKED; }
            finally { scan.close(); }
        }
        if (!mob.onGround() && edge.action() == TraversalEdge.Action.STEP) return Verdict.UNKNOWN;
        if (actual.distanceToSqr(edge.to().feet()) > 9) return Verdict.UNKNOWN;
        AABB area = bodyAt(profile, actual).minmax(bodyAt(profile, edge.to().feet()))
                .expandTowards(0, 1.25, 0);
        if (!loaded(area.inflate(1))) return Verdict.WAITING_CHUNK;
        ShapeScan scan = immediateScan(mob, area, actual.y);
        if (scan == null) return Verdict.UNKNOWN;
        try {
            TraversalEdge checked=localEdge(mob,node(actual),edge.to().feet(),profile,scan,
                    edge.action()==TraversalEdge.Action.STEP);
            return checked==null?Verdict.BLOCKED:
                    checked.action()==TraversalEdge.Action.NATIVE_INTERACTION?Verdict.NATIVE_INTERACTION:Verdict.CLEAR;
        } finally { scan.close(); }
    }

    /** Controller must cease extra lateral acceleration when relying on this residual-drag bound. */
    public boolean validateDropDrift(Mob mob, TraversalEdge edge, MovementProfile profile) {
        if (edge.action() != TraversalEdge.Action.DROP || edge.controlPoints().size() < 3) return false;
        Vec3 velocity = mob.getDeltaMovement();
        Vec3 departure = edge.controlPoints().get(1);
        if (mob.onGround()) return TraversalMath.residualDrift(velocity.x)<=DROP_DRIFT+EPSILON
                && TraversalMath.residualDrift(velocity.z)<=DROP_DRIFT+EPSILON;
        // Signed residual motion may move an off-centre departure INTO the proven envelope.
        // Checking |offset| + |drift| would incorrectly reject that common edge departure.
        double endX=mob.getX()+velocity.x/(1.0-0.91),endZ=mob.getZ()+velocity.z/(1.0-0.91);
        return Math.min(mob.getX(),endX)>=departure.x-DROP_DRIFT-EPSILON
                && Math.max(mob.getX(),endX)<=departure.x+DROP_DRIFT+EPSILON
                && Math.min(mob.getZ(),endZ)>=departure.z-DROP_DRIFT-EPSILON
                && Math.max(mob.getZ(),endZ)<=departure.z+DROP_DRIFT+EPSILON;
    }

    /** Coordinate extraction, support enumeration, and columns each retain their own cursor. */
    private void generateCandidateBatch(NeighborCursor cursor) {
        Vec3 origin = cursor.from.feet();
        double half = cursor.profile.width()/2;
        // With no fractional horizontal shape boundary, grid-centred surfaces are complete.
        // Keep edge departures, but avoid regenerating all of the native candidates just emitted.
        if (nativePoint(origin) && !cursor.local.partialHorizontal) {
            if (cursor.directionIndex<4) {
                if (maybeDeparture(cursor,DIRECTIONS[cursor.directionIndex])) cursor.directionIndex++;
            }
            else cursor.generated=true;
            return;
        }
        if (cursor.directionIndex>=DIRECTIONS.length) {
            if (!cursor.reserve(cursor.candidateSet.size())) return;
            cursor.candidates.addAll(cursor.candidateSet); cursor.generated=true; return;
        }
        int[] direction=DIRECTIONS[cursor.directionIndex];
        if (cursor.column==null) {
            CandidateColumn next=new CandidateColumn(origin,direction);
            if (!cursor.reserve(next.xs.size()+next.zs.size())) return;
            cursor.column=next;
        }
        CandidateColumn column=cursor.column;
        int cellX=column.cellX,cellZ=column.cellZ;
        Set<Double> xs=column.xs,zs=column.zs;
        if (!column.coordinatesDone) {
            int end=Math.min(cursor.local.boxes.size(),column.boxIndex+SHAPE_BATCH);
            while (column.boxIndex<end) {
                AABB box=cursor.local.boxes.get(column.boxIndex);
                Set<Double> newXs=new LinkedHashSet<>(),newZs=new LinkedHashSet<>();
                if (!(box.maxX<cellX-1 || box.minX>cellX+2 || box.maxZ<cellZ-1 || box.minZ>cellZ+2)
                        && !wholeBlock(box) && box.maxY>=origin.y-EPSILON
                        && box.minY<origin.y+cursor.profile.height()+EPSILON) {
                    if (!wholeAxis(box.minX,box.maxX)) {
                        addCoordinate(newXs,box.minX+half+EPSILON,cellX); addCoordinate(newXs,box.maxX-half-EPSILON,cellX);
                        addCoordinate(newXs,box.minX-half-EPSILON,cellX); addCoordinate(newXs,box.maxX+half+EPSILON,cellX);
                    }
                    if (!wholeAxis(box.minZ,box.maxZ)) {
                        addCoordinate(newZs,box.minZ+half+EPSILON,cellZ); addCoordinate(newZs,box.maxZ-half-EPSILON,cellZ);
                        addCoordinate(newZs,box.minZ-half-EPSILON,cellZ); addCoordinate(newZs,box.maxZ+half+EPSILON,cellZ);
                    }
                }
                newXs.removeAll(xs); newZs.removeAll(zs);
                if ((xs.size()+newXs.size())*(zs.size()+newZs.size())>256) { cursor.limited=true; return; }
                if (!cursor.reserve(newXs.size()+newZs.size())) return;
                xs.addAll(newXs); zs.addAll(newZs); column.boxIndex++;
            }
            if (column.boxIndex>=cursor.local.boxes.size()) { column.coordinatesDone=true; column.boxIndex=0; }
            return;
        }
        if (column.boxIndex<cursor.local.boxes.size()) {
            // At most sixteen support boxes and 256 coordinate/shape pairs in one work unit.
            int boxBudget=Math.min(SHAPE_BATCH,256/(xs.size()*zs.size()));
            int end=Math.min(cursor.local.boxes.size(),column.boxIndex+boxBudget);
            while (column.boxIndex<end) {
                AABB box=cursor.local.boxes.get(column.boxIndex);
                Set<Vec3> additions=new LinkedHashSet<>();
                if (box.maxY>=origin.y-1-EPSILON && box.maxY<=origin.y+cursor.profile.stepHeight()+EPSILON) {
                    for (double x : xs) for (double z : zs) {
                        if (x+half<=box.minX+EPSILON || x-half>=box.maxX-EPSILON
                                || z+half<=box.minZ+EPSILON || z-half>=box.maxZ-EPSILON) continue;
                        Vec3 feet=new Vec3(x,box.maxY,z);
                        if (!cursor.candidateSet.contains(feet)) additions.add(feet);
                    }
                }
                if (cursor.candidateSet.size()+additions.size()>MAX_CANDIDATES) { cursor.limited=true; return; }
                if (!cursor.reserve(additions.size())) return;
                cursor.candidateSet.addAll(additions); column.boxIndex++;
            }
            return;
        }
        if (!maybeDeparture(cursor,direction)) return;
        cursor.lease.release(column.xs.size()+column.zs.size());
        cursor.directionIndex++; cursor.column=null;
    }

    private boolean maybeDeparture(NeighborCursor cursor,int[] direction) {
        if (cursor.profile.allowDrops() && (direction[0]==0 || direction[1]==0)) {
            Vec3 origin=cursor.from.feet();
            Vec3 departure=new Vec3(Mth.floor(origin.x)+direction[0]+0.5,origin.y,
                    Mth.floor(origin.z)+direction[1]+0.5);
            if (inside(bodyAt(cursor.profile,departure).inflate(DROP_DRIFT,0,DROP_DRIFT))
                    && clearSweep(origin,departure,cursor.profile,cursor.local)
                    && !supported(departure,cursor.profile,cursor.local.boxes)) {
                List<TraversalMath.Interval> support=supportIntervals(origin,departure,cursor.profile,cursor.local.boxes);
                double prefix=TraversalMath.supportedPrefix(support);
                // Walking off the current platform cannot cross one gap and regain another platform.
                if (prefix>EPSILON && prefix<1-EPSILON
                        && support.stream().noneMatch(interval -> interval.start()>prefix+EPSILON)) {
                    if (!cursor.reserve(1)) return false;
                    cursor.departures.add(departure);
                }
            }
        }
        return true;
    }

    private static final class CandidateColumn {
        final int cellX,cellZ;
        final Set<Double> xs=new LinkedHashSet<>(),zs=new LinkedHashSet<>();
        int boxIndex;
        boolean coordinatesDone;
        CandidateColumn(Vec3 origin,int[] direction) {
            cellX=Mth.floor(origin.x)+direction[0]; cellZ=Mth.floor(origin.z)+direction[1];
            xs.add(cellX+0.5); zs.add(cellZ+0.5);
            if (direction[0]==0) xs.add(origin.x);
            if (direction[1]==0) zs.add(origin.z);
        }
    }

    private static void addCoordinate(Set<Double> values, double value, int cell) {
        if (value > cell+EPSILON && value < cell+1-EPSILON) values.add(value);
    }

    @Nullable
    private TraversalEdge localEdge(Mob mob, SurfaceNode from, Vec3 to, MovementProfile profile, ShapeScan scan) {
        return localEdge(mob,from,to,profile,scan,false);
    }

    @Nullable
    private TraversalEdge localEdge(Mob mob,SurfaceNode from,Vec3 to,MovementProfile profile,ShapeScan scan,
            boolean executionStep) {
        Vec3 start = from.feet();
        double dy = to.y-start.y;
        boolean interaction=Math.abs(dy)<=EPSILON && profile.canOpenDoors() && scan.doorAbility
                && crossesDoor(start,to,profile,scan);
        if (!standing(to,profile,scan,interaction) || start.distanceToSqr(to)<EPSILON*EPSILON) return null;
        TraversalEdge.Action action;
        List<Vec3> controls;
        if (Math.abs(dy) <= EPSILON) {
            if (!clearSweep(start,to,profile,scan,interaction)
                    || TraversalMath.supportedPrefix(supportIntervals(start,to,profile,scan.boxes)) < 1-EPSILON) return null;
            action = interaction?TraversalEdge.Action.NATIVE_INTERACTION:
                    nativePoint(start) && nativePoint(to) ? TraversalEdge.Action.WALK : TraversalEdge.Action.PRECISE_MOVE;
            controls = List.of(start,to);
        } else if (dy > 0 && dy <= profile.stepHeight()+EPSILON) {
            // Native step/jump has a vertical lift, a horizontal phase, then contact with the new floor.
            // It is NOT a diagonal translation of the old body. Ordinary full-block jumps need apex clearance.
            double rise = dy <= mob.maxUpStep()+EPSILON ? dy : Math.max(dy,1.25);
            Vec3 raised = start.add(0,rise,0), aboveEnd = new Vec3(to.x,start.y+rise,to.z);
            if (!clearSweep(start,raised,profile,scan) || !clearSweep(raised,aboveEnd,profile,scan)
                    || !clearSweep(aboveEnd,to,profile,scan)) return null;
            // Reserve the headroom above the traversed support as well as the three control segments.
            if (!clearSweep(new Vec3(start.x,to.y,start.z),to,profile,scan)) return null;
            if ((!executionStep && !nativePoint(start)) || !nativePoint(to)) return null;
            action = TraversalEdge.Action.STEP;
            controls = List.of(start,raised,aboveEnd,to);
        } else if (dy < 0 && dy >= -1-EPSILON) {
            Vec3 aboveEnd = new Vec3(to.x,start.y,to.z);
            if (!clearSweep(start,aboveEnd,profile,scan) || !clearSweep(aboveEnd,to,profile,scan)) return null;
            if ((!executionStep && !nativePoint(start)) || !nativePoint(to)) return null;
            action = TraversalEdge.Action.STEP;
            controls = List.of(start,aboveEnd,to);
        } else return null;
        double setupCost=action==TraversalEdge.Action.STEP?0.5:
                action==TraversalEdge.Action.PRECISE_MOVE?0.35:action==TraversalEdge.Action.NATIVE_INTERACTION?1:0;
        return new TraversalEdge(from,node(to),action,controls,start.distanceTo(to)+setupCost,scan.dependencies);
    }

    private final class DropCursor implements AutoCloseable {
        final Mob mob;
        final SurfaceNode from;
        final Vec3 departure;
        final MovementProfile profile;
        final TraversalMath.DropScanRange range;
        final boolean planningCache;
        final Map<NavigationGraphCache.TileKey,Long> dependencies = new HashMap<>();
        ShapeScan scan;
        boolean done,limited;
        private final NavigationGraphCache.TransientLease lease=cache.transientLease();
        private final Map<NavigationGraphCache.TileKey,Long> initial;
        @Override public void close() { if (scan!=null) scan.close(); lease.close(); dependencies.clear(); }
        DropCursor(Mob mob, SurfaceNode from, Vec3 departure, MovementProfile profile,
                Map<NavigationGraphCache.TileKey,Long> initial) {
            this(mob,from,departure,profile,initial,false);
        }
        DropCursor(Mob mob,SurfaceNode from,Vec3 departure,MovementProfile profile,
                Map<NavigationGraphCache.TileKey,Long> initial,boolean planningCache) {
            this.mob=mob; this.from=from; this.departure=departure; this.profile=profile;
            this.planningCache=planningCache;
            this.initial=initial;
            range = new TraversalMath.DropScanRange(from.feet().y,
                    Math.max(context.bounds().minY,context.level().getMinBuildHeight()));
        }
        @Nullable TraversalEdge advance() {
            limited=false;
            for (var entry : initial.entrySet()) if (!dependencies.containsKey(entry.getKey())) {
                if (!lease.reserve(1)) { limited=true; return null; }
                dependencies.put(entry.getKey(),entry.getValue());
            }
            if (range.done()) { done=true; return null; }
            Vec3 high = new Vec3(departure.x,range.top(),departure.z);
            Vec3 low = new Vec3(departure.x,range.bottom(),departure.z);
            if (scan == null) {
                scan = new ShapeScan(mob,bodyAt(profile,high).minmax(bodyAt(profile,low))
                        .inflate(DROP_DRIFT,0,DROP_DRIFT),high.y,true,planningCache?profile:null);
                return null;
            }
            if (!scan.done) { scan.advance(); return null; }
            for (var entry : scan.dependencies.entrySet()) if (!dependencies.containsKey(entry.getKey())) {
                if (!lease.reserve(1)) { limited=true; return null; }
                dependencies.put(entry.getKey(),entry.getValue());
            }
            double firstY = Double.NEGATIVE_INFINITY;
            AABB footprint = bodyAt(profile,low);
            for (AABB box : scan.boxes) {
                if (box.maxY <= high.y+EPSILON && box.maxY >= low.y-EPSILON
                        && overlapsHorizontal(footprint,box)) firstY=Math.max(firstY,box.maxY);
            }
            if (Double.isFinite(firstY)) {
                // Never skip an inconvenient first contact and choose a more attractive floor below it.
                done=true;
                Vec3 landing = new Vec3(departure.x,firstY,departure.z);
                if (from.feet().y-firstY < 1-EPSILON) return null;
                if (!clearDriftSweep(high,landing,profile,scan)) return null;
                for (double dx : new double[]{-DROP_DRIFT,0,DROP_DRIFT})
                    for (double dz : new double[]{-DROP_DRIFT,0,DROP_DRIFT})
                        if (!standing(landing.add(dx,0,dz),profile,scan)) return null;
                return new TraversalEdge(from,node(landing),TraversalEdge.Action.DROP,
                        List.of(from.feet(),departure,landing),from.feet().distanceTo(landing)+1,dependencies);
            }
            if (!clearDriftSweep(high,low,profile,scan)) { done=true; return null; }
            range.advance(); scan.close(); scan=null;
            return null;
        }
    }

    private boolean clearDriftSweep(Vec3 from, Vec3 to, MovementProfile profile, ShapeScan scan) {
        MovementProfile envelope = new MovementProfile(profile.width()+2*DROP_DRIFT,profile.height(),
                profile.stepHeight(),profile.canOpenDoors(),profile.canPassDoors(),profile.allowDrops(),profile.collisionContext());
        return clearSweep(from,to,envelope,scan);
    }

    private record CachedCell(List<AABB> boxes,BlockPathTypes type,boolean fluid) { }

    /** A work unit reads eight world cells or 128 memoized cells, processing at most sixteen boxes. */
    private final class ShapeScan implements AutoCloseable {
        final Mob mob;
        final AABB area;
        final double feetY;
        final List<AABB> boxes = new ArrayList<>();
        final List<AABB> hazards = new ArrayList<>();
        final Set<AABB> doors=new LinkedHashSet<>();
        final boolean doorAbility;
        final boolean retainGeometry;
        final MovementProfile cacheProfile;
        long fingerprint=0xcbf29ce484222325L;
        final Map<NavigationGraphCache.TileKey,Long> dependencies = new HashMap<>();
        final int minX,maxX,minY,maxY,minZ,maxZ;
        int x,y,z,boxIndex;
        List<AABB> pending = List.of();
        boolean done,waiting,limited,hardLimited,pendingDoor,partialHorizontal;
        private final NavigationGraphCache.TransientLease lease=cache.transientLease();
        private boolean reserve(int entries) {
            if (lease.reserve(entries)) return true; limited=true; return false;
        }
        @Override public void close() {
            lease.close(); boxes.clear(); hazards.clear(); doors.clear(); dependencies.clear(); pending=List.of();
        }
        void releaseGeometry() {
            lease.release(boxes.size()+hazards.size()+doors.size()+pending.size());
            boxes.clear();hazards.clear();doors.clear();pending=List.of();boxIndex=0;
        }
        ShapeScan(Mob mob,AABB area,double feetY) {
            this(mob,area,feetY,true);
        }
        ShapeScan(Mob mob,AABB area,double feetY,boolean retainGeometry) {
            this(mob,area,feetY,retainGeometry,null);
        }
        ShapeScan(Mob mob,AABB area,double feetY,boolean retainGeometry,MovementProfile cacheProfile) {
            this.mob=mob; this.area=area; this.feetY=feetY;
            this.retainGeometry=retainGeometry;
            this.cacheProfile=cacheProfile;
            doorAbility=mob.getNavigation() instanceof GroundPathNavigation ground && ground.getNodeEvaluator().canOpenDoors()
                    && mob.goalSelector.getAvailableGoals().stream().anyMatch(goal -> goal.getGoal() instanceof OpenDoorGoal);
            // The one-block read extension includes fences, moving shapes, and neighbour lookups.
            minX=Mth.floor(area.minX)-1; maxX=Mth.floor(area.maxX)+1;
            minY=Math.max(context.level().getMinBuildHeight(),Mth.floor(area.minY)-1);
            maxY=Math.min(context.level().getMaxBuildHeight()-1,Mth.floor(area.maxY)+1);
            minZ=Mth.floor(area.minZ)-1; maxZ=Mth.floor(area.maxZ)+1;
            x=minX;y=minY;z=minZ;
        }
        void advance() {
            waiting=false; limited=hardLimited;
            if (hardLimited) return;
            int blocks=0,shapes=0,cells=0;
            while (!done && blocks<BLOCK_BATCH && shapes<SHAPE_BATCH && cells<CACHED_CELL_BATCH) {
                if (boxIndex < pending.size()) {
                    AABB box=pending.get(boxIndex);
                    boolean included=box.intersects(area.inflate(EPSILON));
                    if (included && retainGeometry) {
                        if (boxes.size()>=MAX_LOCAL_BOXES) { hardLimited=limited=true; return; }
                        if (!reserve(1+(pendingDoor && !doors.contains(box)?1:0))) return;
                    }
                    boxIndex++; shapes++;
                    if (included) {
                        fingerprint=hashBox(fingerprint,box);
                        if (box.maxY>=feetY-EPSILON && box.minY<area.maxY
                                && (!wholeAxis(box.minX,box.maxX) || !wholeAxis(box.minZ,box.maxZ)))
                            partialHorizontal=true;
                        if (pendingDoor) fingerprint=(fingerprint^0x646f6f72L)*0x100000001b3L;
                        if (retainGeometry) { boxes.add(box); if (pendingDoor) doors.add(box); }
                    }
                    continue;
                }
                lease.release(pending.size()); pending=List.of(); boxIndex=0;
                if (y>maxY) { done=true; break; }
                BlockPos pos=new BlockPos(x,y,z);
                if (!loaded(new AABB(pos).inflate(1))) { waiting=true; return; }
                NavigationGraphCache.TileKey tile=NavigationGraphCache.TileKey.at(Vec3.atCenterOf(pos));
                long version=cache.version(tile);
                if (version<0) { limited=true; return; }
                if (!dependencies.containsKey(tile)) {
                    if (!reserve(1)) return;
                    dependencies.put(tile,version);
                }
                CachedCell cell=cacheProfile==null?null:(CachedCell)cache.geometryCell(cacheProfile,CONTEXT_INDEPENDENT_FEET,pos);
                if (cell==null && cacheProfile!=null) cell=(CachedCell)cache.geometryCell(cacheProfile,feetY,pos);
                if (cell==null) {
                    var state=context.level().getBlockState(pos);
                    BlockPathTypes type=WalkNodeEvaluator.getBlockPathTypeStatic(context.level(),pos.mutable());
                    VoxelShape shape=state.getCollisionShape(context.level(),pos,new ProjectedCollisionContext(mob,feetY));
                    List<AABB> shapeBoxes=shape.move(x,y,z).toAabbs();
                    // The third-party callback itself is indivisible; reject its oversized result
                    // before retaining it across a scheduler slice.
                    if (shapeBoxes.size()>MAX_LOCAL_BOXES) { hardLimited=limited=true; return; }
                    cell=new CachedCell(shapeBoxes,type,!state.getFluidState().isEmpty());
                    if (cacheProfile!=null && shapeBoxes.size()<=SHAPE_BATCH
                            && state.getBlock().getClass().getName().startsWith("net.minecraft.world.level.block.")) {
                        Class<?> blockClass=state.getBlock().getClass();
                        boolean independent=blockClass==net.minecraft.world.level.block.Block.class
                                || blockClass==net.minecraft.world.level.block.AirBlock.class;
                        cache.putGeometryCell(cacheProfile,independent?CONTEXT_INDEPENDENT_FEET:feetY,pos,cell);
                    }
                    blocks++;
                }
                boolean hazard=dangerous(cell.type()) || cell.fluid();
                if (!reserve(cell.boxes().size()+(hazard && retainGeometry?1:0))) return;
                cells++;
                BlockPathTypes type=cell.type();
                pendingDoor=doorAbility && type==BlockPathTypes.DOOR_WOOD_CLOSED;
                if (hazard) {
                    AABB hazardBox=new AABB(pos);
                    if (retainGeometry) hazards.add(hazardBox);
                    if (hazardBox.intersects(area.expandTowards(0,-0.01,0)))
                        fingerprint=(hashBox(fingerprint,hazardBox)^type.ordinal())*0x100000001b3L;
                }
                pending=cell.boxes(); boxIndex=0;
                if (++x>maxX) { x=minX; if (++z>maxZ) { z=minZ; y++; } }
            }
            if (y>maxY && boxIndex>=pending.size()) {
                lease.release(pending.size()); pending=List.of(); boxIndex=0; done=true;
            }
        }
    }

    private static final class ProjectedCollisionContext extends EntityCollisionContext {
        ProjectedCollisionContext(Mob mob,double feetY) {
            super(mob.isDescending(),feetY,mob.getMainHandItem(),mob::canStandOnFluid,mob);
        }
    }

    @Nullable private ShapeScan immediateScan(Mob mob,AABB area,double feetY) {
        if (!loaded(area.inflate(2))) return null;
        ShapeScan scan=new ShapeScan(mob,area,feetY);
        for (int batches=0;!scan.done && batches<128;batches++) {
            scan.advance(); if (scan.waiting || scan.limited) { scan.close(); return null; }
        }
        if (!scan.done) { scan.close(); return null; }
        return scan;
    }

    private boolean standing(Vec3 feet,MovementProfile profile,ShapeScan scan) {
        return standing(feet,profile,scan,false);
    }
    private boolean standing(Vec3 feet,MovementProfile profile,ShapeScan scan,boolean allowDoors) {
        return inside(bodyAt(profile,feet)) && supported(feet,profile,scan.boxes)
                && clearSweep(feet,feet,profile,scan,allowDoors);
    }

    private static boolean supported(Vec3 feet,MovementProfile profile,List<AABB> boxes) {
        AABB body=bodyAt(profile,feet);
        for (AABB box : boxes)
            if (Math.abs(box.maxY-feet.y)<=EPSILON && overlapsHorizontal(body,box)) return true;
        return false;
    }

    private boolean clearSweep(Vec3 from,Vec3 to,MovementProfile profile,ShapeScan scan) {
        return clearSweep(from,to,profile,scan,false);
    }
    private boolean clearSweep(Vec3 from,Vec3 to,MovementProfile profile,ShapeScan scan,boolean allowDoors) {
        AABB start=bodyAt(profile,from).deflate(EPSILON),end=bodyAt(profile,to).deflate(EPSILON);
        if (!inside(start.minmax(end))) return false;
        Vec3 center=start.getCenter(), finish=end.getCenter();
        double halfX=start.getXsize()/2,halfY=start.getYsize()/2,halfZ=start.getZsize()/2;
        for (AABB box : scan.boxes) {
            if (allowDoors && scan.doors.contains(box)) continue;
            if (start.intersects(box) || end.intersects(box)
                    || box.inflate(halfX,halfY,halfZ).clip(center,finish).isPresent()) return false;
        }
        AABB swept=start.minmax(end).expandTowards(0,-0.01,0);
        for (AABB hazard : scan.hazards) if (hazard.intersects(swept)) return false;
        return true;
    }

    private static boolean crossesDoor(Vec3 from,Vec3 to,MovementProfile profile,ShapeScan scan) {
        AABB start=bodyAt(profile,from).deflate(EPSILON),end=bodyAt(profile,to).deflate(EPSILON);
        for (AABB door : scan.doors)
            if (start.intersects(door) || end.intersects(door)
                    || door.inflate(start.getXsize()/2,start.getYsize()/2,start.getZsize()/2)
                    .clip(start.getCenter(),end.getCenter()).isPresent()) return true;
        return false;
    }

    private static List<TraversalMath.Interval> supportIntervals(Vec3 from,Vec3 to,
            MovementProfile profile,List<AABB> boxes) {
        List<TraversalMath.Interval> result=new ArrayList<>();
        double half=profile.width()/2-EPSILON;
        for (AABB box : boxes) if (Math.abs(box.maxY-from.y)<=EPSILON) {
            var interval=TraversalMath.overlap(from.x,from.z,to.x-from.x,to.z-from.z,
                    box.minX-half,box.maxX+half,box.minZ-half,box.maxZ+half);
            if (interval!=null) result.add(interval);
        }
        return result;
    }

    private static boolean dangerous(BlockPathTypes type) {
        return type==BlockPathTypes.LAVA || type==BlockPathTypes.WATER || type==BlockPathTypes.DAMAGE_FIRE
                || type==BlockPathTypes.DANGER_FIRE || type==BlockPathTypes.DAMAGE_OTHER
                || type==BlockPathTypes.DANGER_OTHER || type==BlockPathTypes.POWDER_SNOW
                || type==BlockPathTypes.DANGER_POWDER_SNOW || type==BlockPathTypes.DAMAGE_CAUTIOUS;
    }

    private static long hashBox(long hash,AABB box) {
        hash=(hash^Double.doubleToLongBits(box.minX))*0x100000001b3L;
        hash=(hash^Double.doubleToLongBits(box.minY))*0x100000001b3L;
        hash=(hash^Double.doubleToLongBits(box.minZ))*0x100000001b3L;
        hash=(hash^Double.doubleToLongBits(box.maxX))*0x100000001b3L;
        hash=(hash^Double.doubleToLongBits(box.maxY))*0x100000001b3L;
        return (hash^Double.doubleToLongBits(box.maxZ))*0x100000001b3L;
    }

    private SurfaceNode node(Vec3 feet) {
        return new SurfaceNode(feet,cache.version(NavigationGraphCache.TileKey.at(feet)));
    }
    private static boolean nativePoint(Vec3 p) {
        return Math.abs(p.x-Mth.floor(p.x)-0.5)<EPSILON && Math.abs(p.z-Mth.floor(p.z)-0.5)<EPSILON;
    }
    private static boolean wholeBlock(AABB box) {
        return Math.abs(box.getXsize()-1)<EPSILON && Math.abs(box.getYsize()-1)<EPSILON
                && Math.abs(box.getZsize()-1)<EPSILON && Math.abs(box.minX-Math.rint(box.minX))<EPSILON
                && Math.abs(box.minY-Math.rint(box.minY))<EPSILON && Math.abs(box.minZ-Math.rint(box.minZ))<EPSILON;
    }
    private static boolean wholeAxis(double min,double max) {
        return Math.abs(max-min-1)<EPSILON && Math.abs(min-Math.rint(min))<EPSILON;
    }
    public static AABB bodyAt(MovementProfile profile,Vec3 feet) {
        double half=profile.width()/2;
        return new AABB(feet.x-half,feet.y,feet.z-half,feet.x+half,feet.y+profile.height(),feet.z+half);
    }
    private boolean inside(AABB body) {
        AABB bounds=context.bounds();
        return body.minX>=bounds.minX-EPSILON && body.maxX<=bounds.maxX+EPSILON
                && body.minZ>=bounds.minZ-EPSILON && body.maxZ<=bounds.maxZ+EPSILON
                && body.minY>=Math.max(bounds.minY,context.level().getMinBuildHeight())-EPSILON
                && body.maxY<=Math.min(bounds.maxY,context.level().getMaxBuildHeight())+EPSILON;
    }
    private boolean loaded(AABB area) {
        for (int x=Mth.floor(area.minX)>>4;x<=Mth.floor(area.maxX)>>4;x++)
            for (int z=Mth.floor(area.minZ)>>4;z<=Mth.floor(area.maxZ)>>4;z++)
                if (!context.level().hasChunkAt(new BlockPos(x<<4,context.level().getMinBuildHeight(),z<<4))) return false;
        return true;
    }
    private static boolean overlapsHorizontal(AABB a,AABB b) {
        return a.maxX>b.minX+EPSILON && a.minX<b.maxX-EPSILON
                && a.maxZ>b.minZ+EPSILON && a.minZ<b.maxZ-EPSILON;
    }
    private static boolean finite(Vec3 p) { return p!=null && Double.isFinite(p.x+p.y+p.z); }
}
