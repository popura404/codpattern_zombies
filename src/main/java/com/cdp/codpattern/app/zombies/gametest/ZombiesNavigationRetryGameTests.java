package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exhaustion and retry regressions driven by real room spawning and native entity ticks. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationRetryGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final String BATCH = "zombies_ground_navigation_retry";
    private static final int CHANGE_TICK = 1000;
    private static final int BLOCKED_DEADLINE = 1300;
    private static final int OPEN_DEADLINE = 1500;
    private static final int ATTEMPTS = 4;

    private ZombiesNavigationRetryGameTests() {
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = BLOCKED_DEADLINE + 10)
    public static void movingBlockedPlayerDoesNotRenewExhaustedRecovery(GameTestHelper helper) {
        run(helper, false);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = OPEN_DEADLINE + 10)
    public static void openingExitAfterExhaustionRestoresPursuit(GameTestHelper helper) {
        run(helper, true);
    }

    private static void run(GameTestHelper helper, boolean openExit) {
        buildSealedCell(helper);
        var fixture = new ZombiesNavigationGameTests.Fixture(helper, new Vec3(12.5D, 1.0D, 5.5D));
        try {
            Mob mob = fixture.spawn("zombie", new BlockPos(5, 1, 5));
            mob.getRandom().setSeed(1701L);
            helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob) != null,
                    "the room spawn must install navigation recovery");
            RetryRun run = new RetryRun(helper, fixture, mob, openExit);
            helper.onEachTick(run::observe);
        } catch (RuntimeException | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void buildSealedCell(GameTestHelper helper) {
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= 21; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
                for (int y = 1; y <= 3; y++) {
                    boolean border = x == 0 || x == 21 || z == 0 || z == 21;
                    boolean cellWall = x >= 4 && x <= 6 && z >= 4 && z <= 6
                            && (x != 5 || z != 5);
                    helper.setBlock(new BlockPos(x, y, z), border || cellWall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }

    private static final class RetryRun {
        private final GameTestHelper helper;
        private final ZombiesNavigationGameTests.Fixture fixture;
        private final Mob mob;
        private final boolean openExit;
        private final Vec3 origin;
        private Vec3 expectedTarget;
        private ZombiesGroundNavigationService.SearchMetrics previousMetrics;
        private ZombiesGroundNavigationService.SearchMetrics metricsAtChange;
        private long progressAtChange;
        private boolean changed;
        private boolean finished;

        private RetryRun(GameTestHelper helper, ZombiesNavigationGameTests.Fixture fixture,
                         Mob mob, boolean openExit) {
            this.helper = helper;
            this.fixture = fixture;
            this.mob = mob;
            this.openExit = openExit;
            origin = mob.position();
            expectedTarget = fixture.player.position();
            previousMetrics = fixture.spawnService.navigationMetrics();
        }

        private void observe() {
            if (finished) {
                return;
            }
            try {
                helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID()) == mob,
                        "retry must use the original ticking zombie without recycling or replacement");
                helper.assertTrue(fixture.player.isAlive() && mob.getTarget() == fixture.player,
                        "the same live room target must remain selected throughout exhaustion and retry");
                helper.assertTrue(fixture.player.position().distanceToSqr(expectedTarget) < 1.0E-8D,
                        "the player may move only at the fixed scenario change");
                var progress = ZombiesGroundNavigationService.getProgress(mob);
                helper.assertTrue(progress != null, "the recovery observer must remain installed");
                var metrics = fixture.spawnService.navigationMetrics();
                helper.assertTrue(metrics.searches() - previousMetrics.searches() <= 2,
                        "exhaustion and retries must obey the room limit of two extra searches per tick");
                helper.assertTrue(metrics.geometryChecks() - previousMetrics.geometryChecks() <= 32,
                        "retry route validation must obey the room collision-check budget");
                previousMetrics = metrics;
                if (!changed && helper.getTick() >= CHANGE_TICK) {
                    changeScenario(progress, metrics);
                }
                if (!changed) {
                    return;
                }
                if (openExit) {
                    observeOpenedExit(progress, metrics);
                } else {
                    observeBlockedMove(progress, metrics);
                }
            } catch (RuntimeException | Error failure) {
                finished = true;
                fixture.close();
                throw failure;
            }
        }

        private void changeScenario(ZombiesGroundNavigationService.ProgressSnapshot progress,
                                    ZombiesGroundNavigationService.SearchMetrics metrics) {
            helper.assertTrue(progress.recoveryAttempts() == ATTEMPTS,
                    "the sealed cell must naturally exhaust four attempts before tick " + CHANGE_TICK
                            + "; progress=" + progress + ", metrics=" + metrics);
            helper.assertTrue(metrics.recoveries() > ATTEMPTS,
                    "an exhausted zombie must already have performed a low-frequency recheck; metrics=" + metrics);
            helper.assertTrue(metrics.localRoutes() == 0,
                    "a fully sealed one-block cell must not offer any executable local relay");
            helper.assertTrue(mob.position().distanceToSqr(origin) < 0.5D,
                    "the zombie must still be physically confined when the scenario changes");
            progressAtChange = progress.lastProgressGameTime();
            metricsAtChange = metrics;
            changed = true;
            if (openExit) {
                // A side exit keeps the player behind the east wall until the zombie walks around it.
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(5, y, 6), Blocks.AIR);
                }
            } else {
                // Preserve the direction of pursuit: the player moves six blocks farther behind the same wall.
                expectedTarget = helper.absoluteVec(new Vec3(18.5D, 1.0D, 5.5D));
                fixture.player.moveTo(expectedTarget.x, expectedTarget.y, expectedTarget.z, 0.0F, 0.0F);
            }
        }

        private void observeBlockedMove(ZombiesGroundNavigationService.ProgressSnapshot progress,
                                        ZombiesGroundNavigationService.SearchMetrics metrics) {
            helper.assertTrue(progress.recoveryAttempts() == ATTEMPTS,
                    "moving an unreachable player must not grant another four recovery attempts; progress=" + progress);
            helper.assertTrue(progress.lastProgressGameTime() == progressAtChange,
                    "target movement and exhausted rechecks must not refresh physical progress; progress=" + progress);
            helper.assertTrue(metrics.localRoutes() == metricsAtChange.localRoutes(),
                    "exhausted rechecks must not start a fresh local-relay stage");
            if (helper.getTick() >= BLOCKED_DEADLINE) {
                helper.assertTrue(metrics.recoveries() > metricsAtChange.recoveries(),
                        "low-frequency target rechecks must continue after the blocked player moves");
                succeed();
            }
        }

        private void observeOpenedExit(ZombiesGroundNavigationService.ProgressSnapshot progress,
                                       ZombiesGroundNavigationService.SearchMetrics metrics) {
            helper.assertTrue(metrics.localRoutes() == metricsAtChange.localRoutes(),
                    "opening a reachable exit must not grant an exhausted zombie fresh local-relay rounds");
            if (mob.position().distanceToSqr(origin) < 0.5D) {
                helper.assertTrue(progress.recoveryAttempts() == ATTEMPTS,
                        "opening a block alone must not clear failures before the zombie physically leaves its cell");
            }
            double reach = mob.getBbWidth() * 2.0D;
            boolean arrived = mob.distanceToSqr(fixture.player) <= reach * reach + fixture.player.getBbWidth()
                    && mob.getSensing().hasLineOfSight(fixture.player);
            if (arrived) {
                helper.assertTrue(progress.lastProgressGameTime() > progressAtChange,
                        "renewed pursuit must produce real physical progress after the exit opens");
                succeed();
            } else if (helper.getTick() >= OPEN_DEADLINE) {
                helper.fail("an exhausted zombie failed to leave the opened cell and reach the stationary player; "
                        + "position=" + mob.position() + ", origin=" + origin + ", progress=" + progress
                        + ", metrics=" + metrics);
            }
        }

        private void succeed() {
            finished = true;
            fixture.close();
            helper.succeed();
        }
    }
}
