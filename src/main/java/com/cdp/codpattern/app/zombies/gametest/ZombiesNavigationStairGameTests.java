package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.MovementProfile;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationContext;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationGraphCache;
import com.cdp.codpattern.app.zombies.service.navigation.SurfaceNode;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** A stalled real zombie may start a graph search on the lower, off-centre half of a stair. */
@GameTestHolder("codpattern_navigation_stairs")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationStairGameTests {
    private ZombiesNavigationStairGameTests() { }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void lowerHalfOfEastFacingStairHasAnAscendingExit(GameTestHelper helper) {
        halfStairExit(helper, Direction.EAST);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void lowerHalfOfSouthFacingStairHasAnAscendingExit(GameTestHelper helper) {
        halfStairExit(helper, Direction.SOUTH);
    }

    private static void halfStairExit(GameTestHelper helper, Direction facing) {
        for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        BlockPos stair = new BlockPos(6, 1, 6);
        helper.setBlock(stair, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
        helper.setBlock(stair.relative(facing), Blocks.STONE);
        // A zombie is wider than a half-block: its feet are near the lower lip, with some
        // body overhanging the preceding lower floor. This is a valid supported position.
        Vec3 relative = facing == Direction.EAST ? new Vec3(6.15, 1.5, 6.5) : new Vec3(6.5, 1.5, 6.15);
        Mob mob = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob != null, "the stair fixture must create a zombie");
        Vec3 feet = helper.absoluteVec(relative);
        mob.moveTo(feet.x, feet.y, feet.z, 0, 0);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setOnGround(true);
        mob.setPersistenceRequired();
        helper.getLevel().addFreshEntity(mob);
        NavigationGraphCache cache = new NavigationGraphCache();
        NavigationContext context = new NavigationContext(RoomId.of("zombies", "stair-" + UUID.randomUUID()),
                helper.getLevel(), new AABB(helper.absoluteVec(new Vec3(1, 0, 1)),
                helper.absoluteVec(new Vec3(23, 12, 23))), List::of, candidate -> candidate == mob, UUID.randomUUID());
        TraversalValidator validator = new TraversalValidator(context, cache);
        MovementProfile profile = MovementProfile.from(mob);
        SurfaceNode from = supportedStart(helper, validator, mob, feet, profile, cache,
                "the real lower half-stair position must remain a supported off-centre search origin");
        var cursor = validator.beginNeighbours(mob, from, profile, from.version());
        List<TraversalEdge> edges = new ArrayList<>();
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            try {
                var batch = validator.advance(cursor, 16, Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed() <= 16, "half-stair expansion must obey the supplied geometry budget");
                helper.assertTrue(mob.position().distanceToSqr(feet) < 1.0E-8,
                        "graph exploration cannot move the zombie to manufacture a stair exit");
                edges.addAll(batch.edges());
                if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                helper.assertTrue(edges.stream().anyMatch(edge -> (edge.action() == TraversalEdge.Action.STEP
                                        || edge.action() == TraversalEdge.Action.PRECISE_MOVE)
                                && Math.abs(edge.to().feet().y - feet.y - 0.5) < 1.0E-8),
                        "a supported lower half-stair must expose an ascending edge without centring at the blocked lower height; "
                                + "facing=" + facing + ", origin=" + from + ", neighbours=" + edges);
                finished[0] = true;
                cursor.close();
                mob.discard();
                cache.reset();
                helper.succeed();
            } catch (RuntimeException | Error failure) {
                finished[0] = true;
                cursor.close();
                mob.discard();
                cache.reset();
                throw failure;
            }
        });
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void offCentreFlatFloorJoinsCentresWithoutPropagatingOffset(GameTestHelper helper) {
        for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        Vec3 feet = helper.absoluteVec(new Vec3(6.15, 1, 6.35));
        Mob mob = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob != null, "the flat-floor fixture must create a zombie");
        mob.moveTo(feet.x, feet.y, feet.z, 0, 0);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setOnGround(true);
        mob.setPersistenceRequired();
        helper.getLevel().addFreshEntity(mob);
        NavigationGraphCache cache = new NavigationGraphCache();
        NavigationContext context = new NavigationContext(RoomId.of("zombies", "flat-offset-" + UUID.randomUUID()),
                helper.getLevel(), new AABB(helper.absoluteVec(new Vec3(1, 0, 1)),
                helper.absoluteVec(new Vec3(23, 12, 23))), List::of, candidate -> candidate == mob, UUID.randomUUID());
        TraversalValidator validator = new TraversalValidator(context, cache);
        MovementProfile profile = MovementProfile.from(mob);
        SurfaceNode from = supportedStart(helper, validator, mob, feet, profile, cache,
                "flat-floor acquisition must preserve the real off-centre start");
        var cursor = validator.beginNeighbours(mob, from, profile, from.version());
        List<TraversalEdge> edges = new ArrayList<>();
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            try {
                var batch = validator.advance(cursor, 4, Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed() <= 4, "flat-floor expansion must obey each geometry slice");
                helper.assertTrue(from.feet().distanceToSqr(feet) < 1.0E-8
                                && mob.position().distanceToSqr(feet) < 1.0E-8,
                        "joining centred graph nodes must not snap the origin or move the entity");
                edges.addAll(batch.edges());
                if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                helper.assertTrue(edges.size() == 9,
                        "an off-centre start on an unobstructed floor must retain its own centre and eight neighbours");
                for (TraversalEdge edge : edges) {
                    Vec3 end = edge.to().feet();
                    helper.assertTrue(edge.from().equals(from) && Math.abs(end.y - feet.y) < 1.0E-8
                                    && Math.abs(end.x - Math.floor(end.x) - 0.5) < 1.0E-8
                                    && Math.abs(end.z - Math.floor(end.z) - 0.5) < 1.0E-8,
                            "empty full-block terrain must not propagate arbitrary starting offsets into parallel graph nodes");
                }
                Vec3 centre = helper.absoluteVec(new Vec3(6.5, 1, 6.5));
                helper.assertTrue(edges.stream().anyMatch(edge -> edge.to().feet().distanceToSqr(centre) < 1.0E-8),
                        "the same-cell centre must remain connected by a proven precise move");
                finished[0] = true;
                cursor.close();
                helper.assertTrue(cache.stats().transientEntries() == 0,
                        "finishing the flat-floor scan must release all retained geometry and outputs");
                mob.discard();
                cache.reset();
                helper.succeed();
            } catch (RuntimeException | Error failure) {
                finished[0] = true;
                cursor.close();
                mob.discard();
                cache.reset();
                throw failure;
            }
        });
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation", batch = "navigation_stair_execution", timeoutTicks = 610)
    public static void layeredControllerPhysicallyLeavesLowerHalfOfStair(GameTestHelper helper) {
        physicalHalfStair(helper, false, false);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation", batch = "navigation_stair_execution", timeoutTicks = 610)
    public static void lowerHalfStairAscentFitsUnderTwoBlockCeiling(GameTestHelper helper) {
        physicalHalfStair(helper, true, false);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation", batch = "navigation_stair_execution", timeoutTicks = 610)
    public static void layeredControllerDescendsToOffCentreLowerHalfStair(GameTestHelper helper) {
        physicalHalfStair(helper, false, true);
    }

    private static void physicalHalfStair(GameTestHelper helper, boolean lowRoof, boolean descending) {
        for (int x = 1; x <= 22; x++) for (int z = 1; z <= 22; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        for (int z = 5; z <= 9; z++) {
            helper.setBlock(new BlockPos(6, 1, z), Blocks.STONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST));
            for (int x = 7; x <= 18; x++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        }
        if (lowRoof) for (int x = 4; x <= 19; x++) for (int z = 4; z <= 10; z++) {
            helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
        }
        Vec3 lower = new Vec3(6.15, 1.5, 7.5), upper = new Vec3(15.5, 2, 7.5);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, descending ? lower : upper);
        try {
            Mob mob = fixture.spawn("zombie", descending ? new BlockPos(15, 2, 7) : new BlockPos(5, 1, 7));
            Vec3 feet = helper.absoluteVec(descending ? upper : lower);
            // Initial placement only. No test callback moves, pushes, jumps, or teleports the entity.
            mob.moveTo(feet.x, feet.y, feet.z, 0, 0);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.setOnGround(true);
            LayeredNavigationRuntime runtime = driveProductionController(helper, mob);
            UUID original = mob.getUUID();
            Vec3 target = fixture.player.position();
            double roofY = helper.absoluteVec(new Vec3(0, 4, 0)).y;
            Vec3[] previous = {feet};
            boolean[] preciseVertical = {false}, finished = {false};
            helper.onEachTick(() -> {
                if (finished[0]) return;
                try {
                    helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(original) == mob,
                            "the same zombie must physically traverse the half-stair");
                    helper.assertTrue(fixture.player.position().distanceToSqr(target) < 1.0E-8,
                            "the stair execution target must remain fixed");
                    helper.assertTrue(mob.position().distanceToSqr(previous[0]) < 4,
                            "half-stair execution must never teleport the zombie");
                    if (lowRoof) helper.assertTrue(mob.getBoundingBox().maxY <= roofY + 1.0E-6,
                            "automatic native jumping must never bypass the two-block ceiling collision");
                    previous[0] = mob.position();
                    TraversalEdge edge = runtime.activeEdge(mob);
                    double direction = descending ? -1 : 1;
                    preciseVertical[0] |= edge != null && edge.action() == TraversalEdge.Action.PRECISE_MOVE
                            && (edge.to().feet().y - edge.from().feet().y) * direction > 0.1;
                    double arrivalDistanceSquared = descending ? 0.22 * 0.22 : 2.2;
                    if (mob.onGround() && Math.abs(mob.getY() - target.y) < 0.05
                            && mob.distanceToSqr(fixture.player) <= arrivalDistanceSquared) {
                        helper.assertTrue(preciseVertical[0], "arrival must exercise the production precise stair "
                                + (descending ? "descent" : "ascent") + " edge");
                        if (descending) helper.assertTrue(Math.abs(mob.getX() - Math.floor(mob.getX()) - 0.5) > 0.2,
                                "the descended zombie must actually stand on the off-centre lower tread");
                        finished[0] = true;
                        fixture.close();
                        helper.succeed();
                    } else if (helper.getTick() >= 600) {
                        helper.fail("layered controller did not physically traverse the stair half; position=" + mob.position()
                                + ", descending=" + descending + ", lowRoof=" + lowRoof
                                + ", preciseVertical=" + preciseVertical[0] + ", runtime=" + runtime.describe(mob));
                    }
                } catch (RuntimeException | Error failure) {
                    finished[0] = true;
                    fixture.close();
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static LayeredNavigationRuntime driveProductionController(GameTestHelper helper, Mob mob) {
        LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
        helper.assertTrue(runtime != null, "the physical half-stair test requires the layered engine");
        Goal recovery = runtime.movementGoal(mob);
        // Isolate this public production controller from vanilla melee. The end-to-end
        // distant-stair test below retains all unmodified production goal scheduling.
        mob.goalSelector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal()).toList()
                .stream().filter(goal -> goal == recovery || goal.getFlags().contains(Goal.Flag.MOVE))
                .forEach(mob.goalSelector::removeGoal);
        mob.targetSelector.getAvailableGoals().stream().map(wrapped -> wrapped.getGoal()).toList()
                .forEach(mob.targetSelector::removeGoal);
        mob.goalSelector.addGoal(0, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)); }
            @Override public boolean canUse() { return true; }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void start() { recovery.start(); }
            @Override public void tick() { recovery.tick(); }
            @Override public void stop() { recovery.stop(); }
        });
        return runtime;
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void nonCentredStairMovementCannotBridgeAnUnsupportedGap(GameTestHelper helper) {
        rejectedConnection(helper, true);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void nonCentredFullBlockJumpCannotBecomePreciseWalking(GameTestHelper helper) {
        rejectedConnection(helper, false);
    }

    private static void rejectedConnection(GameTestHelper helper, boolean gap) {
        for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        if (gap) {
            helper.setBlock(new BlockPos(6, 0, 6), Blocks.AIR);
            helper.setBlock(new BlockPos(7, 1, 6), Blocks.STONE_SLAB);
        } else helper.setBlock(new BlockPos(6, 1, 6), Blocks.STONE);
        Vec3 from = helper.absoluteVec(new Vec3(gap ? 5.9 : 5.65, 1, 6.5));
        Vec3 to = helper.absoluteVec(gap ? new Vec3(7.2, 1.5, 6.5) : new Vec3(6.5, 2, 6.5));
        Mob mob = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob != null, "the rejected step fixture must create a zombie");
        mob.moveTo(from.x, from.y, from.z, 0, 0);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setOnGround(true);
        mob.setPersistenceRequired();
        helper.getLevel().addFreshEntity(mob);
        NavigationGraphCache cache = new NavigationGraphCache();
        NavigationContext context = new NavigationContext(RoomId.of("zombies", "stair-rejection-" + UUID.randomUUID()),
                helper.getLevel(), new AABB(helper.absoluteVec(new Vec3(1, 0, 1)),
                helper.absoluteVec(new Vec3(23, 12, 23))), List::of, candidate -> candidate == mob, UUID.randomUUID());
        TraversalValidator validator = new TraversalValidator(context, cache);
        MovementProfile profile = MovementProfile.from(mob);
        SurfaceNode start = supportedStart(helper, validator, mob, from, profile, cache,
                "the rejected transition must begin at a real supported off-centre position");
        var cursor = validator.beginConnection(mob, start, to, profile);
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            try {
                var batch = validator.advanceConnection(cursor, 16, Long.MAX_VALUE);
                helper.assertTrue(batch.edges().isEmpty(), gap
                        ? "precise movement cannot bridge a full unsupported air gap"
                        : "an off-centre one-block jump cannot be relabelled as precise supported walking");
                if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                finished[0] = true;
                cursor.close();
                mob.discard();
                cache.reset();
                helper.succeed();
            } catch (RuntimeException | Error failure) {
                finished[0] = true;
                cursor.close();
                mob.discard();
                cache.reset();
                throw failure;
            }
        });
    }

    private static SurfaceNode supportedStart(GameTestHelper helper, TraversalValidator validator, Mob mob,
                                               Vec3 feet, MovementProfile profile, NavigationGraphCache cache,
                                               String assertion) {
        try {
            SurfaceNode start = validator.locate(mob, feet, profile);
            helper.assertTrue(start != null && start.feet().distanceToSqr(feet) < 1.0E-8, assertion);
            return start;
        } catch (RuntimeException | Error failure) {
            // The tick callback is not registered yet, so it cannot own setup cleanup.
            mob.discard();
            cache.reset();
            throw failure;
        }
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void centredFlatFloorKeepsEightExitsUnderSmallGeometrySlices(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.CLEAR);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void brickFloorRetainsAllExitsThroughGeneralShapeScanning(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.BRICKS);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void wallChangedBetweenGeometrySlicesCannotPublishAFlatShortcut(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.MID_SCAN_MUTATION);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void connectedStairTopDoesNotDuplicateTheAdjacentFlatFloor(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.STAIR_SUPPORT);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void clearDiagonalDestinationCannotCutThroughTwoStoneCorners(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.CORNER);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void neighbouringFireAndCactusPreventOtherwiseFlatWalking(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.DANGER);
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_flat_geometry", timeoutTicks = 400)
    public static void flatPlannedEdgeRechecksUnannouncedStoneWallBeforeExecution(GameTestHelper helper) {
        flatGeometry(helper, FlatScenario.MUTATION);
    }

    private enum FlatScenario { CLEAR, BRICKS, STAIR_SUPPORT, CORNER, DANGER, MUTATION, MID_SCAN_MUTATION }

    private static void flatGeometry(GameTestHelper helper, FlatScenario scenario) {
        // A broad integer-height floor and empty body/halo volume provide the ordinary
        // flat-terrain case. Each counterexample changes only the relevant geometry.
        for (int x = 3; x <= 18; x++) for (int z = 3; z <= 18; z++) for (int y = 0; y <= 8; y++) {
            helper.setBlock(new BlockPos(x, y, z), y <= 2
                    ? scenario == FlatScenario.BRICKS ? Blocks.STONE_BRICKS : Blocks.STONE : Blocks.AIR);
        }
        if (scenario == FlatScenario.STAIR_SUPPORT) {
            helper.setBlock(new BlockPos(9, 2, 8), Blocks.STONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST));
        } else if (scenario == FlatScenario.CORNER) {
            for (int y = 3; y <= 5; y++) {
                helper.setBlock(new BlockPos(9, y, 8), Blocks.STONE);
                helper.setBlock(new BlockPos(8, y, 9), Blocks.STONE);
            }
        } else if (scenario == FlatScenario.DANGER) {
            helper.setBlock(new BlockPos(10, 2, 8), Blocks.SAND);
            helper.setBlock(new BlockPos(10, 3, 8), Blocks.CACTUS);
            helper.setBlock(new BlockPos(6, 2, 8), Blocks.NETHERRACK);
            helper.setBlock(new BlockPos(6, 3, 8), Blocks.FIRE);
        }
        FlatGeometryRun run = new FlatGeometryRun(helper, scenario);
        try {
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
            run.release();
            run.cache.reset();
            throw failure;
        }
    }

    private static final class FlatGeometryRun {
        private static final int SLICE = 4;
        final GameTestHelper helper;
        final FlatScenario scenario;
        final NavigationGraphCache cache = new NavigationGraphCache();
        final List<TraversalEdge> edges = new ArrayList<>();
        final Vec3 feet;
        Mob mob;
        MovementProfile profile;
        TraversalValidator validator;
        TraversalValidator.NeighborCursor neighbours;
        TraversalValidator.ValidationCursor validation;
        boolean finished, changedDuringScan;

        FlatGeometryRun(GameTestHelper helper, FlatScenario scenario) {
            this.helper = helper;
            this.scenario = scenario;
            feet = helper.absoluteVec(new Vec3(8.5, 3, 8.5));
            try {
                mob = EntityType.ZOMBIE.create(helper.getLevel());
                helper.assertTrue(mob != null, "the flat geometry fixture must create a zombie");
                mob.moveTo(feet.x, feet.y, feet.z, 0, 0);
                mob.setNoAi(true);
                mob.setNoGravity(true);
                mob.setOnGround(true);
                mob.setPersistenceRequired();
                helper.getLevel().addFreshEntity(mob);
                NavigationContext context = new NavigationContext(RoomId.of("zombies", "flat-proof-" + UUID.randomUUID()),
                        helper.getLevel(), new AABB(helper.absoluteVec(new Vec3(1, 0, 1)),
                        helper.absoluteVec(new Vec3(23, 12, 23))), List::of, candidate -> candidate == mob, UUID.randomUUID());
                validator = new TraversalValidator(context, cache);
                profile = MovementProfile.from(mob);
                SurfaceNode from = supportedStart(helper, validator, mob, feet, profile, cache,
                        "the flat proof must begin at its real supported grid centre");
                if (scenario == FlatScenario.CORNER) {
                    Vec3 diagonal = helper.absoluteVec(new Vec3(9.5, 3, 9.5));
                    SurfaceNode destination = validator.locate(mob, diagonal, profile);
                    helper.assertTrue(destination != null && destination.feet().distanceToSqr(diagonal) < 1.0E-8,
                            "the diagonal destination itself must be standable; only the swept corner blocks travel");
                }
                neighbours = validator.beginNeighbours(mob, from, profile, from.version());
            } catch (RuntimeException | Error failure) {
                release();
                cache.reset();
                throw failure;
            }
        }

        void observe() {
            if (finished) return;
            try {
                helper.assertTrue(mob.position().distanceToSqr(feet) < 1.0E-8,
                        "proving flat movement must not relocate the actual entity");
                if (validation != null) {
                    var batch = validator.advanceValidation(validation, SLICE, Long.MAX_VALUE);
                    helper.assertTrue(batch.workUsed() <= SLICE, "fresh execution proof must retain the small geometry slice");
                    if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                    helper.assertTrue(batch.verdict() == TraversalValidator.Verdict.BLOCKED,
                            "an unannounced new wall must invalidate the previously clear flat edge before execution");
                    succeed();
                    return;
                }
                var batch = validator.advance(neighbours, SLICE, Long.MAX_VALUE);
                helper.assertTrue(batch.workUsed() <= SLICE, "flat neighbour discovery must obey every small geometry slice");
                helper.assertTrue(batch.status() != TraversalValidator.ScanStatus.RESOURCE_LIMITED
                                && batch.status() != TraversalValidator.ScanStatus.WAITING_CHUNK,
                        "the small loaded flat fixture must not need extra resources or chunks");
                edges.addAll(batch.edges());
                if (scenario == FlatScenario.MID_SCAN_MUTATION && !changedDuringScan) {
                    helper.assertTrue(batch.status() == TraversalValidator.ScanStatus.PENDING && edges.isEmpty(),
                            "the wall change must interrupt an unfinished geometry proof");
                    for (int y = 3; y <= 5; y++) helper.setBlock(new BlockPos(9, y, 8), Blocks.STONE);
                    cache.invalidate(new AABB(helper.absoluteVec(new Vec3(9, 3, 8)),
                            helper.absoluteVec(new Vec3(10, 6, 9))), "wall changed between read batches");
                    changedDuringScan = true;
                    return;
                }
                if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                helper.assertTrue(validator.fastNeighborScans() == (scenario == FlatScenario.CLEAR
                                || scenario == FlatScenario.MUTATION ? 1 : 0),
                        "clear stone must exercise the fast proof; corners and danger must use full geometry");
                if (scenario == FlatScenario.STAIR_SUPPORT) helper.assertTrue(validator.simplifiedStairScans() == 1,
                        "a proven connected stair top must exercise the redundant-offset shortcut");
                switch (scenario) {
                    case CLEAR, BRICKS, STAIR_SUPPORT -> {
                        helper.assertTrue(edges.size() == 8, "an unobstructed centred floor must expose all eight neighbours");
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            Vec3 expected = feet.add(dx, 0, dz);
                            helper.assertTrue(edges.stream().anyMatch(edge -> edge.action() == TraversalEdge.Action.WALK
                                            && edge.from().feet().distanceToSqr(feet) < 1.0E-8
                                            && edge.to().feet().distanceToSqr(expected) < 1.0E-8),
                                    "flat proof must preserve each distinct centred walking neighbour: " + expected);
                        }
                    }
                    case CORNER -> {
                        Vec3 forbidden = feet.add(1, 0, 1);
                        helper.assertTrue(edges.stream().noneMatch(edge -> edge.to().feet().distanceToSqr(forbidden) < 1.0E-8),
                                "a clear diagonal endpoint must not allow the zombie body to cut through two solid corners");
                    }
                    case MID_SCAN_MUTATION -> {
                        helper.assertTrue(changedDuringScan && edges.stream().noneMatch(edge ->
                                        edge.to().feet().distanceToSqr(feet.add(1, 0, 0)) < 1.0E-8),
                                "resumed scans must see the new wall instead of reusing pre-change read evidence");
                        helper.assertTrue(edges.stream().anyMatch(edge ->
                                        edge.to().feet().distanceToSqr(feet.add(-1, 0, 0)) < 1.0E-8),
                                "an invalidated proof must resume discovery of the remaining clear exits");
                    }
                    case DANGER -> {
                        Vec3 east = feet.add(1, 0, 0), west = feet.add(-1, 0, 0);
                        helper.assertTrue(edges.stream().noneMatch(edge -> edge.to().feet().distanceToSqr(east) < 1.0E-8
                                        || edge.to().feet().distanceToSqr(west) < 1.0E-8),
                                "fire/cactus beside the empty destination must prevent walking through its danger halo");
                    }
                    case MUTATION -> {
                        Vec3 end = feet.add(1, 0, 0);
                        TraversalEdge east = edges.stream().filter(edge -> edge.to().feet().distanceToSqr(end) < 1.0E-8)
                                .findFirst().orElseThrow(() -> new AssertionError("the initial clear flat edge must exist"));
                        for (int y = 3; y <= 5; y++) helper.setBlock(new BlockPos(9, y, 8), Blocks.STONE);
                        // Deliberately do not notify/invalidate the planner cache after changing the world.
                        validation = validator.beginValidation(mob, east, profile);
                        neighbours.close();
                        neighbours = null;
                        return;
                    }
                }
                succeed();
            } catch (RuntimeException | Error failure) {
                finished = true;
                release();
                cache.reset();
                throw failure;
            }
        }

        void succeed() {
            finished = true;
            release();
            helper.assertTrue(cache.stats().transientEntries() == 0,
                    "completed flat planning and validation cursors must release every transient geometry reference");
            cache.reset();
            helper.succeed();
        }

        void release() {
            if (neighbours != null) { neighbours.close(); neighbours = null; }
            if (validation != null) { validation.close(); validation = null; }
            if (mob != null) mob.discard();
        }
    }

    @GameTest(template = "zombies_navigation", batch = "navigation_stair_geometry", timeoutTicks = 400)
    public static void opposingStairTopsRetainTheLowerTreadTransitionAcrossTheirGap(GameTestHelper helper) {
        for (int x = 3; x <= 18; x++) for (int z = 3; z <= 18; z++) for (int y = 0; y <= 8; y++) {
            helper.setBlock(new BlockPos(x, y, z), y <= 1 ? Blocks.STONE : Blocks.AIR);
        }
        helper.setBlock(new BlockPos(8, 2, 8), Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST));
        helper.setBlock(new BlockPos(9, 2, 8), Blocks.STONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST));
        // Reuse only the origin/cursor/resource setup. This scene deliberately does
        // not use FlatGeometryRun.observe's complete-flat-neighbour assertions.
        FlatGeometryRun run = new FlatGeometryRun(helper, FlatScenario.CLEAR);
        try {
            run.neighbours.close();
            run.neighbours = null;
            SurfaceNode from = supportedStart(helper, run.validator, run.mob, run.feet, run.profile, run.cache,
                    "the two-stage stair search must preserve its supported upper-tread origin");
            run.neighbours = run.validator.beginCoarseNeighbours(run.mob, from, run.profile, from.version());
            boolean[] coarse = {true};
            Vec3 eastTop = helper.absoluteVec(new Vec3(9.5, 3, 8.5));
            SurfaceNode target = run.validator.locate(run.mob, eastTop, run.profile);
            helper.assertTrue(target != null && target.feet().distanceToSqr(eastTop) < 1.0E-8,
                    "both facing-away stair tops must be standable even though their upper supports do not connect");
            helper.onEachTick(() -> {
                if (run.finished) return;
                try {
                    var batch = run.validator.advance(run.neighbours, 4, Long.MAX_VALUE);
                    helper.assertTrue(batch.workUsed() <= 4, "the lower-tread search must preserve its small geometry slices");
                    helper.assertTrue(run.mob.position().distanceToSqr(run.feet) < 1.0E-8,
                            "discovering the lower tread must never move the real entity to manufacture support");
                    run.edges.addAll(batch.edges());
                    if (batch.status() != TraversalValidator.ScanStatus.COMPLETE) return;
                    helper.assertTrue(run.edges.stream().noneMatch(edge -> edge.action() == TraversalEdge.Action.WALK
                                    && edge.to().feet().distanceToSqr(eastTop) < 1.0E-8),
                            "a 0.6-wide zombie cannot walk at the same height across the one-block gap between stair tops");
                    if (coarse[0]) {
                        helper.assertTrue(!run.neighbours.exhaustive(),
                                "coarse stair discovery must report its omitted fractional exits instead of claiming complete adjacency");
                        run.neighbours.close();
                        run.neighbours = null;
                        helper.assertTrue(run.cache.stats().transientEntries() == 0,
                                "closing coarse stair discovery must release all temporary geometry and outputs before refinement");
                        run.edges.clear();
                        run.neighbours = run.validator.beginNeighbours(run.mob, from, run.profile, from.version());
                        coarse[0] = false;
                        return;
                    }
                    helper.assertTrue(run.neighbours.exhaustive(),
                            "the full stair pass must report exhaustive adjacency after restoring fractional exits");
                    Vec3 origin = helper.absoluteVec(Vec3.ZERO);
                    helper.assertTrue(run.edges.stream().anyMatch(edge -> {
                        Vec3 relative = edge.to().feet().subtract(origin);
                        // Neighbour-column enumeration may choose either edge of the
                        // lower tread: approximately x=8.8 or x=9.2 for this body width.
                        return edge.action() == TraversalEdge.Action.PRECISE_MOVE
                                && Math.abs(relative.y - 2.5) < 1.0E-8
                                && Math.abs(relative.z - 8.5) < 1.0E-8
                                && relative.x >= 8.79 && relative.x <= 9.21;
                    }), "stair simplification must preserve a real half-block descent into the unsupported upper gap; edges=" + run.edges);
                    run.succeed();
                } catch (RuntimeException | Error failure) {
                    run.finished = true;
                    run.release();
                    run.cache.reset();
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            run.release();
            run.cache.reset();
            throw failure;
        }
    }

    private static final int STAIR_DEADLINE = 3600;
    // A work-count gate is stable across hardware; the previous exhaustive search used over 300k.
    private static final long FIRST_STAIR_ROUTE_GEOMETRY_LIMIT = 100_000;
    private static final BlockPos STAIR_ROOM_SIZE = new BlockPos(48, 20, 54);

    @GameTest(setupTicks = 20, template = "zombies_navigation_distant_stairs", batch = "navigation_distant_stairs", timeoutTicks = STAIR_DEADLINE + 10)
    public static void originalZombieClimbsDistantStairsPastLowStepsToFixedUpperPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "distant-stairs-low-approach", false);
        // The open lower floor is wide enough that searching all nearby positions below the
        // player is much cheaper heuristically than first walking away to the actual stairs.
        for (int x = 0; x < STAIR_ROOM_SIZE.getX(); x++) {
            for (int z = 0; z < STAIR_ROOM_SIZE.getZ(); z++) {
                for (int y = 0; y < STAIR_ROOM_SIZE.getY(); y++) {
                    boolean shell = x == 0 || x == 47 || z == 0 || z == 53 || y == 0 || y == 19;
                    helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        // The target's upper floor covers the start and the short approach steps. A side
        // gallery connects it to the far landing without putting a ceiling over the ascent.
        for (int x = 1; x <= 46; x++) for (int z = 1; z <= 25; z++) {
            helper.setBlock(new BlockPos(x, 14, z), Blocks.STONE);
        }
        for (int x = 2; x <= 6; x++) for (int z = 26; z <= 44; z++) {
            helper.setBlock(new BlockPos(x, 14, z), Blocks.STONE);
        }
        for (int x = 1; x <= 46; x++) for (int z = 45; z <= 50; z++) {
            helper.setBlock(new BlockPos(x, 14, z), Blocks.STONE);
        }
        // Three real low stairs first lead to a raised lower floor. Its near lip is the
        // tempting local high point below the stationary player, but it cannot reach them.
        for (int x = 1; x <= 46; x++) {
            for (int z = 10; z <= 12; z++) {
                int support = z - 9;
                for (int y = 1; y < support; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, support, z), Blocks.STONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, Direction.SOUTH));
            }
            for (int z = 13; z <= 33; z++) for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
        }
        // The only ascent is 27 blocks horizontally from the initial player column;
        // its top is 37 blocks away, both comfortably inside the reported 50-block range.
        for (int x = 22; x <= 26; x++) for (int z = 34; z <= 44; z++) {
            int support = z - 30;
            for (int y = 1; y < support; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, support, z), Blocks.STONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.SOUTH));
        }
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(24.5, 15, 7.5), STAIR_ROOM_SIZE);
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(24, 1, 7));
            DistantStairRun run = new DistantStairRun(helper, fixture, mob);
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
            fixture.close();
            ZombiesNavigationTestTiming.finish(helper, false);
            throw failure;
        }
    }

    private static final class DistantStairRun {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final com.cdp.codpattern.app.zombies.service.ZombiesMobRecycleService recycler;
        final Mob mob;
        final UUID original;
        final Vec3 origin, target, start;
        final List<java.util.Map<String, Object>> trajectory = new ArrayList<>();
        Vec3 previous, firstActivePosition;
        boolean finished, climbedLowSteps, enteredFarAscent, reachedUpperFloor;
        double travelled, farthestFromTarget;
        int requeued, discarded, maxGeometry, maxExpansions;
        long previousGeometry, previousExpansions;
        long firstSearchRequestTick = -1, firstSearchRequestGeometry = -1, firstSearchRequestExpansions = -1;
        long firstActiveEdgeTick = -1, firstActiveEdgeGeometry = -1, firstActiveEdgeExpansions = -1;
        long firstFollowTick = -1, firstFollowGeometry = -1, firstFollowExpansions = -1;
        long firstSearchRequestNanos = -1, firstActiveEdgeNanos = -1, firstObservedRouteMotionTick = -1;

        DistantStairRun(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture, Mob mob) {
            this.helper = helper;
            this.fixture = fixture;
            this.mob = mob;
            original = mob.getUUID();
            origin = helper.absoluteVec(Vec3.ZERO);
            target = fixture.player.position();
            start = mob.position();
            previous = start;
            farthestFromTarget = start.distanceTo(target);
            recycler = fixture.createRecycler();
            recycler.tick(fixture.roomId(), helper.getLevel(), fixture.waveState(), 0);
        }

        void observe() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(original) == mob,
                        "the same original zombie must complete the staircase route");
                helper.assertTrue(fixture.player.isAlive() && fixture.player.position().distanceToSqr(target) < 1.0E-8,
                        "the upper player must stay fixed instead of luring the zombie to the stairs");
                Vec3 position = mob.position(), relative = position.subtract(origin);
                double moved = position.distanceTo(previous);
                helper.assertTrue(moved < 2, "all stair progress must use real physical movement without teleportation");
                travelled += moved;
                farthestFromTarget = Math.max(farthestFromTarget, position.distanceTo(target));
                previous = position;
                climbedLowSteps |= relative.z >= 12 && relative.z <= 16 && relative.y >= 3.5 && relative.y < 5;
                enteredFarAscent |= relative.z >= 34 && relative.z <= 45 && relative.x >= 21 && relative.x <= 28
                        && relative.y > 4.5;
                reachedUpperFloor |= relative.y >= 14.95;
                var runtime = com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime.of(mob);
                if (runtime != null) {
                    var stats = runtime.planningStats();
                    // Observe before recycling can unregister the controller. These are tick-granularity
                    // observations and cumulative planner counters, not wall-clock or CPU timings.
                    if (firstSearchRequestTick < 0 && stats.requests() > 0) {
                        firstSearchRequestTick = helper.getTick();
                        firstSearchRequestGeometry = stats.geometry();
                        firstSearchRequestExpansions = stats.expansions();
                        firstSearchRequestNanos = stats.nanos();
                    }
                    TraversalEdge active = runtime.activeEdge(mob);
                    if (firstActiveEdgeTick < 0 && active != null) {
                        firstActiveEdgeTick = helper.getTick();
                        firstActiveEdgeGeometry = stats.geometry();
                        firstActiveEdgeExpansions = stats.expansions();
                        firstActiveEdgeNanos = stats.nanos();
                        firstActivePosition = position;
                        helper.assertTrue(firstActiveEdgeGeometry - firstSearchRequestGeometry < FIRST_STAIR_ROUTE_GEOMETRY_LIMIT,
                                "the initial distant-stair route must avoid exhaustive fractional search below the player");
                    }
                    if (firstFollowTick < 0 && active != null && runtime.describe(mob).contains("phase=FOLLOW,")) {
                        firstFollowTick = helper.getTick();
                        firstFollowGeometry = stats.geometry();
                        firstFollowExpansions = stats.expansions();
                    }
                    // FOLLOW can be set before its execution proof is ready. Record physical
                    // displacement separately instead of treating that phase as actual motion.
                    if (firstObservedRouteMotionTick < 0 && active != null && firstActivePosition != null
                            && position.distanceToSqr(firstActivePosition) >= 0.1 * 0.1)
                        firstObservedRouteMotionTick = helper.getTick();
                    int geometry = (int) (stats.geometry() - previousGeometry);
                    int expansions = (int) (stats.expansions() - previousExpansions);
                    maxGeometry = Math.max(maxGeometry, geometry);
                    maxExpansions = Math.max(maxExpansions, expansions);
                    helper.assertTrue(geometry <= 256 && expansions <= 1024 && runtime.schedulerMetrics().geometry() <= 1024
                                    && runtime.schedulerMetrics().expansions() <= 4096,
                            "distant stair searches must retain the existing room/server work budgets");
                    previousGeometry = stats.geometry();
                    previousExpansions = stats.expansions();
                }
                var recycled = recycler.tick(fixture.roomId(), helper.getLevel(), fixture.waveState(), helper.getTick());
                requeued += recycled.requeued();
                discarded += recycled.discarded();
                helper.assertTrue(requeued == 0 && discarded == 0,
                        "the reachable upper player cannot be reached by replacing or recycling the original zombie");
                if (helper.getTick() % 20 == 0) {
                    trajectory.add(java.util.Map.of("tick", helper.getTick(), "position", point(relative), "onGround", mob.onGround(),
                            "execution", runtime == null ? "legacy" : runtime.describe(mob)));
                }
                double reach = mob.getBbWidth() * 2.0;
                boolean arrived = mob.onGround() && Math.abs(mob.getY() - target.y) < 0.05
                        && mob.getTarget() == fixture.player
                        && mob.distanceToSqr(fixture.player) <= reach * reach + fixture.player.getBbWidth()
                        && mob.getSensing().hasLineOfSight(fixture.player);
                if (arrived) {
                    helper.assertTrue(firstObservedRouteMotionTick >= firstActiveEdgeTick && firstActiveEdgeTick >= 0,
                            "a completed route must record both its first edge and subsequent physical movement");
                    helper.assertTrue(climbedLowSteps && enteredFarAscent && reachedUpperFloor,
                            "arrival must include the low stairs, far ascent, and actual upper-floor traversal");
                    helper.assertTrue(farthestFromTarget > start.distanceTo(target) + 15,
                            "the zombie must first walk away from the upper player to find the stairs");
                    finish(true, "same original zombie climbed both stair runs to the fixed upper player");
                } else if (helper.getTick() >= STAIR_DEADLINE) {
                    finish(false, "original zombie failed to reach the stationary upper player within " + STAIR_DEADLINE
                            + " ticks; relativePosition=" + relative + ", execution=" + (runtime == null ? "legacy" : runtime.describe(mob)));
                }
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false, failure.getMessage());
                throw failure;
            }
        }

        void finish(boolean success, String reason) {
            if (finished) return;
            finished = true;
            var runtime = com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime.of(mob);
            java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
            report.put("scenario", "stationary player 14 blocks above original zombie, distant stairs after three low approach steps");
            report.put("success", success);
            report.put("reason", reason);
            report.put("uuid", original.toString());
            report.put("mobMovedByFixture", false);
            report.put("targetMovedByFixture", false);
            report.put("elapsedTicks", helper.getTick());
            report.put("frozenDeadlineTicks", STAIR_DEADLINE);
            report.put("start", point(start.subtract(origin)));
            report.put("target", point(target.subtract(origin)));
            report.put("end", point(mob.position().subtract(origin)));
            report.put("floorSeparation", 14);
            report.put("realStairBlocks", true);
            report.put("climbedLowSteps", climbedLowSteps);
            report.put("enteredFarAscent", enteredFarAscent);
            report.put("reachedUpperFloor", reachedUpperFloor);
            report.put("travelledDistance", travelled);
            report.put("farthestTargetDistance", farthestFromTarget);
            report.put("requeued", requeued);
            report.put("discarded", discarded);
            report.put("maxRoomGeometryPerTick", maxGeometry);
            report.put("maxRoomExpansionsPerTick", maxExpansions);
            report.put("planningMilestoneMeasurement", "observed once per GameTest tick; cumulative room planner work; -1 means not observed");
            report.put("firstSearchRequestTick", firstSearchRequestTick);
            report.put("firstSearchRequestGeometry", firstSearchRequestGeometry);
            report.put("firstSearchRequestExpansions", firstSearchRequestExpansions);
            report.put("firstActiveEdgeTick", firstActiveEdgeTick);
            report.put("firstActiveEdgeGeometry", firstActiveEdgeGeometry);
            report.put("firstActiveEdgeExpansions", firstActiveEdgeExpansions);
            report.put("firstFollowTick", firstFollowTick);
            report.put("firstFollowGeometry", firstFollowGeometry);
            report.put("firstFollowExpansions", firstFollowExpansions);
            report.put("firstActiveEdgePlanningNanos", firstActiveEdgeNanos < 0 || firstSearchRequestNanos < 0
                    ? -1 : firstActiveEdgeNanos - firstSearchRequestNanos);
            report.put("firstObservedRouteMotionTick", firstObservedRouteMotionTick);
            report.put("firstRequestToObservedMotionTicks", firstSearchRequestTick < 0 || firstObservedRouteMotionTick < 0
                    ? -1 : firstObservedRouteMotionTick - firstSearchRequestTick);
            report.put("routeMotionMeasurement", "first observed displacement of at least 0.1 blocks from the first active edge position while a route remains active");
            report.put("firstRequestToActiveEdgeTicks", firstSearchRequestTick < 0 || firstActiveEdgeTick < 0
                    ? -1 : firstActiveEdgeTick - firstSearchRequestTick);
            report.put("firstRequestToFollowTicks", firstSearchRequestTick < 0 || firstFollowTick < 0
                    ? -1 : firstFollowTick - firstSearchRequestTick);
            report.put("firstRouteGeometryLimit", FIRST_STAIR_ROUTE_GEOMETRY_LIMIT);
            report.put("runtime", fixture.spawnService.navigationRuntimeMetrics());
            report.put("execution", runtime == null ? "legacy" : runtime.describe(mob));
            report.put("trajectory", trajectory);
            recycler.reset();
            fixture.close();
            ZombiesNavigationTestReport.write("distant-stairs-low-approach", report);
            if (success) ZombiesNavigationTestTiming.succeed(helper);
            else ZombiesNavigationTestTiming.fail(helper, reason);
        }
    }

    private static List<Double> point(Vec3 value) { return List.of(value.x, value.y, value.z); }
}
