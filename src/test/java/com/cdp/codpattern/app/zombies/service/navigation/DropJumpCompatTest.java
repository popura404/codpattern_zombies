package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/** Pure trajectory checks; no world, entity tick, game, or server is required. */
public final class DropJumpCompatTest {
    private static final double STONE_FIRST_DRAG = (float) (0.6F * 0.91F);

    private DropJumpCompatTest() { }

    public static void main(String[] args) {
        onlyDropsHigherThanThreeBlocksNeedTheJump();
        normalCliffsHaveFiniteTrajectoriesAndFixedLandings();
        firstTickUsesGroundDragThenAirDrag();
        departureBrakingIsDirectionalAndStopsExtraAcceleration();
        mirroredAndRotatedJumpsHaveTheSameTrajectory();
        anUnreachableDepartureCannotBecomeAValidDeepFall();
        malformedAndUnboundedInputsDeclineTheOptionalJump();
    }

    private static void onlyDropsHigherThanThreeBlocksNeedTheJump() {
        require(!DropJump.required(edge(3.0, TraversalEdge.Action.DROP)), "exactly three blocks retains ordinary DROP");
        require(!DropJump.required(edge(2.99, TraversalEdge.Action.DROP)), "short drops retain ordinary DROP");
        require(DropJump.required(edge(3.01, TraversalEdge.Action.DROP)), "a drop above three blocks uses the fixed jump");
        require(!DropJump.required(edge(4, TraversalEdge.Action.WALK)), "height alone must not convert a different action");
        require(!DropJump.required(null), "a missing route is not permission to jump");
    }

    private static void normalCliffsHaveFiniteTrajectoriesAndFixedLandings() {
        for (double depth : new double[]{3.01, 4, 5, 8, 32, 64, 383.75}) {
            Vec3 origin = new Vec3(10.5, 320, -2.5);
            Vec3 departure = origin.add(1, 0, 0);
            Vec3 landing = departure.add(0, -depth, 0);
            DropJump.Plan plan = requirePlan(origin, departure, landing, STONE_FIRST_DRAG);
            List<Vec3> samples = plan.samples();
            require(samples.get(0).equals(origin) && samples.size() <= 513, "samples include launch and stay bounded");
            require(samples.get(1).y > origin.y, "the action starts with the fixed upward impulse");
            boolean crossedStartHeight = false;
            for (int i = 0; i < samples.size(); i++) {
                Vec3 sample = samples.get(i);
                require(finite(sample), "every trajectory sample must be finite");
                require(sample.y >= landing.y - 1e-8, "the final movement must stop at first landing height");
                if (i > 0 && sample.y <= origin.y && samples.get(i - 1).y > origin.y) {
                    Vec3 previous = samples.get(i - 1);
                    double fraction = (previous.y - origin.y) / (previous.y - sample.y);
                    double crossingX = previous.x + (sample.x - previous.x) * fraction;
                    require(crossingX - 0.3 > origin.x + 0.5, "the complete zombie footprint clears the full block before returning down");
                    crossedStartHeight = true;
                }
            }
            Vec3 contact = samples.get(samples.size() - 1);
            require(crossedStartHeight && close(contact.y, landing.y), "a deep drop must cross launch height and meet the exact landing height");
            require(horizontalDistance(contact, landing) <= 0.2 + 1e-6, "the fixed landing column bounds endpoint error");
            try {
                samples.add(origin);
                throw new AssertionError("trajectory samples must be immutable");
            } catch (UnsupportedOperationException expected) { }
        }
    }

