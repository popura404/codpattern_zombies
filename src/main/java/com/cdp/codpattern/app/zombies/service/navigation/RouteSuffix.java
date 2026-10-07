package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Budgeted, independently owned suffix snapshot; never retains a chain of older plans. */
final class RouteSuffix implements AutoCloseable {
    enum Status { COPYING, READY, UNUSABLE }
    private RoutePlan original;
    private RoutePlan.Retention originalRetention;
    private final NavigationGraphCache cache;
    private final NavigationGraphCache.TransientLease memory;
    private final MovementProfile profile;
    private final Predicate<TraversalEdge> allowed;
    private final Vec3 target;
    private final int minimum;
    private int cursor;
    private final List<TraversalEdge> reverse = new ArrayList<>();
    private final Set<TraversalEdge> payload = new HashSet<>();
    private TraversalEdge checking;
    private Iterator<Map.Entry<NavigationGraphCache.TileKey, Long>> dependencies;
    private Status status = Status.COPYING;
    private boolean closed;
    private int verified;
    private long verificationRevision = Long.MIN_VALUE;

    RouteSuffix(RoutePlan plan, int firstUnconsumed, NavigationGraphCache cache, Predicate<TraversalEdge> allowed) {
        this.original = plan; originalRetention = plan.retain();
        this.cache = cache; this.allowed = allowed; profile = plan.profile(); target = plan.targetSnapshot();
        minimum = Math.max(0, firstUnconsumed + 1); cursor = plan.edges().size() - 1;
        memory = cache.transientLease();
        if (!memory.reserve(8)) unusable();
    }
    Status status() { return status; }
    int size() { return reverse.size(); }
    TraversalEdge edge(int index) { return reverse.get(reverse.size() - 1 - index); }
    SurfaceNode rejoin() { return edge(0).from(); }
    Vec3 target() { return target; }

    void advance(NavigationScheduler.Budget budget) {
        if (status != Status.COPYING) return;
        long started = System.nanoTime();
        try {
            while (cursor >= minimum) {
                if (checking == null) {
                    if (!budget.takeExpansion()) return;
                    checking = original.edges().get(cursor);
                    if (!usable(checking) || !reverse.isEmpty() && !checking.to().equals(reverse.get(reverse.size()-1).from())
                            || reverse.isEmpty() && checking.to().feet().distanceToSqr(target) > NavigationTuning.EPSILON * NavigationTuning.EPSILON) {
                        finish(); return;
                    }
                    dependencies = checking.dependencies().entrySet().iterator();
                }
                while (dependencies.hasNext()) {
                    if (!budget.takeExpansion()) return;
                    var dependency = dependencies.next();
                    if (!cache.valid(dependency.getKey(), dependency.getValue())) { finish(); return; }
                }
                if (!budget.takeExpansion()) return;
                boolean unique = !payload.contains(checking);
                if (!memory.reserve(1 + (unique ? NavigationGraphCache.edgeTransientEntries(checking) + 1 : 0))) {
                    unusable(); return;
                }
                payload.add(checking); reverse.add(checking); cursor--;
                checking = null; dependencies = null;
            }
            finish();
        } finally { budget.recordUnit(NavigationScheduler.UnitKind.RECONSTRUCT, started); }
    }
    private boolean usable(TraversalEdge edge) { return allowed.test(edge) && !cache.isFailed(edge, profile); }
    private void finish() {
        checking = null; dependencies = null;
        original = null; originalRetention.close(); originalRetention = null;
        if (reverse.isEmpty()) unusable();
        else status = Status.READY;
    }
    /** null means still checking; false means the old suffix is no longer proof of a route. */
    Boolean verify(NavigationScheduler.Budget budget) {
        if (status != Status.READY) return false;
        if (verificationRevision != cache.revision()) {
            verificationRevision = cache.revision(); verified = 0; checking = null; dependencies = null;
        }
        while (verified < size()) {
            if (checking == null) {
                if (!budget.takeExpansion()) return null;
                checking = edge(verified);
                if (!usable(checking)) return false;
                dependencies = checking.dependencies().entrySet().iterator();
            }
            while (dependencies.hasNext()) {
                if (!budget.takeExpansion()) return null;
                var dependency = dependencies.next();
                if (!cache.valid(dependency.getKey(), dependency.getValue())) return false;
            }
            checking = null; dependencies = null; verified++;
        }
        return true;
    }
    void transferPayload(TraversalEdge edge, NavigationGraphCache.TransientLease destination, boolean needed) {
        if (!payload.remove(edge)) return;
        int amount = NavigationGraphCache.edgeTransientEntries(edge);
        if (needed) {
            if (!memory.transferTo(destination, amount)) throw new IllegalStateException("Closed repair suffix transfer");
        } else memory.release(amount);
        memory.release(1);
    }
    private void unusable() { status = Status.UNUSABLE; close(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        original = null;
        if (originalRetention != null) { originalRetention.close(); originalRetention = null; }
        checking = null; dependencies = null; reverse.clear(); payload.clear(); memory.close();
    }
}
