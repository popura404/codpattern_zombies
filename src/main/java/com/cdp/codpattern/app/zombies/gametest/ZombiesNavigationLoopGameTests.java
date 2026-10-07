package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobRecycleService;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P0-4/P5-3: paired real-entity observations of moving pursuit and a fixed unreachable target. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationLoopGameTests {
    // Frozen before the first execution. Player speed gives two square circuits in 1360 ticks.
    private static final int MOVING_DEADLINE = 1800;
    private static final int FIXED_DEADLINE = 1000;
    private static final double PLAYER_SPEED = .10;
    private static final double SIDE = 17, PERIMETER = SIDE * 4, PLAYER_INITIAL_LEAD = 12;
    private static final String TEMPLATE = "zombies_navigation";
    private static final String BATCH = "navigation_loop_pair";

    private ZombiesNavigationLoopGameTests() { }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = MOVING_DEADLINE + 10)
    public static void originalZombieFollowsMovingPlayerForTwoRealCircuits(GameTestHelper helper) {
        start(helper, true);
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = FIXED_DEADLINE + 10)
    public static void fixedEnclosedPlayerCannotRenewUnproductiveCircuitsForever(GameTestHelper helper) {
        start(helper, false);
    }

    private static void start(GameTestHelper helper, boolean moving) {
        ZombiesNavigationTestTiming.begin(helper, moving ? "loop-moving-player" : "loop-fixed-unreachable-player", true);
        buildRing(helper);
        Vec3 playerStart = moving ? squarePoint(PLAYER_INITIAL_LEAD) : new Vec3(11.5, 1, 11.5);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, playerStart, new BlockPos(24, 12, 24));
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(3, 1, 3));
            Run run = new Run(helper, fixture, mob, moving);
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
            fixture.close(); throw failure;
        }
    }

    private static void buildRing(GameTestHelper helper) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 10, z), Blocks.STONE);
            for (int y = 1; y < 10; y++) {
                boolean shell = x == 0 || z == 0 || x == 23 || z == 23;
                boolean middle = x >= 6 && x <= 17 && z >= 6 && z <= 17;
                boolean enclosure = middle && (y == 6 || y < 6 && (x == 6 || x == 17 || z == 6 || z == 17));
                helper.setBlock(new BlockPos(x, y, z), shell || enclosure ? Blocks.STONE : Blocks.AIR);
            }
        }
    }

    private static Vec3 squarePoint(double distance) {
        double wrapped = distance % PERIMETER;
        int side = (int) (wrapped / SIDE);
        double along = wrapped - side * SIDE;
        return switch (side) {
            case 0 -> new Vec3(3.5 + along, 1, 3.5);
            case 1 -> new Vec3(20.5, 1, 3.5 + along);
            case 2 -> new Vec3(20.5 - along, 1, 20.5);
            default -> new Vec3(3.5, 1, 20.5 - along);
        };
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMobRecycleService recycler;
        final Mob mob;
        final UUID originalId;
        final boolean moving;
        final Vec3 origin, playerStart;
        final GateTracker gates = new GateTracker();
        final List<Map<String, Object>> trace = new ArrayList<>();
        final long started;
        Vec3 previous;
        double travelled;
        boolean finished, loopObserved;
        int requeued, discarded;
        long recycleTick = -1;
        ZombiesGroundNavigationService.ProgressSnapshot lastProgress;
        String recycleReason = "none";

        Run(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture, Mob mob, boolean moving) {
            this.helper = helper; this.fixture = fixture; this.mob = mob; this.moving = moving;
            recycler = fixture.createRecycler(); originalId = mob.getUUID(); previous = mob.position();
            origin = helper.absoluteVec(Vec3.ZERO); playerStart = fixture.player.position();
            started = helper.getLevel().getGameTime();
            recycler.tick(fixture.roomId(), helper.getLevel(), fixture.waveState(), 0);
        }

        void tick() {
            if (finished) return;
            try {
                long tick = helper.getTick();
                if (moving) {
                    // The fixture drives only the player. The mob retains its real room/native Goals and physics.
                    Vec3 point = helper.absoluteVec(squarePoint(PLAYER_INITIAL_LEAD + tick * PLAYER_SPEED));
                    fixture.player.moveTo(point.x, point.y, point.z, 0, 0);
                    fixture.player.setDeltaMovement(Vec3.ZERO);
                    fixture.player.setHealth(fixture.player.getMaxHealth());
                } else helper.assertTrue(fixture.player.position().distanceToSqr(playerStart) < 1.0E-8,
                        "the enclosed target must stay fixed for the entire unproductive-route observation");
                helper.assertTrue(fixture.player.isAlive(), "the target must remain valid throughout the paired fixture");

                Vec3 current = mob.position();
                double tickTravel = current.distanceTo(previous);
                helper.assertTrue(tickTravel < 2,
                        "a flat-ring mob must move through native walking physics; a same-UUID teleport cannot count as pursuit");
                travelled += tickTravel;
                gates.sample(previous.subtract(origin), current.subtract(origin), tick);
                previous = current;
                LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
                boolean loop = runtime != null && runtime.loopDetected(mob);
                loopObserved |= loop;
                lastProgress = ZombiesGroundNavigationService.getProgress(mob);
                var recycled = recycler.tick(fixture.roomId(), helper.getLevel(), fixture.waveState(), tick);
                requeued += recycled.requeued(); discarded += recycled.discarded();
                if (recycled.requeued() + recycled.discarded() > 0) {
                    recycleTick = tick;
                    recycleReason = lastProgress == null ? "legacy-unmanaged" : lastProgress.geometricFailure()
                            ? "confirmed-geometric-failure" : "finite-wait-exhausted:" + lastProgress.planningReason();
                }
                if (tick % 20 == 0) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("tick", tick); sample.put("mob", current.toString());
                    sample.put("player", fixture.player.position().toString()); sample.put("gateTransitions", gates.longest);
                    sample.put("loop", loop); sample.put("progress", ZombiesGroundNavigationService.getProgress(mob));
                    trace.add(sample);
                }

                if (moving) {
                    helper.assertTrue(helper.getLevel().getEntity(originalId) == mob && mob.isAlive() && !mob.isRemoved(),
                            "normal circular pursuit must retain the original live mob, never replace it");
                    helper.assertTrue(!loop && requeued == 0 && discarded == 0,
                            "a substantively moving player must not trigger fixed-target loop recovery or recycling");
                    helper.assertTrue(fixture.waveState().activeZombies() == 1 && fixture.waveState().remainingBudget() == 0
                                    && fixture.activeRoomCount() == 1 && fixture.ownedRoomCount() == 1,
                            "real circular pursuit must preserve one active entity and consume exactly one spawn budget");
                    if (tick * PLAYER_SPEED >= PERIMETER * 2 && gates.clockwiseLongest >= 8 && travelled >= 88) {
                        finish(true, "original UUID completed two ordered circuits while pursuing a moving player"); return;
                    }
                } else {
                    helper.assertTrue(gates.longest < 12,
                            "an inaccessible fixed target cannot grant progress for a third complete unproductive circuit");
                    helper.assertFalse(mob.getSensing().hasLineOfSight(fixture.player),
                            "the fixed-target fixture must not acquire a combat sight line through the enclosure");
                    if (requeued + discarded > 0) {
                        helper.assertTrue(helper.getLevel().getGameTime() - started >= 320,
                                "fixed-target recycling must respect the real finite recovery window");
                        helper.assertTrue(requeued == 1 && discarded == 0 && mob.isRemoved()
                                        && helper.getLevel().getEntity(originalId) == null,
                                "the first failed original mob must be terminated and requeued exactly once");
                        helper.assertTrue(fixture.waveState().activeZombies() == 0 && fixture.waveState().remainingBudget() == 1
                                        && fixture.waveState().activeZombieEntityIdsSnapshot().isEmpty()
                                        && fixture.activeRoomCount() == 0 && fixture.ownedRoomCount() == 0,
                                "recycling must reconcile real wave budget, active IDs, ownership and room counters");
                        recycler.tick(fixture.roomId(), helper.getLevel(), fixture.waveState(), tick);
                        helper.assertTrue(fixture.waveState().remainingBudget() == 1,
                                "observing the same completed recycle twice must not duplicate budget");
                        finish(true, "fixed unreachable target exhausted its finite real recovery allowance"); return;
                    }
                    helper.assertTrue(helper.getLevel().getEntity(originalId) == mob && mob.isAlive(),
                            "the original mob cannot disappear before a recorded recycler decision");
                }
                if (tick >= (moving ? MOVING_DEADLINE : FIXED_DEADLINE))
                    finish(false, moving ? "two real pursuit circuits not completed within the frozen deadline"
                            : "fixed unreachable target retained its original mob past the frozen deadline");
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                if (!finished) { report(false, failure.getMessage()); finished = true; close(); }
                throw failure;
            }
        }

        void finish(boolean success, String reason) {
            report(success, reason); finished = true; close();
            if (success) ZombiesNavigationTestTiming.succeed(helper); else ZombiesNavigationTestTiming.fail(helper, reason);
        }

        void report(boolean success, String reason) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceRequirement", "P0-4/P5-3 real paired circular pursuit; pure history tests are insufficient");
            result.put("success", success); result.put("reason", reason); result.put("uuid", originalId.toString());
            result.put("deadlineTicks", moving ? MOVING_DEADLINE : FIXED_DEADLINE);
            result.put("elapsedTicks", helper.getTick()); result.put("travelled", travelled);
            result.put("orderedGateTransitions", gates.longest); result.put("clockwiseGateTransitions", gates.clockwiseLongest);
            result.put("mobCompletedCircuits", gates.longest / 4); result.put("gateEvents", gates.events);
            result.put("playerCompletedCircuits", moving ? (int) (helper.getTick() * PLAYER_SPEED / PERIMETER) : 0);
            result.put("playerDrivenSpeed", moving ? PLAYER_SPEED : 0); result.put("playerHealthRestored", moving);
            result.put("mobMovedByFixture", false); result.put("loopSignalObserved", loopObserved);
            result.put("unproductiveCircuitObservation", moving ? "notApplicable" : gates.longest >= 8 ? "observed" : "notObserved");
            result.put("requeued", requeued); result.put("discarded", discarded);
            result.put("recycleTick", recycleTick); result.put("recycleReason", recycleReason);
            result.put("progressBeforeRecycle", lastProgress);
            result.put("remainingBudget", fixture.waveState().remainingBudget()); result.put("activeZombies", fixture.waveState().activeZombies());
            result.put("planning", fixture.spawnService.navigationRuntimeMetrics()); result.put("traceEvery20Ticks", trace);
            ZombiesNavigationTestReport.write(moving ? "loop-moving-player" : "loop-fixed-unreachable-player", result);
        }

        void close() { recycler.reset(); fixture.close(); }
    }

    /** Crossings are measured from actual entity coordinates, not Path cursors or planned segments. */
    private static final class GateTracker {
        final List<String> events = new ArrayList<>();
        int last = -1, direction, consecutive, longest, clockwiseLongest;
        void sample(Vec3 before, Vec3 after, long tick) {
            int gate = -1;
            if (crosses(before.x, after.x) && before.z < 6 && after.z < 6) gate = 0;
            else if (crosses(before.z, after.z) && before.x > 17 && after.x > 17) gate = 1;
            else if (crosses(before.x, after.x) && before.z > 17 && after.z > 17) gate = 2;
            else if (crosses(before.z, after.z) && before.x < 6 && after.x < 6) gate = 3;
            if (gate < 0 || gate == last) return;
            events.add(tick + ":" + gate);
            if (last >= 0) {
                int change = Math.floorMod(gate - last, 4);
                int nextDirection = change == 1 ? 1 : change == 3 ? -1 : 0;
                if (nextDirection != 0 && (direction == 0 || nextDirection == direction)) {
                    direction = nextDirection; consecutive++;
                } else { direction = nextDirection; consecutive = nextDirection == 0 ? 0 : 1; }
                longest = Math.max(longest, consecutive);
                if (direction == 1) clockwiseLongest = Math.max(clockwiseLongest, consecutive);
            }
            last = gate;
        }
        private static boolean crosses(double before, double after) {
            return before < 11.5 && after >= 11.5 || before > 11.5 && after <= 11.5;
        }
    }
}
