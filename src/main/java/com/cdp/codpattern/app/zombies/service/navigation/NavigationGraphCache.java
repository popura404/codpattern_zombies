package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Room-local immutable adjacency cache, bounded version/failure metadata and tile LRU. */
public final class NavigationGraphCache {
    public record TileKey(int x, int y, int z) {
        public static TileKey at(BlockPos position) {
            return new TileKey(Math.floorDiv(position.getX(), NavigationTuning.TILE_SIZE),
                    Math.floorDiv(position.getY(), NavigationTuning.TILE_SIZE),
                    Math.floorDiv(position.getZ(), NavigationTuning.TILE_SIZE));
        }
        public static TileKey at(Vec3 feet) {
            return new TileKey((int) Math.floor(feet.x / NavigationTuning.TILE_SIZE),
                    (int) Math.floor(feet.y / NavigationTuning.TILE_SIZE),
                    (int) Math.floor(feet.z / NavigationTuning.TILE_SIZE));
        }
        public AABB bounds() {
            int size = NavigationTuning.TILE_SIZE;
            return new AABB((double) x * size, (double) y * size, (double) z * size,
                    ((double) x + 1) * size, ((double) y + 1) * size, ((double) z + 1) * size);
        }
    }
    public enum FailureEvidence { STATIC_COLLISION, DYNAMIC_CONGESTION, TARGET_CHANGED, EXTERNAL_DISPLACEMENT }
    private record NodeKey(MovementProfile profile, SurfaceNode node) { }
    private record FailureKey(MovementProfile profile, Vec3 from, Vec3 to,
            TraversalEdge.Action action, Map<TileKey, Long> versions) { }
    public record RegionId(TileKey tile, MovementProfile profile, SurfaceNode representative) { }
    /** A directed region-entry/exit connection with its already-refined immutable fine path. */
    public record PortalPath(SurfaceNode from, SurfaceNode to, List<TraversalEdge> edges,
            double cost, Map<TileKey, Long> dependencies) {
        public PortalPath { edges = List.copyOf(edges); dependencies = Map.copyOf(dependencies); }
    }
    private record PortalKey(MovementProfile profile, SurfaceNode from, SurfaceNode to) { }
    public record Stats(int tiles, int nodes, int edges, int failures, int pinnedTiles, long invalidations,
            int geometryCells, int portalPaths, int portalEdgeReferences, long invalidationEvents,
            String lastInvalidationReason, AABB lastInvalidationBounds, Map<String, Long> invalidationsByReason,
            int transientEntries, int peakTransientEntries, int transientLimit) { }
    private record GeometryKey(MovementProfile profile, double feetY, BlockPos position) { }
    private record GeometryValue(Object value, long observedTick) { }

