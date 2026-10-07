package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed low jump over a proven DROP departure; collision evidence remains the validator's job. */
public final class DropJump {
    public static final double HEIGHT_THRESHOLD = 3.0;
    public static final double UPWARD_SPEED = 0.25;
    public static final double FORWARD_SPEED = 0.28;
    public static final double AIR_DRAG = 0.91F;
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98F;
    private static final double EPSILON = TraversalMath.EPSILON;
    private static final double COLUMN_TOLERANCE = 0.20;
    // A work limit for the optional action, not a limit on graph DROP reachability.
    private static final int MAX_TICKS = 512;

    private DropJump() { }

    public record Plan(Vec3 origin, Vec3 departure, Vec3 landing, Vec3 initialVelocity,
                       List<Vec3> samples, double firstDrag) {
        public Plan {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(departure);
            Objects.requireNonNull(landing);
            Objects.requireNonNull(initialVelocity);
            samples = List.copyOf(samples);
        }
    }

    public static boolean required(TraversalEdge edge) {
        return edge != null && edge.action() == TraversalEdge.Action.DROP
                && edge.from().feet().y - edge.to().feet().y > HEIGHT_THRESHOLD + EPSILON;
    }

    /**
     * Simulates movement before drag/gravity, including the grounded first tick.
     * firstDrag is the complete horizontal factor (block friction * 0.91), not raw block friction.
     * A null result declines this optional jump; the ordinary DROP remains available.
     */
    public static Plan plan(Vec3 origin, Vec3 departure, Vec3 landing, double firstDrag) {
        if (!finite(origin) || !finite(departure) || !finite(landing)
                || !Double.isFinite(firstDrag) || firstDrag < 0 || firstDrag > 1
                || Math.abs(departure.y - origin.y) > EPSILON
                || landing.y >= origin.y - EPSILON
                || horizontalDistanceSqr(departure, landing) > EPSILON * EPSILON) return null;
        double dx = departure.x - origin.x, dz = departure.z - origin.z;
        double distance = Math.hypot(dx, dz);
        if (!Double.isFinite(distance) || distance <= EPSILON) return null;
        Vec3 initialVelocity = new Vec3(dx / distance * FORWARD_SPEED, UPWARD_SPEED,
                dz / distance * FORWARD_SPEED);
        Vec3 velocity = initialVelocity;
        Vec3 position = origin;
        List<Vec3> samples = new ArrayList<>();
        samples.add(origin);
        boolean returnedToStartHeight = false;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (reachedDeparture(origin, departure, position))
                velocity = new Vec3(0, velocity.y, 0);
            Vec3 next = position.add(velocity);
            if (!finite(next)) return null;
            if (reachedDeparture(origin, departure, next)
                    && horizontalDistanceSqr(next, departure) > square(COLUMN_TOLERANCE + EPSILON)) return null;
            if (!returnedToStartHeight && velocity.y < 0 && next.y <= origin.y) {
                Vec3 crossing = interpolateAtHeight(position, next, origin.y);
                double progress = ((crossing.x - origin.x) * dx + (crossing.z - origin.z) * dz) / distance;
                // The feet must leave a normal full-block ledge before descending through its top.
                // Partial blocks and the actual body width still need the complete collision proof.
                if (progress < distance - COLUMN_TOLERANCE - EPSILON) return null;
                returnedToStartHeight = true;
            }
            if (velocity.y < 0 && next.y <= landing.y) {
                // Vanilla clips vertical contact without shortening that tick's horizontal move.
                // Height interpolation is exact only after the departure brake has stopped X/Z.
                if (velocity.x != 0 || velocity.z != 0) return null;
                Vec3 contact = interpolateAtHeight(position, next, landing.y);
                if (!finite(contact) || horizontalDistanceSqr(contact, landing)
                        > square(COLUMN_TOLERANCE + EPSILON)) return null;
                samples.add(contact);
                return new Plan(origin, departure, landing, initialVelocity, samples, firstDrag);
            }
            samples.add(next);
            position = next;
            double horizontalDrag = tick == 0 ? firstDrag : AIR_DRAG;
            velocity = new Vec3(velocity.x * horizontalDrag, (velocity.y - GRAVITY) * VERTICAL_DRAG,
                    velocity.z * horizontalDrag);
        }
        return null;
    }

    /** The plane through departure perpendicular to the fixed launch direction; ignores vertical motion. */
    public static boolean reachedDeparture(Plan plan, Vec3 position) {
        return plan != null && finite(position) && reachedDeparture(plan.origin(), plan.departure(), position);
    }

    private static boolean reachedDeparture(Vec3 origin, Vec3 departure, Vec3 position) {
        double dx = departure.x - origin.x, dz = departure.z - origin.z;
        return (position.x - departure.x) * dx + (position.z - departure.z) * dz >= -EPSILON;
    }

    private static Vec3 interpolateAtHeight(Vec3 from, Vec3 to, double y) {
        double fraction = (from.y - y) / (from.y - to.y);
        return new Vec3(from.x + (to.x - from.x) * fraction, y,
                from.z + (to.z - from.z) * fraction);
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        return square(a.x - b.x) + square(a.z - b.z);
    }

    private static double square(double value) { return value * value; }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
