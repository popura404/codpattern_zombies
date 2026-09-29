package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/** Behavior regressions for executable recovery, using real room spawning and stationary players. */
@Mod.EventBusSubscriber(modid = "codpattern_zombies", bus = Mod.EventBusSubscriber.Bus.MOD)
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationRecoveryGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final String SERIES_TEMPLATE = "zombies_navigation_recovery_long";
    private static final String BATCH = "zombies_ground_navigation_recovery";
    private static final int DEADLINE = 500;
    private static final int ALTERNATIVE_DEADLINE = 600;
    private static final int SERIES_DEADLINE = 2600;
    private static final int WALL_REMOVAL_TICK = 180;
    private static final int WALL_PLANE = 10;
    private static final double TARGET_ALONG = 10.5D;

    private static Block halfWall;
    private static Block blockedHalfWall;
    private static Block rotatedThinWall;

    private ZombiesNavigationRecoveryGameTests() {
    }

    @SubscribeEvent
    public static void registerTestBlocks(RegisterEvent event) {
        // These synthetic blocks must never become part of a normal server's registry/save format.
        if (!ForgeGameTestHooks.isGametestServer()) {
            return;
        }
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
            halfWall = new NavigationWall(false, 8.0D, true);
            blockedHalfWall = new NavigationWall(false, 8.0D, false);
            rotatedThinWall = new NavigationWall(true, 3.0D, true);
            helper.register(ResourceLocation.fromNamespaceAndPath("codpattern_zombies", "navigation_test_half_wall"), halfWall);
            helper.register(ResourceLocation.fromNamespaceAndPath("codpattern_zombies", "navigation_test_blocked_wall"),
                    blockedHalfWall);
            // A different namespace prevents compatibility from depending on the addon's block IDs.
            helper.register(ResourceLocation.fromNamespaceAndPath("navigation_compat_fixture", "thin_wall"), rotatedThinWall);
        });
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 10)
    public static void stationaryZombieBypassesMisleadingHalfThicknessWall(GameTestHelper helper) {
        run(helper, new WallScenario(false, 8, 12, halfWall, false, false));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 10)
    public static void identicalBlockedNodeWallRemainsReachable(GameTestHelper helper) {
        run(helper, new WallScenario(false, 8, 12, blockedHalfWall, false, false));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 10)
    public static void rotatedNonHalfThicknessWallUsesTheSameRecovery(GameTestHelper helper) {
        run(helper, new WallScenario(true, 8, 12, rotatedThinWall, false, false));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 10)
    public static void sideMovementContinuesUntilTheLongWallIsActuallyBypassed(GameTestHelper helper) {
        // One short sideways move still leaves the player behind the same continuous obstacle.
        run(helper, new WallScenario(false, 6, 14, halfWall, true, false));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 10)
    public static void removingFailedWallRestoresPursuitWithoutMovingThePlayer(GameTestHelper helper) {
        // Initially joins both room walls. Removing its middle is the only possible ground passage.
        run(helper, new WallScenario(false, 1, 20, halfWall, false, true));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = ALTERNATIVE_DEADLINE + 10)
    public static void unreachableMainPlayerFallsBackToBottomSlabSurface(GameTestHelper helper) {
        runAlternativeSurface(helper, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM), 0.5D);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = ALTERNATIVE_DEADLINE + 10)
    public static void unreachableMainPlayerFallsBackToClosedBottomTrapdoorSurface(GameTestHelper helper) {
        runAlternativeSurface(helper, Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false).setValue(TrapDoorBlock.HALF, Half.BOTTOM), 3.0D / 16.0D);
    }

    @GameTest(template = SERIES_TEMPLATE, batch = BATCH, timeoutTicks = SERIES_DEADLINE + 10)
    public static void fifthIndependentObstacleStillPermitsRealLocalRecovery(GameTestHelper helper) {
        helper.assertTrue(halfWall != null, "navigation fixture blocks must be registered on the test server");
        // Its declared 24 x 6 x 64 template keeps this room separate from adjacent GameTests.
        buildEmptyRoom(helper, 4, 61);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(10.5D, 1.0D, 59.5D));
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(10, 1, 3));
            mob.getRandom().setSeed(1701L);
            ObstacleSeriesRun run = new ObstacleSeriesRun(helper, fixture, mob);
            run.placeNextWall();
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void runAlternativeSurface(GameTestHelper helper, BlockState surface, double height) {
        buildEmptyRoom(helper, 10);
        // Match the existing fallback fixture: the closer upper player has no stair access.
        for (int x = 3; x <= 13; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
            for (int y = 1; y < 5; y++) {
                helper.setBlock(new BlockPos(x, y, 7), Blocks.STONE);
            }
        }
        // The large surface makes a real height change necessary before entering melee range.
        for (int x = 15; x <= 19; x++) {
            for (int z = 15; z <= 19; z++) {
                helper.setBlock(new BlockPos(x, 1, z), surface);
            }
        }
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(7.5D, 6.0D, 5.5D));
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(7, 1, 8));
            mob.getRandom().setSeed(1701L);
            helper.assertTrue(mob.getTarget() == fixture.player,
                    "the original room target must be the inaccessible upper player");
            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob) != null,
                    "the real room spawn path must install recovery for the surface regression");
            ServerPlayer alternative = fixture.addPlayer(new Vec3(17.5D, 1.0D + height, 17.5D));
            Vec3 originalMain = fixture.player.position();
            Vec3 originalAlternative = alternative.position();
            boolean[] finished = {false};
            helper.onEachTick(() -> {
                if (finished[0]) {
                    return;
                }
                try {
                    helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID()) == mob,
                            "the original zombie must reach the alternate surface without replacement");
                    helper.assertTrue(fixture.player.isAlive() && alternative.isAlive(),
                            "fallback must occur while both room players remain valid and alive");
                    helper.assertTrue(fixture.player.position().distanceToSqr(originalMain) < 1.0E-8D
                                    && alternative.position().distanceToSqr(originalAlternative) < 1.0E-8D,
                            "neither player may move to trigger fallback or repair target height");
                    double reach = mob.getBbWidth() * 2.0D;
                    boolean arrived = mob.getTarget() == alternative
                            && Math.abs(mob.getY() - alternative.getY()) < 0.05D
                            && mob.distanceToSqr(alternative) <= reach * reach + alternative.getBbWidth()
                            && mob.getSensing().hasLineOfSight(alternative);
                    if (arrived) {
                        helper.assertTrue(fixture.spawnService.navigationMetrics().recoveries() > 0,
                                "the alternate player must be approached through a real recovery attempt");
                        finished[0] = true;
                        fixture.close();
                        helper.succeed();
                    } else if (helper.getTick() >= ALTERNATIVE_DEADLINE) {
                        var path = mob.getNavigation().getPath();
                        helper.fail("recovery failed to select and stand on the alternate target's surface; surface="
                                + surface + ", position=" + mob.position() + ", expected=" + originalAlternative
                                + ", selectedAlternative=" + (mob.getTarget() == alternative)
                                + ", canReach=" + (path == null ? "none" : path.canReach())
                                + ", end=" + (path == null ? "none" : path.getEndNode())
                                + ", metrics=" + fixture.spawnService.navigationMetrics());
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

    private static void run(GameTestHelper helper, WallScenario scenario) {
        helper.assertTrue(scenario.block() != null, "navigation fixture blocks must be registered on the test server");
        buildRoom(helper, scenario);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, scenario.position(TARGET_ALONG, 11.5D));
        try {
            Mob mob = fixture.spawn("zombie", BlockPos.containing(scenario.position(TARGET_ALONG, 5.5D)));
            mob.getRandom().setSeed(1701L);
            RecoveryRun run = new RecoveryRun(helper, scenario, fixture, mob);
            run.assertFixture();
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void buildRoom(GameTestHelper helper, WallScenario scenario) {
        buildEmptyRoom(helper, 4);
        for (int along = scenario.first(); along <= scenario.last(); along++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(scenario.blockPosition(along, y), scenario.block());
            }
        }
    }

    private static void buildEmptyRoom(GameTestHelper helper, int ceiling) {
        buildEmptyRoom(helper, ceiling, 21);
    }

    private static void buildEmptyRoom(GameTestHelper helper, int ceiling, int lastZ) {
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= lastZ; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, ceiling, z), Blocks.STONE);
                for (int y = 1; y < ceiling; y++) {
                    boolean border = x == 0 || x == 21 || z == 0 || z == lastZ;
                    helper.setBlock(new BlockPos(x, y, z), border ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }

    private static final class ObstacleSeriesRun {
        private final GameTestHelper helper;
        private final ZombiesNavigationGameTests.Fixture fixture;
        private final Mob mob;
        private final Vec3 origin;
        private final Vec3 stationaryTarget;
        private int completedWalls;
        private int wallPlane;
        private int wallCenter;
        private long localRoutesAtWallStart;
        private long recoveriesAtWallStart;
        private boolean crossedWallEnd;
        private boolean finished;

        private ObstacleSeriesRun(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture, Mob mob) {
            this.helper = helper;
            this.fixture = fixture;
            this.mob = mob;
            origin = helper.absoluteVec(Vec3.ZERO);
            stationaryTarget = fixture.player.position();
        }

        private void placeNextWall() {
            wallPlane = 10 + completedWalls * 10;
            Vec3 relative = mob.position().subtract(origin);
            helper.assertTrue(relative.z + mob.getBbWidth() / 2.0D + 1.0D < wallPlane,
                    "the next obstacle must still be ahead, without placing blocks inside the zombie; " + describe());
            wallCenter = nextRouteColumn(relative);
            for (int x = wallCenter - 2; x <= wallCenter + 2; x++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, wallPlane), halfWall);
                }
            }
            crossedWallEnd = false;
            var metrics = fixture.spawnService.navigationMetrics();
            localRoutesAtWallStart = metrics.localRoutes();
            recoveriesAtWallStart = metrics.recoveries();
        }

        private int nextRouteColumn(Vec3 relative) {
            var path = mob.getNavigation().getPath();
            if (path != null) {
                for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                    BlockPos node = path.getNodePos(i);
                    if (node.getZ() - origin.z >= wallPlane) {
                        return Math.max(4, Math.min(17, (int) Math.floor(node.getX() - origin.x)));
                    }
                }
            }
            Vec3 target = stationaryTarget.subtract(origin);
            double fraction = (wallPlane - relative.z) / (target.z - relative.z);
            double crossingX = relative.x + fraction * (target.x - relative.x);
            return Math.max(4, Math.min(17, (int) Math.floor(crossingX)));
        }

        private void observe() {
            if (finished) {
                return;
            }
            try {
                helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID()) == mob,
                        "all five obstacles must be solved by the original living room zombie");
                helper.assertTrue(fixture.player.position().distanceToSqr(stationaryTarget) < 1.0E-8D,
                        "the player must remain fixed for all five independent recoveries");
                var progress = ZombiesGroundNavigationService.getProgress(mob);
                helper.assertTrue(progress != null, "the original room recovery controller must remain installed");
                Vec3 relative = mob.position().subtract(origin);
                if (completedWalls < 5) {
                    double halfWidth = mob.getBbWidth() / 2.0D;
                    boolean outsideWall = relative.x + halfWidth <= wallCenter - 2
                            || relative.x - halfWidth >= wallCenter + 3;
                    crossedWallEnd |= relative.z >= wallPlane && outsideWall;
                    boolean physicallyPassed = relative.z > wallPlane + 0.5D + halfWidth;
                    if (physicallyPassed && crossedWallEnd && progress.recoveryAttempts() == 0) {
                        var metrics = fixture.spawnService.navigationMetrics();
                        helper.assertTrue(metrics.recoveries() > recoveriesAtWallStart,
                                "each obstacle must cause a new recovery attempt; " + describe());
                        helper.assertTrue(metrics.localRoutes() > localRoutesAtWallStart,
                                "each obstacle, including the fifth, must execute a local route; " + describe());
                        completedWalls++;
                        System.out.println("NAVIGATION_RECOVERY_SERIES completed=" + completedWalls
                                + " tick=" + helper.getTick() + " position=" + relative + " metrics=" + metrics);
                        if (completedWalls < 5) {
                            placeNextWall();
                        }
                    }
                }
                double reach = mob.getBbWidth() * 2.0D;
                if (completedWalls == 5 && mob.distanceToSqr(fixture.player) <= reach * reach + fixture.player.getBbWidth()
                        && mob.getSensing().hasLineOfSight(fixture.player)) {
                    finished = true;
                    fixture.close();
                    helper.succeed();
                } else if (helper.getTick() >= SERIES_DEADLINE) {
                    helper.fail("five independent obstacles exceeded the fixed " + SERIES_DEADLINE
                            + " tick deadline; " + describe());
                }
            } catch (RuntimeException | Error failure) {
                finished = true;
                fixture.close();
                throw failure;
            }
        }

        private String describe() {
            return "completed=" + completedWalls + ", wallPlane=" + wallPlane + ", wallCenter=" + wallCenter
                    + ", position=" + mob.position().subtract(origin)
                    + ", progress=" + ZombiesGroundNavigationService.getProgress(mob)
                    + ", metrics=" + fixture.spawnService.navigationMetrics();
        }
    }

    private static final class RecoveryRun {
        private final GameTestHelper helper;
        private final WallScenario scenario;
        private final ZombiesNavigationGameTests.Fixture fixture;
        private final Mob mob;
        private final Vec3 targetPosition;
        private final Vec3 origin;
        private boolean finished;
        private boolean wallOpened;
        private boolean observedIntermediateSideMovement;
        private boolean crossedAtWallEnd;
        private long previousSearches;

        private RecoveryRun(GameTestHelper helper, WallScenario scenario,
                            ZombiesNavigationGameTests.Fixture fixture, Mob mob) {
            this.helper = helper;
            this.scenario = scenario;
            this.fixture = fixture;
            this.mob = mob;
            targetPosition = fixture.player.position();
            origin = helper.absoluteVec(Vec3.ZERO);
        }

        private void assertFixture() {
            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob) != null,
                    "the real room spawn path must install ground recovery");
            helper.assertFalse(mob.getSensing().hasLineOfSight(fixture.player),
                    "the stationary player must initially be hidden behind the wall");
            BlockPathTypes type = WalkNodeEvaluator.getBlockPathTypeStatic(helper.getLevel(),
                    helper.absolutePos(scenario.blockPosition(10, 1)).mutable());
            BlockPathTypes expected = scenario.block() == blockedHalfWall
                    ? BlockPathTypes.BLOCKED : BlockPathTypes.WALKABLE;
            helper.assertTrue(type == expected,
                    "fixture must expose collision/path-type disagreement; expected=" + expected + ", actual=" + type);
        }

        private void observe() {
            if (finished) {
                return;
            }
            try {
                helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID()) == mob,
                        "the original room zombie must complete the route alive; replacement is not success");
                helper.assertTrue(fixture.player.position().distanceToSqr(targetPosition) < 1.0E-8D,
                        "the target must remain stationary throughout recovery");
                long searches = fixture.spawnService.navigationMetrics().searches();
                helper.assertTrue(searches - previousSearches <= 2,
                        "recovery must respect the existing two-search room budget each tick");
                previousSearches = searches;

                Vec3 relative = mob.position().subtract(origin);
                double along = scenario.along(relative);
                double normal = scenario.normal(relative);
                double halfWidth = mob.getBbWidth() / 2.0D;
                boolean outsideWallEnd = along + halfWidth <= scenario.first()
                        || along - halfWidth >= scenario.last() + 1.0D;
                if (normal >= WALL_PLANE && outsideWallEnd) {
                    crossedAtWallEnd = true;
                }
                if (normal < WALL_PLANE && !outsideWallEnd
                        && Math.abs(along - TARGET_ALONG) >= 1.5D) {
                    observedIntermediateSideMovement = true;
                }

                if (scenario.removeMiddle() && !wallOpened && helper.getTick() >= WALL_REMOVAL_TICK) {
                    helper.assertTrue(normal < WALL_PLANE,
                            "the zombie must not cross the initially closed wall before removal");
                    helper.assertTrue(fixture.spawnService.navigationMetrics().recoveries() > 0,
                            "the wall must have caused a real recovery attempt before it is removed");
                    for (int alongBlock = 8; alongBlock <= 12; alongBlock++) {
                        for (int y = 1; y <= 3; y++) {
                            helper.setBlock(scenario.blockPosition(alongBlock, y), Blocks.AIR);
                        }
                    }
                    wallOpened = true;
                }

                boolean onPlayerSide = normal > WALL_PLANE + scenario.thickness() + halfWidth;
                double reach = mob.getBbWidth() * 2.0D;
                boolean engaging = mob.getSensing().hasLineOfSight(fixture.player)
                        && mob.distanceToSqr(fixture.player) <= reach * reach + fixture.player.getBbWidth();
                if (onPlayerSide && engaging) {
                    helper.assertTrue(scenario.removeMiddle() ? wallOpened : crossedAtWallEnd,
                            "the zombie must physically use the opened passage or go around a wall end");
                    if (scenario.requireIntermediateMovement()) {
                        helper.assertTrue(observedIntermediateSideMovement,
                                "the long-wall fixture must include a sideways advance that has not yet bypassed it");
                    }
                    if (scenario.block() != blockedHalfWall) {
                        helper.assertTrue(fixture.spawnService.navigationMetrics().recoveries() > 0,
                                "misleading wall nodes must exercise the recovery controller");
                    }
                    finished = true;
                    fixture.close();
                    helper.succeed();
                    return;
                }
                if (helper.getTick() >= DEADLINE) {
                    helper.fail("stationary-target recovery did not reach the far side before its fixed deadline; "
                            + describe(relative));
                }
            } catch (RuntimeException | Error failure) {
                finished = true;
                fixture.close();
                throw failure;
            }
        }

        private String describe(Vec3 relative) {
            var path = mob.getNavigation().getPath();
            return "position=" + relative + ", wall=" + scenario + ", middleOpened=" + wallOpened
                    + ", intermediateSideMovement=" + observedIntermediateSideMovement
                    + ", wallEndCrossing=" + crossedAtWallEnd
                    + ", canReach=" + (path == null ? "none" : path.canReach())
                    + ", end=" + (path == null ? "none" : path.getEndNode())
                    + ", progress=" + ZombiesGroundNavigationService.getProgress(mob)
                    + ", metrics=" + fixture.spawnService.navigationMetrics()
                    + ", goals=" + mob.goalSelector.getRunningGoals()
                    .map(goal -> goal.getGoal().getClass().getSimpleName()).toList();
        }
    }

    private record WallScenario(boolean rotated, int first, int last, Block block,
                                boolean requireIntermediateMovement, boolean removeMiddle) {
        private Vec3 position(double along, double normal) {
            return rotated ? new Vec3(normal, 1.0D, along) : new Vec3(along, 1.0D, normal);
        }

        private BlockPos blockPosition(int along, int y) {
            return rotated ? new BlockPos(WALL_PLANE, y, along) : new BlockPos(along, y, WALL_PLANE);
        }

        private double along(Vec3 position) {
            return rotated ? position.z : position.x;
        }

        private double normal(Vec3 position) {
            return rotated ? position.x : position.z;
        }

        private double thickness() {
            return rotated ? 3.0D / 16.0D : 0.5D;
        }
    }

    private static final class NavigationWall extends Block {
        private final VoxelShape shape;
        private final boolean pathable;

        private NavigationWall(boolean rotated, double thickness, boolean pathable) {
            super(BlockBehaviour.Properties.of().strength(1.0F).noOcclusion());
            shape = rotated ? Block.box(0, 0, 0, thickness, 16, 16)
                    : Block.box(0, 0, 0, 16, 16, thickness);
            this.pathable = pathable;
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position,
                                   CollisionContext context) {
            return shape;
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos position,
                                            CollisionContext context) {
            return shape;
        }

        @Override
        public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos position,
                                      PathComputationType type) {
            return pathable && super.isPathfindable(state, level, position, type);
        }
    }
}