    private final NavigationTuning tuning;
    private final TransientCapacity transientCapacity;
    private final TransientCapacity serverTransientCapacity;
    private final Set<TransientLease> transientLeases = new HashSet<>();
    private Consumer<TileKey> readObserver;
    private Set<TileKey> lastInvalidatedTiles = Set.of();
    private final LinkedHashMap<TileKey, TileData> tiles = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<TileKey, Long> versions = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<FailureKey, FailureRecord> failed = new LinkedHashMap<>(16, .75f, true);
    public record FailedEdge(TraversalEdge edge, MovementProfile profile) { }
    private static final class FailureRecord {
        final FailedEdge failure;
        Long fingerprint;
        TraversalValidator.ShapeEvidence evidence;
        long nextProbeTick;
        FailureRecord(TraversalEdge edge, MovementProfile profile) { failure = new FailedEdge(edge, profile); }
    }
    private final Map<UUID, Set<TileKey>> pins = new HashMap<>();
    private final LinkedHashMap<PortalKey, PortalPath> portalPaths = new LinkedHashMap<>(16, .75f, true);
    private final Map<NodeKey, Set<PortalKey>> portalIndex = new HashMap<>();
    private final Map<TileKey, Set<PortalKey>> portalDependencies = new HashMap<>();
    private int portalEdgeReferences;
    private final LinkedHashMap<GeometryKey, GeometryValue> geometryCells = new LinkedHashMap<>(16, .75f, true);
    private final Map<TileKey, Set<GeometryKey>> geometryIndex = new HashMap<>();
    private long sequence = 1, revision, invalidations;
    private long cacheHits;
    private long gameTime;
    private long invalidationEvents;
    private String lastInvalidationReason;
    private AABB lastInvalidationBounds;
    private final LinkedHashMap<String, Long> invalidationsByReason = new LinkedHashMap<>();
    private int nodeCount, edgeCount;
    public NavigationGraphCache() { this(NavigationTuning.DEFAULT); }
    public NavigationGraphCache(NavigationTuning tuning) { this(tuning, null); }
    public NavigationGraphCache(NavigationTuning tuning, TransientCapacity serverTransientCapacity) {
        this.tuning = tuning; this.serverTransientCapacity = serverTransientCapacity;
        transientCapacity = new TransientCapacity(tuning.surfaceNodes());
    }
    /** Counts retained cursor/search entries independently of the bounded resident graph. */
    public static final class TransientCapacity {
        private final int limit;
        private int used, peak;
        public TransientCapacity(int limit) { this.limit = Math.max(1, limit); }
        public int used() { return used; }
        public int peak() { return peak; }
        public int limit() { return limit; }
        private int available() { return limit - used; }
        private void add(int entries) { used += entries; peak = Math.max(peak, used); }
    }
    public final class TransientLease implements AutoCloseable {
        private final NavigationGraphCache owner = NavigationGraphCache.this;
        private int held;
        private boolean closed;
        public int available() {
            if (closed) return 0;
            return Math.min(transientCapacity.available(), serverTransientCapacity == null
                    ? Integer.MAX_VALUE : serverTransientCapacity.available());
        }
        public boolean reserve(int entries) {
            if (entries < 0) throw new IllegalArgumentException("Negative transient reservation");
            if (closed || entries > available()) return false;
            held += entries; transientCapacity.add(entries);
            if (serverTransientCapacity != null) serverTransientCapacity.add(entries);
            return true;
        }
        public void release(int entries) {
            if (closed) return; // A room reset can release leases before their abandoned cursors close.
            if (entries < 0 || entries > held) throw new IllegalArgumentException("Invalid transient release");
            held -= entries; transientCapacity.used -= entries;
            if (serverTransientCapacity != null) serverTransientCapacity.used -= entries;
        }
        /** Transfers already-accounted immutable output from a cursor to its consuming search. */
        public boolean transferTo(TransientLease destination, int entries) {
            if (destination.owner != owner) throw new IllegalArgumentException("Transient transfer crossed a room");
            if (closed || destination.closed) return false;
            if (entries < 0 || entries > held) throw new IllegalArgumentException("Invalid transient transfer");
            held -= entries; destination.held += entries; return true;
        }
        @Override public void close() {
            if (closed) return;
            release(held); closed = true; transientLeases.remove(this);
        }
    }
    public TransientLease transientLease() {
        TransientLease lease = new TransientLease(); transientLeases.add(lease); return lease;
    }
    public static int edgeTransientEntries(TraversalEdge edge) {
        return 4 + edge.dependencies().size() + edge.controlPoints().size();
    }
    public Consumer<TileKey> observeReads(Consumer<TileKey> observer) {
        Consumer<TileKey> previous = readObserver; readObserver = observer; return previous;
    }
    private void read(TileKey tile) { if (readObserver != null) readObserver.accept(tile); }
    public Set<TileKey> lastInvalidatedTiles() { return lastInvalidatedTiles; }
    public long revision() { return revision; }
    public long cacheHits() { return cacheHits; }
    public void updateTime(long gameTime) { this.gameTime = gameTime; }
    public Object geometryCell(MovementProfile profile, double feetY, BlockPos position) {
        if (!tiles.containsKey(TileKey.at(position))) return null;
        tiles.get(TileKey.at(position)); // Touch the same resident tile LRU as fine adjacency.
        GeometryKey key = new GeometryKey(profile, feetY, position.immutable());
        GeometryValue cached = geometryCells.get(key);
        if (cached == null) return null;
        if (gameTime - cached.observedTick() >= 40) { removeGeometryCell(key); return null; }
        return cached.value();
    }
    public boolean putGeometryCell(MovementProfile profile, double feetY, BlockPos position, Object value) {
        TileKey tile = TileKey.at(position);
        if (version(tile) < 0) return false;
        if (!tiles.containsKey(tile)) {
            Set<TileKey> protectedTiles = pinned(); protectedTiles.add(tile);
            while (tiles.size() >= tuning.residentTiles()) if (!evictOne(protectedTiles)) return false;
            tiles.put(tile, new TileData());
        }
        GeometryKey key = new GeometryKey(profile, feetY, position.immutable());
        int limit = Math.max(1, tuning.surfaceNodes() / 8);
        while (!geometryCells.containsKey(key) && geometryCells.size() >= limit)
            removeGeometryCell(geometryCells.keySet().iterator().next());
        geometryCells.put(key, new GeometryValue(value, gameTime));
        geometryIndex.computeIfAbsent(tile, ignored -> new HashSet<>()).add(key);
        return true;
    }
    private void removeGeometryCell(GeometryKey key) {
        geometryCells.remove(key);
        TileKey tile = TileKey.at(key.position());
        Set<GeometryKey> keys = geometryIndex.get(tile);
        if (keys != null) { keys.remove(key); if (keys.isEmpty()) geometryIndex.remove(tile); }
    }

