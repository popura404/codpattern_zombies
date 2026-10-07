package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared immutable route data. Every entity owns its own execution cursor and native Path. */
public record RoutePlan(UUID id, UUID targetId, Vec3 targetSnapshot, MovementProfile profile,
        List<TraversalEdge> edges, List<SurfaceNode> portals,
        Map<NavigationGraphCache.TileKey, Long> dependencies, Reservation reservation) {
    public RoutePlan(UUID id, UUID targetId, Vec3 targetSnapshot, MovementProfile profile,
            List<TraversalEdge> edges, List<SurfaceNode> portals,
            Map<NavigationGraphCache.TileKey, Long> dependencies) {
        this(id, targetId, targetSnapshot, profile, edges, portals, dependencies, new Reservation(null));
    }
    public RoutePlan {
        Objects.requireNonNull(id); Objects.requireNonNull(targetId); Objects.requireNonNull(targetSnapshot);
        Objects.requireNonNull(profile); Objects.requireNonNull(reservation);
        edges = List.copyOf(edges); portals = List.copyOf(portals); dependencies = Map.copyOf(dependencies);
    }

    /** Every persistent holder retains the same payload; execution cursors never duplicate its charge. */
    public Retention retain() { return reservation.retain(); }

    public static final class Reservation {
        private final NavigationGraphCache.TransientLease memory;
        private int holders;
        private boolean released;
        Reservation(NavigationGraphCache.TransientLease memory) { this.memory = memory; }
        private Retention retain() {
            if (released && memory != null) throw new IllegalStateException("Route payload has already been released");
            holders++;
            return new Retention(this);
        }
        private void release() {
            if (--holders == 0 && memory != null) { released = true; memory.close(); }
        }
    }
    public static final class Retention implements AutoCloseable {
        private Reservation reservation;
        private Retention(Reservation reservation) { this.reservation = reservation; }
        @Override public void close() {
            if (reservation == null) return;
            Reservation held = reservation; reservation = null; held.release();
        }
    }
}
