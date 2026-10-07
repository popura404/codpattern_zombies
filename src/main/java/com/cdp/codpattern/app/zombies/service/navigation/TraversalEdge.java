package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Directed immutable, geometry-proven connection. Never contains a mutable native Path. */
public record TraversalEdge(SurfaceNode from, SurfaceNode to, Action action,
        List<Vec3> controlPoints, double cost, Map<NavigationGraphCache.TileKey, Long> dependencies) {
    public enum Action { WALK, STEP, PRECISE_MOVE, DROP, NATIVE_INTERACTION }
    public TraversalEdge {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(action);
        controlPoints = List.copyOf(controlPoints); dependencies = Map.copyOf(dependencies);
        if (!Double.isFinite(cost) || cost < 0) throw new IllegalArgumentException("Invalid traversal cost");
        // Euclidean endpoint distance is a lower bound on every executable traversal.
        cost = Math.max(cost, from.feet().distanceTo(to.feet()));
    }
}