    public long version(TileKey key) {
        read(key);
        Long current = versions.get(key);
        if (current != null) return current;
        long created = sequence++;
        versions.put(key, created);
        trimVersions();
        return versions.containsKey(key) ? created : -1L;
    }
    public boolean valid(Map<TileKey, Long> dependencies) {
        for (Map.Entry<TileKey, Long> entry : dependencies.entrySet()) {
            read(entry.getKey());
            if (!entry.getValue().equals(versions.get(entry.getKey()))) return false;
        }
        return true;
    }
    public boolean valid(TraversalEdge edge) { return valid(edge.dependencies()); }
    /** One dependency check, without creating or retaining a missing version. */
    public boolean valid(TileKey tile, long expected) {
        read(tile);
        return Long.valueOf(expected).equals(versions.get(tile));
    }

    /** Null means unbuilt/stale, not a wall. A nonnull empty list is complete adjacency. */
    public List<TraversalEdge> edges(SurfaceNode node, MovementProfile profile) {
        read(node.tile());
        TileData tile = tiles.get(node.tile());
        if (tile == null) return null;
        NodeKey key = new NodeKey(profile, node);
        List<TraversalEdge> result = tile.adjacency.get(key);
        if (result == null) return null;
        if (gameTime - tile.observed.getOrDefault(key, gameTime) >= 40) { removeNode(tile, key); return null; }
        for (TraversalEdge edge : result) {
            if (!valid(edge)) { removeNode(tile, key); return null; }
        }
        cacheHits++;
        return result.stream().filter(e -> !isFailed(e, profile)).toList();
    }
    /** Publishes only a completed scan; active cursors stay in the planner. */
    public boolean putEdges(SurfaceNode node, MovementProfile profile, List<TraversalEdge> edges) {
        if (version(node.tile()) != node.version() || edges.stream().anyMatch(e -> !valid(e))) return false;
        NodeKey key = new NodeKey(profile, node);
        TileData tile = tiles.get(node.tile());
        List<TraversalEdge> old = tile == null ? null : tile.adjacency.get(key);
        int newNodes = old == null ? 1 : 0;
        int newEdges = edges.size() - (old == null ? 0 : old.size());
        Set<TileKey> protectedTiles = new HashSet<>(pinned()); protectedTiles.add(node.tile());
        while ((tile == null && tiles.size() >= tuning.residentTiles())
                || nodeCount + newNodes > tuning.surfaceNodes()
                || edgeCount + newEdges > tuning.fineEdges() - tuning.fineEdges() / 4) {
            if (!evictOne(protectedTiles)) return false;
        }
        if (tile == null) { tile = new TileData(); tiles.put(node.tile(), tile); }
        tile.adjacency.put(key, List.copyOf(edges));
        tile.observed.put(key, gameTime);
        nodeCount += newNodes; edgeCount += newEdges;
        tile.parents.putIfAbsent(key, key);
        // Only demonstrated reverse non-drop/non-interaction connections merge a local region.
        for (TraversalEdge edge : edges) {
            if (!bidirectionalAction(edge.action()) || !edge.to().tile().equals(node.tile())) continue;
            NodeKey other = new NodeKey(profile, edge.to());
            List<TraversalEdge> reverse = tile.adjacency.get(other);
            if (reverse != null && reverse.stream().anyMatch(e -> e.to().equals(node)
                    && bidirectionalAction(e.action()))) union(tile, key, other);
        }
        return true;
    }
    public RegionId region(SurfaceNode node, MovementProfile profile) {
        TileData tile = tiles.get(node.tile());
        NodeKey key = new NodeKey(profile, node);
        NodeKey root = tile == null ? key : root(tile, key);
        return new RegionId(node.tile(), profile, root.node());
    }
    public List<PortalPath> portalPaths(SurfaceNode from, MovementProfile profile) {
        Set<PortalKey> keys = portalIndex.get(new NodeKey(profile, from));
        if (keys == null) return List.of();
        List<PortalPath> paths = new ArrayList<>();
        for (PortalKey key : Set.copyOf(keys)) {
            PortalPath path = portalPaths.get(key);
            if (path == null || !valid(path.dependencies()) || path.edges().stream().anyMatch(e -> isFailed(e, profile))) {
                removePortal(key); continue;
            }
            paths.add(path); cacheHits++;
        }
        return List.copyOf(paths);
    }
    /** Learn directed tile-entry/exit paths only from a proven fine route, never from proximity. */
    public void rememberRoute(RoutePlan route) {
        List<TraversalEdge> segment = new ArrayList<>();
        for (TraversalEdge edge : route.edges()) {
            if (!segment.isEmpty() && (!segment.get(0).from().tile().equals(edge.from().tile())
                    || edge.action() == TraversalEdge.Action.DROP || edge.action() == TraversalEdge.Action.NATIVE_INTERACTION)) {
                rememberPortal(route.profile(), segment); segment.clear();
            }
            segment.add(edge);
            if (!edge.from().tile().equals(edge.to().tile()) || edge.action() == TraversalEdge.Action.DROP
                    || edge.action() == TraversalEdge.Action.NATIVE_INTERACTION) {
                rememberPortal(route.profile(), segment); segment.clear();
            }
        }
        if (!segment.isEmpty()) rememberPortal(route.profile(), segment);
    }
    private void rememberPortal(MovementProfile profile, List<TraversalEdge> edges) {
        if (edges.size() < 2) return;
        Map<TileKey, Long> dependencies = new HashMap<>();
        double cost = 0;
        for (TraversalEdge edge : edges) { dependencies.putAll(edge.dependencies()); cost += edge.cost(); }
        if (!valid(dependencies)) return;
        PortalKey key = new PortalKey(profile, edges.get(0).from(), edges.get(edges.size() - 1).to());
        PortalPath old = portalPaths.get(key);
        if (old != null && old.cost() <= cost && valid(old.dependencies())) return;
        removePortal(key);
        int limit = Math.max(1, tuning.fineEdges() / 4);
        if (edges.size() > limit) return;
        while (portalEdgeReferences + edges.size() > limit || portalPaths.size() >= tuning.surfaceNodes())
            removePortal(portalPaths.keySet().iterator().next());
        portalPaths.put(key, new PortalPath(key.from(), key.to(), edges, cost, dependencies));
        portalIndex.computeIfAbsent(new NodeKey(profile, key.from()), ignored -> new HashSet<>()).add(key);
        for (TileKey dependency : dependencies.keySet())
            portalDependencies.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(key);
        portalEdgeReferences += edges.size();
    }
    /** Called by resumable route refinement at a tile boundary. */
    public void rememberSegment(MovementProfile profile, List<TraversalEdge> edges) {
        rememberPortal(profile, edges);
    }
    private void removePortal(PortalKey key) {
        PortalPath removed = portalPaths.remove(key);
        if (removed == null) return;
        portalEdgeReferences -= removed.edges().size();
        NodeKey index = new NodeKey(key.profile(), key.from());
        Set<PortalKey> keys = portalIndex.get(index);
        if (keys != null) { keys.remove(key); if (keys.isEmpty()) portalIndex.remove(index); }
        for (TileKey dependency : removed.dependencies().keySet()) {
            Set<PortalKey> dependents = portalDependencies.get(dependency);
            if (dependents != null) { dependents.remove(key); if (dependents.isEmpty()) portalDependencies.remove(dependency); }
        }
    }
    private static boolean bidirectionalAction(TraversalEdge.Action action) {
        return action == TraversalEdge.Action.WALK || action == TraversalEdge.Action.STEP
                || action == TraversalEdge.Action.PRECISE_MOVE;
    }
    private static NodeKey root(TileData tile, NodeKey key) {
        NodeKey cursor = key;
        while (tile.parents.containsKey(cursor) && !tile.parents.get(cursor).equals(cursor)) cursor = tile.parents.get(cursor);
        if (tile.parents.containsKey(key)) tile.parents.put(key, cursor);
        return cursor;
    }
    private static void union(TileData tile, NodeKey first, NodeKey second) {
        NodeKey a = root(tile, first), b = root(tile, second);
        if (!a.equals(b)) tile.parents.put(b, a);
    }

