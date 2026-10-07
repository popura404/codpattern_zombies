package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Server-thread-only, persistent round-robin queue across dimensions, rooms and entities. */
public final class NavigationScheduler {
    private static final Map<MinecraftServer, NavigationScheduler> SERVERS = new WeakHashMap<>();
    private final NavigationTuning tuning;
    private final NavigationGraphCache.TransientCapacity transientCapacity;
    private final Map<UUID, RoomQueue> rooms = new LinkedHashMap<>();
    private final ArrayDeque<UUID> rotation = new ArrayDeque<>();
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;
    private Metrics lastMetrics = new Metrics(0, 0, 0, 0);
    private long maxSliceNanos, exceededSlices, geometryOverruns, searchOverruns, reconstructionOverruns;
    private long maxGeometryUnitNanos, maxSearchUnitNanos, maxReconstructionUnitNanos;

    public NavigationScheduler(NavigationTuning tuning) {
        this.tuning = tuning;
        transientCapacity = new NavigationGraphCache.TransientCapacity(Math.multiplyExact(tuning.surfaceNodes(), 4));
    }
    public NavigationGraphCache.TransientCapacity transientCapacity() { return transientCapacity; }
    public static NavigationScheduler forServer(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new NavigationScheduler(NavigationTuning.DEFAULT));
    }
    /** Server-thread observation only; null means no scheduler exists and never creates one. */
    public static NavigationScheduler existingForServer(MinecraftServer server) {
        return SERVERS.get(server);
    }
    public static void stopServer(MinecraftServer server) {
        NavigationScheduler scheduler = SERVERS.remove(server);
        if (scheduler != null) scheduler.reset();
    }
    public interface Work {
        void advance(Budget budget);
        boolean finished();
    }
    public record Metrics(int expansions, int geometry, int slices, long elapsedNanos) { }
    public enum UnitKind { GEOMETRY, SEARCH, RECONSTRUCT }
    public record Timing(long maxSliceNanos, long exceededSlices, long geometryOverruns,
            long searchOverruns, long reconstructionOverruns, long maxGeometryUnitNanos,
            long maxSearchUnitNanos, long maxReconstructionUnitNanos,
            int transientEntries, int peakTransientEntries, int transientLimit) { }
    public Timing timing() { return new Timing(maxSliceNanos, exceededSlices, geometryOverruns,
            searchOverruns, reconstructionOverruns, maxGeometryUnitNanos, maxSearchUnitNanos,
            maxReconstructionUnitNanos, transientCapacity.used(), transientCapacity.peak(), transientCapacity.limit()); }
    public Metrics metrics() { return lastMetrics; }
    public long lastAdvancedTick() { return lastTick; }
    public int roomCount() { return rooms.size(); }
    public int registeredRoomCount() { return registrations.size(); }
    public int jobCount() { return rooms.values().stream().mapToInt(r -> r.jobs.size()).sum(); }
    public void register(UUID lifecycleId, ServerLevel level, BiConsumer<AABB, String> invalidation, Runnable prune) {
        register(lifecycleId, level, invalidation, prune, () -> { });
    }
    public void register(UUID lifecycleId, ServerLevel level, BiConsumer<AABB, String> invalidation, Runnable prune, Runnable stop) {
        Registration previous = registrations.get(lifecycleId);
        if (previous != null) previous.stop.run();
        registrations.put(lifecycleId, new Registration(level, invalidation, prune, stop));
    }
    public void unregister(UUID lifecycleId) { registrations.remove(lifecycleId); removeRoom(lifecycleId); }
    public void invalidate(ServerLevel level, AABB bounds, String reason) {
        for (Registration registration : registrations.values())
            if (registration.level == level) registration.invalidation.accept(bounds, reason);
    }

    public void submit(UUID roomId, UUID mobId, Work work) {
        RoomQueue room = rooms.get(roomId);
        if (room == null) {
            room = new RoomQueue(); rooms.put(roomId, room); rotation.addLast(roomId);
        }
        if (!room.jobs.containsKey(mobId)) room.rotation.addLast(mobId);
        room.jobs.put(mobId, work);
    }
    public void cancel(UUID roomId, UUID mobId) {
        RoomQueue room = rooms.get(roomId);
        if (room == null) return;
        room.jobs.remove(mobId); room.rotation.remove(mobId);
        if (room.jobs.isEmpty()) removeRoom(roomId);
    }
    public void removeRoom(UUID roomId) { rooms.remove(roomId); rotation.remove(roomId); }
    public void reset() {
        for (Registration registration : java.util.List.copyOf(registrations.values())) registration.stop.run();
        rooms.clear(); rotation.clear(); registrations.clear(); lastTick = Long.MIN_VALUE;
    }

    /** Called once from the END server tick, not once per dimension or monster. */
    public void advance(long serverTick) {
        if (serverTick == lastTick) return;
        lastTick = serverTick;
        long started = System.nanoTime();
        long deadline = started + tuning.serverNanos();
        for (Registration registration : java.util.List.copyOf(registrations.values())) registration.prune.run();
        int expansions = 0, geometry = 0, slices = 0, idle = 0;
        Map<UUID, Usage> usage = new HashMap<>();
        while (!rotation.isEmpty() && System.nanoTime() < deadline
                && expansions < tuning.serverExpansions() && geometry < tuning.serverGeometry()) {
            UUID roomId = rotation.removeFirst();
            RoomQueue room = rooms.get(roomId);
            if (room == null || room.rotation.isEmpty()) { rooms.remove(roomId); continue; }
            rotation.addLast(roomId);
            Usage used = usage.computeIfAbsent(roomId, ignored -> new Usage());
            if (used.expansions >= tuning.roomExpansions() || used.geometry >= tuning.roomGeometry()
                    || used.nanos >= tuning.roomNanos()) {
                if (++idle >= rotation.size()) break;
                continue;
            }
            UUID mobId = room.rotation.removeFirst();
            Work work = room.jobs.get(mobId);
            if (work == null || work.finished()) {
                room.jobs.remove(mobId);
                if (room.jobs.isEmpty()) removeRoom(roomId);
                continue;
            }
            room.rotation.addLast(mobId);
            long sliceStart = System.nanoTime();
            Budget budget = new Budget(Math.min(deadline, sliceStart + tuning.roomNanos() - used.nanos),
                    Math.min(tuning.sliceExpansions(), Math.min(tuning.roomExpansions() - used.expansions,
                            tuning.serverExpansions() - expansions)),
                    Math.min(tuning.sliceGeometry(), Math.min(tuning.roomGeometry() - used.geometry,
                            tuning.serverGeometry() - geometry)), serverTick);
            work.advance(budget);
            long elapsed = System.nanoTime() - sliceStart;
            maxSliceNanos = Math.max(maxSliceNanos, elapsed);
            if (sliceStart + elapsed > budget.deadlineNanos) exceededSlices++;
            geometryOverruns += budget.geometryOverruns; searchOverruns += budget.searchOverruns;
            reconstructionOverruns += budget.reconstructionOverruns;
            maxGeometryUnitNanos = Math.max(maxGeometryUnitNanos, budget.maxGeometryUnitNanos);
            maxSearchUnitNanos = Math.max(maxSearchUnitNanos, budget.maxSearchUnitNanos);
            maxReconstructionUnitNanos = Math.max(maxReconstructionUnitNanos, budget.maxReconstructionUnitNanos);
            used.nanos += elapsed; used.expansions += budget.expansionsUsed; used.geometry += budget.geometryUsed;
            expansions += budget.expansionsUsed; geometry += budget.geometryUsed; slices++;
            if (work.finished()) cancel(roomId, mobId);
            if (budget.expansionsUsed == 0 && budget.geometryUsed == 0) {
                if (++idle >= Math.max(1, jobCount())) break;
            } else idle = 0;
        }
        lastMetrics = new Metrics(expansions, geometry, slices, System.nanoTime() - started);
    }
    private static final class RoomQueue {
        final Map<UUID, Work> jobs = new HashMap<>();
        final ArrayDeque<UUID> rotation = new ArrayDeque<>();
    }
    private record Registration(ServerLevel level, BiConsumer<AABB, String> invalidation, Runnable prune, Runnable stop) { }
    private static final class Usage { int expansions; int geometry; long nanos; }

    public static final class Budget {
        private final long deadlineNanos;
        private final int expansionLimit, geometryLimit;
        private final long serverTick;
        private int expansionsUsed, geometryUsed;
        private long geometryOverruns, searchOverruns, reconstructionOverruns;
        private long maxGeometryUnitNanos, maxSearchUnitNanos, maxReconstructionUnitNanos;
        public Budget(long deadlineNanos, int expansionLimit, int geometryLimit) {
            this(deadlineNanos, expansionLimit, geometryLimit, 0L);
        }
        public Budget(long deadlineNanos, int expansionLimit, int geometryLimit, long serverTick) {
            this.deadlineNanos = deadlineNanos; this.expansionLimit = Math.max(0, expansionLimit);
            this.geometryLimit = Math.max(0, geometryLimit);
            this.serverTick = serverTick;
        }
        public long serverTick() { return serverTick; }
        public void recordUnit(UnitKind kind, long startedNanos) {
            long finished = System.nanoTime(), elapsed = Math.max(0, finished - startedNanos);
            boolean overrun = startedNanos < deadlineNanos && finished > deadlineNanos;
            switch (kind) {
                case GEOMETRY -> { maxGeometryUnitNanos = Math.max(maxGeometryUnitNanos, elapsed); if (overrun) geometryOverruns++; }
                case SEARCH -> { maxSearchUnitNanos = Math.max(maxSearchUnitNanos, elapsed); if (overrun) searchOverruns++; }
                case RECONSTRUCT -> { maxReconstructionUnitNanos = Math.max(maxReconstructionUnitNanos, elapsed); if (overrun) reconstructionOverruns++; }
            }
        }
        public long deadlineNanos() { return deadlineNanos; }
        public boolean hasTime() { return System.nanoTime() < deadlineNanos; }
        public int geometryRemaining() { return hasTime() ? geometryLimit - geometryUsed : 0; }
        public boolean takeExpansion() {
            if (!hasTime() || expansionsUsed >= expansionLimit) return false;
            expansionsUsed++; return true;
        }
        public void accountGeometry(int amount) {
            if (amount < 0 || geometryUsed + amount > geometryLimit)
                throw new IllegalArgumentException("Geometry exceeded its granted work budget");
            geometryUsed += amount;
        }
        public int expansionsUsed() { return expansionsUsed; }
        public int geometryUsed() { return geometryUsed; }
    }
}
