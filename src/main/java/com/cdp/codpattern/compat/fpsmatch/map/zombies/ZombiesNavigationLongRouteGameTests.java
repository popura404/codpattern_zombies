package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestTiming;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NativeNavigationGuard;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Physical acceptance fixtures: no path flag, replacement entity, or test-driven mob movement can pass. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationLongRouteGameTests {
    // Frozen before running either engine. Independent reference traversal calibration remains a release gate.
    private static final int DETOUR_DEADLINE = 3200;
    private static final int DROP_DEADLINE = 600;

    private ZombiesNavigationLongRouteGameTests() { }

    @GameTest(setupTicks = 20, template = "zombies_navigation_detour", batch = "navigation_long_route", timeoutTicks = DETOUR_DEADLINE + 10)
    public static void originalZombieWalksBeyond128BlocksAroundContinuousWall(GameTestHelper helper) {
        buildShell(helper, 14, 7, 96);
        for (int z = 1; z <= 88; z++) {
            for (int y = 1; y <= 5; y++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(10.5D, 1, 3.5D),
                new BlockPos(14, 7, 96));
        start(helper, fixture, new BlockPos(3, 1, 3), "detour-over-128", DETOUR_DEADLINE, 0);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation_drop", batch = "navigation_deep_drop", timeoutTicks = DROP_DEADLINE + 10)
    public static void originalZombieDrops8BlocksThenResumesChase(GameTestHelper helper) {
        drop(helper, 8);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation_drop", batch = "navigation_deep_drop", timeoutTicks = DROP_DEADLINE + 10)
    public static void originalZombieDrops32BlocksThenResumesChase(GameTestHelper helper) {
        drop(helper, 32);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation_drop", batch = "navigation_deep_drop", timeoutTicks = DROP_DEADLINE + 10)
    public static void originalZombieDrops64BlocksThenResumesChase(GameTestHelper helper) {
        drop(helper, 64);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation_drop", batch = "navigation_deep_drop", timeoutTicks = DROP_DEADLINE + 10)
    public static void originalZombieAtTwoHealthDrops64BlocksThenResumesChase(GameTestHelper helper) {
        drop(helper, 64, 2.0F);
    }

    @GameTest(setupTicks = 20, template = "zombies_navigation_drop", batch = "navigation_deep_drop", timeoutTicks = DROP_DEADLINE + 10)
    public static void originalZombieAtTwoHealthLeavesSlowSoulSandPlatformThenDropsEightBlocks(GameTestHelper helper) {
        // A full-health zombie accepts this drop natively; low health makes the slow managed departure observable.
        drop(helper, 8, 2.0F, Blocks.SOUL_SAND, "-soul-sand");
    }

    private static void drop(GameTestHelper helper, int depth) {
        drop(helper, depth, null);
    }

    private static void drop(GameTestHelper helper, int depth, Float startingHealth) {
        drop(helper, depth, startingHealth, Blocks.STONE, startingHealth == null ? "" : "-two-health");
    }

    static void supplementalDrop(GameTestHelper helper, int depth, Float startingHealth) {
        drop(helper, depth, startingHealth, Blocks.STONE,
                "-matrix-" + (startingHealth == null ? "full-health" : "two-health"));
    }

    private static void drop(GameTestHelper helper, int depth, Float startingHealth, Block support, String suffix) {
        buildShell(helper, 20, 70, 20);
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, depth, z), support);
            }
        }
        // The player is directly below the platform; a vertical ray from the start cannot be a valid DROP.
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(8.5D, 1, 8.5D),
                new BlockPos(20, 70, 20));
        start(helper, fixture, new BlockPos(8, depth + 1, 8),
                "drop-" + depth + suffix, DROP_DEADLINE, depth, startingHealth);
    }

    private static void start(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture,
                              BlockPos spawn, String scenario, int deadline, int dropDepth) {
        start(helper, fixture, spawn, scenario, deadline, dropDepth, null);
    }

    private static void start(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture,
                              BlockPos spawn, String scenario, int deadline, int dropDepth, Float startingHealth) {
        ZombiesNavigationTestTiming.begin(helper, scenario, true);
        BlockPos size = dropDepth > 0 ? new BlockPos(20, 70, 20) : new BlockPos(14, 7, 96);
        ZombiesMap map = new ZombiesMap(helper.getLevel(), fixture.roomId().mapName(),
                new AreaData(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(size)));
        FPSMCore.getInstance().registerMap(map.getGameType(), map);
        try {
            Mob mob = fixture.spawn("zombie", spawn);
            mob.getRandom().setSeed(ZombiesNavigationTestReport.SEED);
            if (startingHealth != null) mob.setHealth(startingHealth);
            Run run = new Run(helper, fixture, map, mob, scenario, deadline, dropDepth);
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
            fixture.close();
            closeMap(map);
            throw failure;
        }
    }

    private static void closeMap(ZombiesMap map) {
        try {
            map.resetGame();
        } finally {
            FPSMCore.getInstance().unregisterMap(map);
            map.getMapTeams().retireCreatedScoreboardTeams();
        }
    }

    private static final class Run {
        private final GameTestHelper helper;
        private final ZombiesNavigationGameTests.Fixture fixture;
        private final ZombiesMap map;
        private final Mob mob;
        private final UUID originalId;
        private final Vec3 start;
        private final Vec3 target;
        private final String scenario;
        private final int deadline;
        private final int dropDepth;
        private final float initialHealth;
        private Float landingHealth;
        private boolean fractionalDeparture;
        private final List<Map<String, Object>> trajectory = new ArrayList<>();
        private Vec3 previous;
        private double travelled;
        private double farthest;
        private boolean descending;
        private boolean landed;
        private boolean finished;
        private final Set<String> verifiedGuardPhases = new HashSet<>();
        private String armedGuardPhase;
        private long guardArmedTick;

        private Run(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture, ZombiesMap map, Mob mob,
                    String scenario, int deadline, int dropDepth) {
            this.helper = helper;
            this.fixture = fixture;
            this.map = map;
            this.mob = mob;
            this.originalId = mob.getUUID();
            this.start = mob.position();
            this.previous = start;
            this.target = fixture.player.position();
            this.scenario = scenario;
            this.deadline = deadline;
            this.dropDepth = dropDepth;
            this.initialHealth = mob.getHealth();
        }

        private void observe() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved()
                                && helper.getLevel().getEntity(originalId) == mob,
                        "the original UUID must remain alive; replacement/recycling does not count as arrival");
                helper.assertTrue(fixture.player.position().distanceToSqr(target) < 1.0E-8D,
                        "the stationary target must not move to repair the route");
                observeGuard();
                if (scenario.endsWith("-soul-sand")) {
                    helper.assertTrue(mob.getMaxFallDistance() < dropDepth,
                            "the slow-floor fixture must require a drop beyond the native fall threshold");
                    LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
                    var edge = runtime == null ? null : runtime.activeEdge(mob);
                    if (edge != null && edge.action() == com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge.Action.DROP
                            && mob.onGround() && runtime.describe(mob).contains("phase=COMMIT,")) {
                        helper.assertTrue(Math.abs(edge.from().feet().y - (start.y - 0.125D)) < 1.0E-6D,
                                "soul sand departure must use its actual 14/16 support height");
                        fractionalDeparture = true;
                    }
                }
                double displacement = mob.position().distanceTo(previous);
                helper.assertTrue(displacement < 12.0D, "a discontinuous teleport cannot count as traversal");
                travelled += displacement;
                farthest = Math.max(farthest, mob.position().distanceTo(target));
                descending |= !mob.onGround() && mob.getY() < previous.y - 0.01D;
                landed |= descending && mob.onGround() && Math.abs(mob.getY() - target.y) < 0.05D;
                if (landed && landingHealth == null) landingHealth = mob.getHealth();
                if (initialHealth == 2.0F) {
                    helper.assertTrue(initialHealth == 2.0F && mob.getHealth() == initialHealth,
                            "the residual-health DROP must retain exactly two health through production fall protection");
                }
                if (scenario.contains("-matrix-")) {
                    helper.assertTrue(mob.getHealth() == initialHealth,
                            "every supplemental depth/health case must preserve its initial health");
                }
                previous = mob.position();
                if (helper.getTick() % 20 == 0) {
                    trajectory.add(Map.of("tick", helper.getTick(), "position", position(mob.position()),
                            "onGround", mob.onGround()));
                }
                double reach = mob.getBbWidth() * 2.0D;
                boolean arrived = mob.onGround() && Math.abs(mob.getY() - target.y) < 0.05D
                        && mob.distanceToSqr(fixture.player) <= reach * reach + fixture.player.getBbWidth()
                        && mob.getSensing().hasLineOfSight(fixture.player) && mob.getTarget() == fixture.player;
                if (arrived) {
                    helper.assertTrue(dropDepth > 0 ? descending && landed : travelled > 128.0D && farthest > 80.0D,
                            "arrival must include the required real detour or natural descent and landing");
                    helper.assertTrue(LayeredNavigationRuntime.of(mob) == null
                                    || verifiedGuardPhases.containsAll(requiredGuardPhases()),
                            "complete entity ticks must protect continued route execution in each required phase: expected "
                                    + requiredGuardPhases() + ", observed " + verifiedGuardPhases);
                    helper.assertTrue(!scenario.endsWith("-soul-sand") || fractionalDeparture,
                            "the original entity must naturally execute a DROP from the slow fractional support");
                    finish(true, "original entity reached the target on the correct support surface");
                } else if (helper.getTick() >= deadline) {
                    finish(false, "fixed traversal deadline exceeded at " + mob.position());
                }
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                if (!finished) {
                    finished = true;
                    report(false, failure.getMessage());
                    fixture.close();
                    closeMap(map);
                }
                throw failure;
            }
        }

        private Set<String> requiredGuardPhases() {
            // An advancing ground route keeps MOVE; leap and door fixtures cover native handoffs.
            return dropDepth == 0 ? Set.of("FOLLOW")
                    : dropDepth == 64 || scenario.endsWith("-soul-sand") ? Set.of("COMMIT", "FALL") : Set.of();
        }

        private void observeGuard() {
            LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
            if (runtime == null || requiredGuardPhases().isEmpty()) return;
            String description = runtime.describe(mob);
            String phase = description.substring(description.indexOf("phase=") + 6).split(",", 2)[0];
            boolean controllerRunning = mob.goalSelector.getRunningGoals()
                    .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals("Controller"));
            if (armedGuardPhase != null && helper.getTick() > guardArmedTick) {
                boolean stillProtected = phase.equals(armedGuardPhase)
                        || armedGuardPhase.equals("COMMIT") && phase.equals("FALL")
                        || armedGuardPhase.equals("FOLLOW") && phase.equals("PRECISE");
                // NATIVE is retained as a label during plan waiting after the goal has resumed.
                // Only an actual released MOVE owner proves that this is a native scheduling window.
                stillProtected &= armedGuardPhase.equals("NATIVE") ? !controllerRunning : controllerRunning;
                if (stillProtected) {
                    boolean nativeWindow = armedGuardPhase.equals("NATIVE");
                    helper.assertTrue(NativeNavigationGuard.pendingRecomputation(mob) == nativeWindow,
                            "pending native recomputation must " + (nativeWindow ? "remain available" : "be cancelled")
                                    + " after a complete entity tick in " + armedGuardPhase + ": " + description);
                    var path = mob.getNavigation().getPath();
                    if (!nativeWindow) {
                        helper.assertTrue(armedGuardPhase.equals("FOLLOW")
                                        ? path == null || path.getNodeCount() <= 2 : path == null,
                                "delayed native player path must not replace the short path/drop command");
                    }
                    verifiedGuardPhases.add(armedGuardPhase);
                }
                // A legitimate handoff during the observation tick is retried in a later stable phase.
                armedGuardPhase = null;
            }
            if (armedGuardPhase == null && requiredGuardPhases().contains(phase)
                    && !verifiedGuardPhases.contains(phase)
                    && (phase.equals("NATIVE")
                        ? !controllerRunning && verifiedGuardPhases.contains("FOLLOW") : controllerRunning)) {
                // Public navigation APIs establish and then throttle a native request; only the guard reads its flag.
                mob.getNavigation().createPath(fixture.player, 0);
                mob.getNavigation().recomputePath();
                mob.getNavigation().recomputePath();
                helper.assertTrue(NativeNavigationGuard.pendingRecomputation(mob),
                        "the test must create a real pending native recomputation before checking " + phase);
                armedGuardPhase = phase;
                guardArmedTick = helper.getTick();
            }
        }

        private void finish(boolean success, String reason) {
            finished = true;
            try {
                report(success, reason);
            } finally {
                fixture.close();
                closeMap(map);
            }
            helper.assertTrue(success, reason);
            ZombiesNavigationTestTiming.succeed(helper);
        }

        private void report(boolean success, String reason) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uuid", originalId.toString());
            result.put("targetUuid", fixture.player.getUUID().toString());
            result.put("start", position(start));
            result.put("end", position(mob.position()));
            result.put("target", position(target));
            result.put("elapsedTicks", helper.getTick());
            result.put("frozenDeadlineTicks", deadline);
            if (scenario.contains("-matrix-")) {
                result.put("supplementalCoverage", true);
                result.put("preImplementationBaseline", false);
                result.put("matrixDepth", dropDepth);
                result.put("matrixHealth", initialHealth == 2.0F ? "residual" : "full");
                result.put("difficulty", helper.getLevel().getDifficulty().getSerializedName());
                result.put("wave", 1);
            }
            result.put("independentReferenceTraversalMeasured", false);
            result.put("travelledDistance", travelled);
            result.put("farthestTargetDistance", farthest);
            result.put("naturalDescentObserved", descending);
            result.put("landingObserved", landed);
            result.put("initialHealth", initialHealth);
            result.put("landingHealth", landingHealth);
            result.put("fractionalDepartureObserved", fractionalDeparture);
            result.put("guardPhasesVerifiedAcrossEntityTick", verifiedGuardPhases);
            result.put("guardPhasesRequired", requiredGuardPhases());
            result.put("runtime", fixture.spawnService.navigationRuntimeMetrics());
            LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
            result.put("execution", runtime == null ? "legacy" : runtime.describe(mob));
            result.put("trajectory", trajectory);
            result.put("success", success);
            result.put("reason", reason);
            ZombiesNavigationTestReport.write(scenario, result);
        }
    }

    private static List<Double> position(Vec3 position) {
        return List.of(position.x, position.y, position.z);
    }

    private static void buildShell(GameTestHelper helper, int width, int height, int depth) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, height - 1, z), Blocks.STONE);
                if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
                    for (int y = 1; y < height - 1; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }
}