    public boolean isFailed(TraversalEdge edge, MovementProfile profile) {
        return failed.get(failureKey(edge, profile)) != null;
    }
    public void reportExecutionFailure(TraversalEdge edge, MovementProfile profile, FailureEvidence evidence) {
        if (evidence != FailureEvidence.STATIC_COLLISION || !valid(edge)) return;
        if (failed.putIfAbsent(failureKey(edge, profile), new FailureRecord(edge, profile)) == null) revision++;
        // A confirmed blocked connection can split a formerly bidirectional local component.
        for (TileKey dependency : edge.dependencies().keySet()) {
            TileData tile = tiles.get(dependency);
            if (tile != null) tile.parents.clear();
        }
        Set<PortalKey> possiblyFailed = new HashSet<>();
        for (TileKey dependency : edge.dependencies().keySet())
            possiblyFailed.addAll(portalDependencies.getOrDefault(dependency, Set.of()));
        for (PortalKey key : possiblyFailed) {
            PortalPath path = portalPaths.get(key);
            if (key.profile().equals(profile) && path.edges().contains(edge)) removePortal(key);
        }
        while (failed.size() > tuning.failedEdges()) failed.remove(failed.keySet().iterator().next());
        revision++;
    }
    public boolean hasFailures(MovementProfile profile) {
        return failed.keySet().stream().anyMatch(key -> key.profile().equals(profile));
    }
    public void setFailureBaseline(TraversalEdge edge, MovementProfile profile, TraversalValidator.ShapeEvidence evidence) {
        FailureRecord record = failed.get(failureKey(edge, profile));
        if (record != null && evidence != null && record.evidence == null) {
            record.evidence = evidence; record.fingerprint = evidence.fingerprint();
        }
    }
    public TraversalValidator.ShapeEvidence failureEvidence(FailedEdge failure) {
        FailureRecord record = failed.get(failureKey(failure.edge(), failure.profile()));
        return record == null ? null : record.evidence;
    }
    /** Claims a due probe without clearing its failure evidence. */
    public FailedEdge nextFailureToProbe(MovementProfile profile, long gameTime) {
        for (FailureRecord record : failed.values()) {
            if (record.failure.profile().equals(profile) && gameTime >= record.nextProbeTick) {
                record.nextProbeTick = gameTime + 40;
                return record.failure;
            }
        }
        return null;
    }
    /** Only observed collision-shape change, never elapsed time, releases static failure evidence. */
    public boolean observeFailureShape(FailedEdge failure, long fingerprint, long gameTime) {
        FailureRecord record = failed.get(failureKey(failure.edge(), failure.profile()));
        if (record == null) return false;
        record.nextProbeTick = gameTime + 40;
        if (record.fingerprint == null) { record.fingerprint = fingerprint; return false; }
        return record.fingerprint.longValue() != fingerprint;
    }
    private static FailureKey failureKey(TraversalEdge edge, MovementProfile profile) {
        return new FailureKey(profile, edge.from().feet(), edge.to().feet(), edge.action(), edge.dependencies());
    }
    /** Include neighboring shapes without enumerating unbuilt world volume. */
    public boolean invalidate(AABB bounds, String reason) {
        invalidationEvents++; lastInvalidationReason = reason; lastInvalidationBounds = bounds;
        invalidationsByReason.merge(reason == null ? "unspecified" : reason, 1L, Long::sum);
        while (invalidationsByReason.size() > 16) invalidationsByReason.remove(invalidationsByReason.keySet().iterator().next());
        AABB affected = bounds.inflate(1.0);
        Set<TileKey> touched = new HashSet<>();
        for (TileKey tile : new ArrayList<>(versions.keySet())) {
            if (tile.bounds().intersects(affected)) { versions.put(tile, sequence++); touched.add(tile); }
        }
        lastInvalidatedTiles = Set.copyOf(touched);
        if (touched.isEmpty()) return false;
        for (TileKey key : touched) removeTile(key);
        // Other tiles' adjacency validates dependencies lazily; do not scan every fine edge here.
        failed.keySet().removeIf(f -> f.versions().keySet().stream().anyMatch(touched::contains));
        Set<PortalKey> obsoletePortals = new HashSet<>();
        for (TileKey key : touched) obsoletePortals.addAll(portalDependencies.getOrDefault(key, Set.of()));
        obsoletePortals.forEach(this::removePortal);
        revision++; invalidations++;
        return true;
    }
    /** Pins only the current edge, the immediately following edge and their landing evidence. */
    public void pin(UUID mobId, List<TraversalEdge> edges, int currentIndex) {
        Set<TileKey> needed = new HashSet<>();
        for (int i = Math.max(0, currentIndex); i < edges.size() && i < currentIndex + 2; i++)
            needed.addAll(edges.get(i).dependencies().keySet());
        if (needed.isEmpty()) pins.remove(mobId); else pins.put(mobId, Set.copyOf(needed));
    }
    public void unpin(UUID mobId) { pins.remove(mobId); }
    private Set<TileKey> pinned() {
        Set<TileKey> result = new HashSet<>(); pins.values().forEach(result::addAll); return result;
    }
    private boolean evictOne(Set<TileKey> protectedTiles) {
        for (TileKey key : new ArrayList<>(tiles.keySet())) {
            if (!protectedTiles.contains(key)) { removeTile(key); return true; }
        }
        return false;
    }
    private void trimVersions() {
        int limit = tuning.residentTiles() * 4;
        if (versions.size() <= limit) return;
        Set<TileKey> protectedTiles = pinned();
        Iterator<TileKey> iterator = versions.keySet().iterator();
        boolean removed=false;
        while (versions.size() > limit && iterator.hasNext()) {
            TileKey key = iterator.next();
            if (protectedTiles.contains(key)) continue;
            iterator.remove(); removeTile(key);removed=true;
            failed.keySet().removeIf(f -> f.versions().containsKey(key));
            for (PortalKey portal : Set.copyOf(portalDependencies.getOrDefault(key, Set.of()))) removePortal(portal);
        }
        if (removed) revision++;
    }
    private void removeNode(TileData tile, NodeKey key) {
        List<TraversalEdge> removed = tile.adjacency.remove(key);
        if (removed != null) { nodeCount--; edgeCount -= removed.size(); tile.parents.clear(); tile.observed.remove(key); }
    }
    private void removeTile(TileKey key) {
        TileData tile = tiles.remove(key);
        for (GeometryKey geometry : Set.copyOf(geometryIndex.getOrDefault(key, Set.of()))) removeGeometryCell(geometry);
        if (tile == null) return;
        nodeCount -= tile.adjacency.size(); edgeCount -= tile.adjacency.values().stream().mapToInt(List::size).sum();
    }
    public Stats stats() { return new Stats(tiles.size(), nodeCount, edgeCount, failed.size(), pinned().size(), invalidations,
            geometryCells.size(), portalPaths.size(), portalEdgeReferences, invalidationEvents,
            lastInvalidationReason, lastInvalidationBounds, Map.copyOf(invalidationsByReason),
            transientCapacity.used(), transientCapacity.peak(), transientCapacity.limit()); }
    public void reset() {
        tiles.clear(); versions.clear(); failed.clear(); pins.clear(); nodeCount = 0; edgeCount = 0; revision++;
        portalPaths.clear(); portalIndex.clear(); portalDependencies.clear(); portalEdgeReferences = 0;
        geometryCells.clear(); geometryIndex.clear();
        for (TransientLease lease : Set.copyOf(transientLeases)) lease.close();
        lastInvalidatedTiles = Set.of(); readObserver = null;
    }
    private static final class TileData {
        final Map<NodeKey, List<TraversalEdge>> adjacency = new HashMap<>();
        final Map<NodeKey, NodeKey> parents = new HashMap<>();
        final Map<NodeKey, Long> observed = new HashMap<>();
    }
}
