package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;

/** GoalSelector may invalidate a checked target while stopping the previous movement owner. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationTargetHandoffGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final String BATCH = "zombies_navigation_target_handoff";
    private static final int HANDOFF_TICK = 70;
    private static final int RESUME_TICK = 110;
    private static final int DEADLINE = 165;

    private ZombiesNavigationTargetHandoffGameTests() { }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 5)
    public static void lostTargetBetweenCanUseAndStartDoesNotConsumeRecovery(GameTestHelper helper) {
        run(helper, Invalidation.CLEAR_TARGET);
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 5)
    public static void nativeMeleeStopClearingCreativeTargetDoesNotCrashRecovery(GameTestHelper helper) {
        run(helper, Invalidation.NATIVE_MELEE_CREATIVE);
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = DEADLINE + 5)
    public static void spectatorTargetBetweenCanUseAndStartDoesNotConsumeRecovery(GameTestHelper helper) {
        run(helper, Invalidation.SPECTATOR_TARGET);
    }

    private static void run(GameTestHelper helper, Invalidation invalidation) {
        ZombiesNavigationTestTiming.begin(helper, "target-handoff-" + invalidation.name().toLowerCase(java.util.Locale.ROOT), false);
        buildLane(helper);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(13.5D, 1.0D, 5.5D));
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(5, 1, 5));
            mob.setNoAi(true);
            mob.setOnGround(true);
            mob.setTarget(fixture.player);
            LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
            Goal recovery = runtime != null ? runtime.movementGoal(mob)
                    : mob.goalSelector.getAvailableGoals().stream().map(goal -> goal.getGoal())
                    .filter(goal -> goal.getClass().getSimpleName().equals("RecoveryGoal")).findFirst().orElseThrow();
            MeleeAttackGoal nativeMelee = mob.goalSelector.getAvailableGoals().stream().map(goal -> goal.getGoal())
                    .filter(MeleeAttackGoal.class::isInstance).map(MeleeAttackGoal.class::cast).findFirst().orElseThrow();
            var previousOwner = new YieldingMovementGoal(() -> {
                switch (invalidation) {
                    case CLEAR_TARGET -> mob.setTarget(null);
                    case NATIVE_MELEE_CREATIVE -> {
                        fixture.player.setGameMode(GameType.CREATIVE);
                        nativeMelee.stop();
                    }
                    case SPECTATOR_TARGET -> fixture.player.setGameMode(GameType.SPECTATOR);
                }
            });
            // Use the real selector admission/preemption sequence, while NoAI keeps entity ticks
            // from starting this same recovery goal before the deliberate handoff boundary.
            GoalSelector selector = new GoalSelector(helper.getLevel()::getProfiler);
            selector.addGoal(1, recovery);
            selector.addGoal(2, previousOwner);
            selector.tick();
            helper.assertTrue(previousOwner.started && !previousOwner.stopped,
                    "the lower-priority movement goal must own MOVE/LOOK before recovery becomes eligible");
            var test = new HandoffRun(helper, fixture, mob, recovery, selector, previousOwner, invalidation);
            helper.onEachTick(test::tick);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
            fixture.close();
            throw failure;
        }
    }

    private static void buildLane(GameTestHelper helper) {
        for (int x = 3; x <= 15; x++) {
            for (int z = 4; z <= 6; z++) {
                for (int y = 0; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y == 0 || y == 4 || z == 4 || z == 6
                            ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }

    private enum Invalidation { CLEAR_TARGET, NATIVE_MELEE_CREATIVE, SPECTATOR_TARGET }

    private static final class HandoffRun {
        private final GameTestHelper helper;
        private final ZombiesNavigationGameTests.Fixture fixture;
        private final Mob mob;
        private final Goal recovery;
        private final GoalSelector selector;
        private final YieldingMovementGoal previousOwner;
        private final Invalidation invalidation;
        private final LayeredNavigationRuntime runtime;
        private boolean handedOff;
        private boolean resumed;
        private boolean finished;
        private long searchesBefore;

        private HandoffRun(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture,
                           Mob mob, Goal recovery, GoalSelector selector, YieldingMovementGoal previousOwner,
                           Invalidation invalidation) {
            this.helper = helper;
            this.fixture = fixture;
            this.mob = mob;
            this.recovery = recovery;
            this.selector = selector;
            this.previousOwner = previousOwner;
            this.invalidation = invalidation;
            this.runtime = LayeredNavigationRuntime.of(mob);
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (!handedOff && helper.getTick() >= HANDOFF_TICK) {
                    handoff();
                }
                if (handedOff && helper.getTick() >= RESUME_TICK) {
                    if (!resumed) {
                        helper.assertTrue(recovery.canUse(),
                                "an aborted handoff must leave the same recovery goal usable for a valid target");
                        resumed = true;
                    }
                    selector.tick();
                    var path = mob.getNavigation().getPath();
                    if (fixture.spawnService.navigationMetrics().searches() > searchesBefore
                            && path != null && path.canReach() && !path.isDone()) {
                        helper.assertTrue(mob.getTarget() == fixture.player && fixture.player.isAlive(),
                                "the original live room survivor must remain the recovered target");
                        if (LayeredNavigationRuntime.of(mob) == null) {
                            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob).recoveryAttempts() == 1,
                                    "only the successful retry may consume the first recovery attempt");
                        } else {
                            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob).recoveryAttempts() == 0
                                            && fixture.spawnService.navigationRuntimeMetrics().planning().expansions() > 0,
                                    "a legal native handoff must produce a planned short path without recording an execution failure");
                        }
                        finished = true;
                        fixture.close();
                        ZombiesNavigationTestTiming.succeed(helper);
                        return;
                    }
                }
                if (helper.getTick() >= DEADLINE) {
                    ZombiesNavigationTestTiming.fail(helper, "valid target did not regain an executable recovery route after " + invalidation
                            + "; metrics=" + fixture.spawnService.navigationMetrics()
                            + ", progress=" + ZombiesGroundNavigationService.getProgress(mob));
                }
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                finished = true;
                fixture.close();
                throw failure;
            }
        }

        private void handoff() {
            helper.assertTrue(recovery.canUse(), "recovery must admit the valid target before the old goal is stopped"
                    + "; tick=" + helper.getTick() + ", mob=" + mob.getUUID() + ", position=" + mob.position()
                    + ", alive=" + mob.isAlive() + ", removal=" + mob.getRemovalReason()
                    + ", worldEntity=" + (helper.getLevel().getEntity(mob.getUUID()) == mob)
                    + ", onGround=" + mob.onGround() + ", water=" + mob.isInWaterOrBubble() + ", lava=" + mob.isInLava()
                    + ", target=" + (mob.getTarget() == null ? null : mob.getTarget().getUUID())
                    + ", roomEligible=" + ZombiesGroundNavigationService.isLayeredTargetEligible(mob, fixture.player)
                    + ", originalRuntime=" + (runtime == null ? null : runtime.describe(mob))
                    + ", installedRuntime=" + LayeredNavigationRuntime.of(mob)
                    + ", controllers=" + (runtime == null ? -1 : runtime.controllerCount())
                    + ", owns=" + (runtime != null && runtime.context().owns(mob))
                    + ", registeredRoom=" + com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry.instance().roomIdOf(mob)
                    + ", expectedRoom=" + fixture.roomId()
                    + ", targetAlive=" + fixture.player.isAlive() + ", targetRemoved=" + fixture.player.isRemoved()
                    + ", targetPosition=" + fixture.player.position()
                    + ", targetSpectator=" + fixture.player.isSpectator()
                    + ", bounds=" + (runtime == null ? null : runtime.context().bounds())
                    + ", progress=" + ZombiesGroundNavigationService.getProgress(mob));
            var before = ZombiesGroundNavigationService.getProgress(mob);
            var metrics = fixture.spawnService.navigationMetrics();
            searchesBefore = metrics.searches();
            selector.tick();
            helper.assertTrue(previousOwner.stopped, "GoalSelector must stop the prior MOVE/LOOK owner during admission");
            boolean restoresLegalRoomTarget = LayeredNavigationRuntime.of(mob) != null
                    && invalidation != Invalidation.SPECTATOR_TARGET;
            if (restoresLegalRoomTarget) {
                helper.assertTrue(mob.getTarget() == fixture.player
                                && ZombiesGroundNavigationService.isLayeredTargetEligible(mob, fixture.player)
                                && recovery.canContinueToUse(),
                        "a native stop may clear the reference, but an eligible room target must be restored on takeover");
                helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob).recoveryAttempts() == before.recoveryAttempts(),
                        "restoring a still-eligible target must not record a failed attempt");
                fixture.player.setGameMode(GameType.SURVIVAL);
                handedOff = true;
                return;
            }
            if (invalidation == Invalidation.SPECTATOR_TARGET) {
                helper.assertTrue(mob.getTarget() == fixture.player && fixture.player.isSpectator(),
                        "the handoff must invalidate the target without clearing its entity reference");
            } else {
                helper.assertTrue(mob.getTarget() == null,
                        "the previous goal's stop must clear the target before RecoveryGoal.start");
            }
            helper.assertFalse(recovery.canContinueToUse(), "a start with an invalid target must abort");
            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob).recoveryAttempts() == before.recoveryAttempts(),
                    "aborting before a recovery begins must not consume an attempt");
            helper.assertTrue(fixture.spawnService.navigationMetrics().searches() == metrics.searches()
                            && fixture.spawnService.navigationMetrics().recoveries() == metrics.recoveries(),
                    "aborted admission must not run or account for a path search");
            selector.tick(); // Let the selector clean up the aborted running wrapper before reacquiring a target.
            fixture.player.setGameMode(GameType.SURVIVAL);
            mob.setTarget(fixture.player);
            handedOff = true;
        }
    }

    private static final class YieldingMovementGoal extends Goal {
        private final Runnable onStop;
        private boolean started;
        private boolean stopped;

        private YieldingMovementGoal(Runnable onStop) {
            this.onStop = onStop;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override public boolean canUse() { return !stopped; }
        @Override public boolean canContinueToUse() { return !stopped; }
        @Override public void start() { started = true; }

        @Override
        public void stop() {
            if (!stopped) {
                stopped = true;
                onStop.run();
            }
        }
    }
}