    private static void firstTickUsesGroundDragThenAirDrag() {
        DropJump.Plan stone = requirePlan(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1, -5, 0), STONE_FIRST_DRAG);
        require(stone.firstDrag() == (double) (0.6F * 0.91F), "firstDrag uses Minecraft's float multiplication before promotion to double");
        require(DropJump.AIR_DRAG == (double) 0.91F && DropJump.VERTICAL_DRAG == (double) 0.98F,
                "air and vertical drag preserve Minecraft's float constants before promotion to double");
        Vec3 first = stone.samples().get(1), second = stone.samples().get(2), third = stone.samples().get(3);
        require(close(first.x, 0.28) && close(first.y, 0.25), "launch moves before applying drag or gravity");
        require(close(second.x - first.x, 0.28 * (double) (0.6F * 0.91F)), "only the first post-move drag uses the support surface");
        require(close(third.x - second.x, 0.28 * (double) (0.6F * 0.91F) * (double) 0.91F), "subsequent movement uses air drag");
        require(close(second.y - first.y, (0.25 - 0.08) * (double) 0.98F), "vertical gravity is applied after each movement");
        DropJump.Plan slippery = requirePlan(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1, -5, 0), (float) (0.98F * 0.91F));
        require(slippery.samples().get(2).x > second.x, "a different first surface must change the trajectory");
    }

    private static void departureBrakingIsDirectionalAndStopsExtraAcceleration() {
        DropJump.Plan plan = requirePlan(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1, -32, 0), STONE_FIRST_DRAG);
        require(!DropJump.reachedDeparture(plan, new Vec3(0.9, -100, 100)), "height and sideways displacement cannot prematurely cross the departure plane");
        require(DropJump.reachedDeparture(plan, new Vec3(1, 100, -100)), "the departure plane ignores height and lateral displacement");
        require(!DropJump.reachedDeparture(plan, new Vec3(Double.NaN, 0, 0)), "invalid entity positions never allow braking decisions");
        boolean crossed = false;
        double fixedX = 0, fixedZ = 0;
        for (Vec3 sample : plan.samples()) {
            if (crossed) require(close(sample.x, fixedX) && close(sample.z, fixedZ), "horizontal movement stops after the first crossing tick");
            if (!crossed && DropJump.reachedDeparture(plan, sample)) {
                crossed = true; fixedX = sample.x; fixedZ = sample.z;
            }
        }
        require(crossed, "a normal one-block departure must actually be crossed");
    }

    private static void mirroredAndRotatedJumpsHaveTheSameTrajectory() {
        DropJump.Plan reference = requirePlan(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1, -8, 0), STONE_FIRST_DRAG);
        for (double[] direction : new double[][]{{-1, 0}, {0, 1}, {0, -1}, {0.6, 0.8}}) {
            Vec3 origin = new Vec3(-20.25, 15.5, 32.75);
            Vec3 departure = origin.add(direction[0], 0, direction[1]);
            DropJump.Plan transformed = requirePlan(origin, departure, departure.add(0, -8, 0), STONE_FIRST_DRAG);
            require(transformed.samples().size() == reference.samples().size(), "direction cannot change the time of contact");
            for (int i = 0; i < reference.samples().size(); i++) {
                Vec3 expected = reference.samples().get(i), actual = transformed.samples().get(i).subtract(origin);
                require(close(actual.x, expected.x * direction[0]) && close(actual.z, expected.x * direction[1])
                        && close(actual.y, expected.y), "mirroring, rotation and translation preserve the trajectory");
            }
        }
    }

    private static void anUnreachableDepartureCannotBecomeAValidDeepFall() {
        require(DropJump.plan(Vec3.ZERO, new Vec3(3, 0, 0), new Vec3(3, -64, 0), STONE_FIRST_DRAG) == null,
                "deep clearance must not hide insufficient horizontal range");
        require(DropJump.plan(Vec3.ZERO, new Vec3(1.5, 0, 0), new Vec3(1.5, -64, 0), STONE_FIRST_DRAG) == null,
                "a jump that falls back through its platform before clearing it is not a usable departure");
        require(DropJump.plan(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(1, -8, 0), 0.1) == null,
                "heavy first-tick damping must not invent a clear platform departure");
        require(DropJump.plan(Vec3.ZERO, new Vec3(0.01, 0, 0), new Vec3(0.01, -8, 0), STONE_FIRST_DRAG) == null,
                "the first step may not overshoot the fixed landing column");
        require(DropJump.plan(Vec3.ZERO, new Vec3(0.8, 0, 0), new Vec3(0.8, -3.01, 0), 0.2) == null,
                "landing before horizontal braking cannot use a height-clipped diagonal contact");
    }

    private static void malformedAndUnboundedInputsDeclineTheOptionalJump() {
        Vec3 departure = new Vec3(1, 0, 0), landing = new Vec3(1, -4, 0);
        require(DropJump.plan(null, departure, landing, STONE_FIRST_DRAG) == null, "missing launch position is invalid");
        require(DropJump.plan(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0), landing, STONE_FIRST_DRAG) == null,
                "infinite coordinates cannot enter the simulation");
        require(DropJump.plan(Vec3.ZERO, Vec3.ZERO, new Vec3(0, -4, 0), STONE_FIRST_DRAG) == null, "no direction cannot create a jump");
        require(DropJump.plan(Vec3.ZERO, new Vec3(1, 1, 0), landing, STONE_FIRST_DRAG) == null, "departure remains at launch height");
        require(DropJump.plan(Vec3.ZERO, departure, new Vec3(2, -4, 0), STONE_FIRST_DRAG) == null, "a landing cannot silently change columns");
        require(DropJump.plan(Vec3.ZERO, departure, new Vec3(1, 0, 0), STONE_FIRST_DRAG) == null, "this is not a level-ground jump planner");
        for (double drag : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1})
            require(DropJump.plan(Vec3.ZERO, departure, landing, drag) == null, "invalid drag must decline the optional action");
        require(DropJump.plan(Vec3.ZERO, departure, new Vec3(1, -Double.MAX_VALUE, 0), STONE_FIRST_DRAG) == null,
                "an impossible depth must terminate at the trajectory work limit");
    }

    private static TraversalEdge edge(double depth, TraversalEdge.Action action) {
        Vec3 from = new Vec3(0.5, 10, 0.5), to = from.add(1, -depth, 0);
        return new TraversalEdge(new SurfaceNode(from, 0), new SurfaceNode(to, 0), action,
                List.of(from, from.add(1, 0, 0), to), depth + 1, Map.of());
    }

    private static DropJump.Plan requirePlan(Vec3 origin, Vec3 departure, Vec3 landing, double firstDrag) {
        DropJump.Plan result = DropJump.plan(origin, departure, landing, firstDrag);
        require(result != null, "expected a finite valid trajectory: " + origin + " -> " + landing);
        return result;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-8; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
