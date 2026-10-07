package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.runtime.ZombiesWaveRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesActiveMobCounter;
import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobLifecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobRecycleService;
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Real spawn/AI regressions; these must run inside Forge, not the pure JVM compatibility suite. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationGameTests {
    private static final String TEMPLATE = "zombies_navigation";
    private static final String BATCH = "zombies_ground_navigation";
    private static final int ROUTE_TIMEOUT = 500;

    private ZombiesNavigationGameTests() {
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void zombieUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:zombieUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "zombie");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void huskUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:huskUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "husk");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void witherSkeletonUsesNearbyStairsWithEnoughHeadroom(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:witherSkeletonUsesNearbyStairsWithEnoughHeadroom", false);
        climbNearbyStairs(helper, "wither_skeleton");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void creeperUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:creeperUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "creeper");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void wolfUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:wolfUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "wolf");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void silverfishUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:silverfishUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "silverfish");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void vindicatorUsesNearbyStairsAwayFromElevatedPlayer(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:vindicatorUsesNearbyStairsAwayFromElevatedPlayer", false);
        climbNearbyStairs(helper, "vindicator");
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void zombieRetriesAnIncompleteRouteWhenNearbyStairsBecomeAvailable(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:zombieRetriesAnIncompleteRouteWhenNearbyStairsBecomeAvailable", false);
        buildRoom(helper, false);
        buildUpperPlatform(helper);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 6.0D, 5.5D));
        Mob mob = fixture.spawn("zombie", new BlockPos(7, 1, 8));
        double upperFloorY = fixture.player.getY();
        helper.runAtTickTime(80, () -> {
            try {
                helper.assertTrue(mob.getY() < upperFloorY - 2.0D,
                        "the mob must not teleport onto an initially inaccessible upper floor");
                buildStairs(helper);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                fixture.close();
                throw failure;
            }
        });
        helper.onEachTick(() -> {
            if (helper.getTick() > 80 && mob.getY() >= upperFloorY - 0.15D
                    && horizontalDistance(mob, fixture.player) < 4.0D) {
                fixture.close();
                ZombiesNavigationTestTiming.succeed(helper);
            }
        });
        helper.runAtTickTime(ROUTE_TIMEOUT - 5, () -> {
            String detail = "a stationary elevated target must be retried after stairs become available; "
                    + describeNavigation(mob);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void creeperStillStartsSwellingNearItsTarget(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:creeperStillStartsSwellingNearItsTarget", false);
        buildRoom(helper, false);
        Fixture fixture = new Fixture(helper, new Vec3(10.5D, 1.0D, 8.5D));
        Creeper creeper = (Creeper) fixture.spawn("creeper", new BlockPos(8, 1, 8));
        helper.onEachTick(() -> {
            if (creeper.getSwellDir() > 0) {
                try {
                    helper.assertTrue(creeper.goalSelector.getRunningGoals()
                                    .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals("SwellGoal")),
                            "native SwellGoal must own the swelling behavior");
                } finally {
                    fixture.close();
                }
                ZombiesNavigationTestTiming.succeed(helper);
            }
        });
        helper.runAtTickTime(75, () -> {
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, "room pursuit suppressed the creeper's native swelling goal");
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = ROUTE_TIMEOUT)
    public static void creeperBelowClosePlayerDoesNotStayInADefusedSwellGoal(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:creeperBelowClosePlayerDoesNotStayInADefusedSwellGoal", false);
        buildRoom(helper, false);
        // A bottom slab has enough clearance for a Creeper below it while keeping the player < 3 blocks away.
        for (int x = 3; x <= 13; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 3, z), Blocks.SMOOTH_STONE_SLAB);
            }
        }
        for (int x = 10; x <= 12; x++) {
            helper.setBlock(new BlockPos(x, 1, 14), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 13), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 13), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 12), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 12), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 3, 12), Blocks.SMOOTH_STONE_SLAB);
        }
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 3.5D, 8.5D));
        Creeper creeper = (Creeper) fixture.spawn("creeper", new BlockPos(7, 1, 8));
        helper.assertTrue(creeper.distanceToSqr(fixture.player) < 9.0D,
                "fixture must start inside the vanilla SwellGoal activation radius");
        helper.assertFalse(creeper.getSensing().hasLineOfSight(fixture.player),
                "fixture must block line of sight through the floor");
        helper.onEachTick(() -> {
            if (creeper.getY() >= fixture.player.getY() - 0.15D && creeper.getSwellDir() > 0) {
                try {
                    helper.assertTrue(creeper.getSensing().hasLineOfSight(fixture.player),
                            "the Creeper must reach the upper floor and enter valid visible-target swelling");
                } finally {
                    fixture.close();
                }
                ZombiesNavigationTestTiming.succeed(helper);
            }
        });
        helper.runAtTickTime(ROUTE_TIMEOUT - 5, () -> {
            String detail = "a defused SwellGoal below a close player must yield to the nearby stair route; "
                    + describeNavigation(creeper);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 160)
    public static void wolfRetainsItsNativeLeapAndLanding(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:wolfRetainsItsNativeLeapAndLanding", false);
        buildRoom(helper, false);
        Fixture fixture = new Fixture(helper, new Vec3(13.5D, 1.0D, 10.5D));
        Wolf wolf = (Wolf) fixture.spawn("wolf", new BlockPos(10, 1, 10));
        wolf.getRandom().setSeed(1701L);
        boolean[] observedLeap = {false};
        helper.onEachTick(() -> {
            boolean nativeLeap = wolf.goalSelector.getRunningGoals()
                    .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals("LeapAtTargetGoal"));
            observedLeap[0] |= nativeLeap && !wolf.onGround();
            if (observedLeap[0] && wolf.onGround() && !nativeLeap) {
                try {
                    helper.assertTrue(fixture.player.getUUID().equals(wolf.getPersistentAngerTarget()),
                            "native leap/landing must preserve the room target's anger identity");
                } finally {
                    fixture.close();
                }
                ZombiesNavigationTestTiming.succeed(helper);
                return;
            }
            if (!observedLeap[0]) {
                // Keep the target in native leap range while deterministic goal randomness gets a chance to run.
                fixture.player.moveTo(wolf.getX() + 3.0D, fixture.player.getY(), wolf.getZ(), 0.0F, 0.0F);
            }
        });
        helper.runAtTickTime(155, () -> {
            String detail = "room navigation must permit a native wolf leap and subsequent landing; "
                    + describeNavigation(wolf);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void wolfRecoveryYieldsToANewNativeLeapOpportunity(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:wolfRecoveryYieldsToANewNativeLeapOpportunity", false);
        buildRoom(helper, false);
        buildUpperPlatform(helper);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 6.0D, 8.5D));
        Wolf wolf = (Wolf) fixture.spawn("wolf", new BlockPos(7, 1, 8));
        wolf.getRandom().setSeed(1701L);
        long[] targetMovedTick = {-1L};
        long[] recoveryReleasedTick = {-1L};
        helper.onEachTick(() -> {
            try {
                long tick = helper.getTick();
                var runtime = com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime.of(wolf);
                boolean recoveryRunning = isGoalRunning(wolf, runtime == null ? "RecoveryGoal" : "Controller");
                var path = wolf.getNavigation().getPath();
                if (targetMovedTick[0] < 0L) {
                    // With one inaccessible target, a complete path to a different endpoint is a real local recovery.
                    // A layered request exposes its planning work and current MOVE owner as recovery evidence.
                    boolean controlledPlanning = runtime != null && runtime.planningStats().requests() > 0
                            && runtime.planningStats().expansions() > 0 && tick >= 40L;
                    boolean localRecovery = runtime == null && path != null && !path.isDone() && path.canReach()
                            && !path.getTarget().equals(fixture.player.blockPosition());
                    if (recoveryRunning && (localRecovery || controlledPlanning)) {
                        helper.assertTrue(tick >= 40L,
                                "the wolf must enter recovery after an actual stall observation window");
                        targetMovedTick[0] = tick;
                        wolf.getRandom().setSeed(1701L);
                        fixture.player.moveTo(wolf.getX() + 3.0D, wolf.getY(), wolf.getZ(), 0.0F, 0.0F);
                    }
                    return;
                }
                if (!recoveryRunning && recoveryReleasedTick[0] < 0L) {
                    recoveryReleasedTick[0] = tick;
                }
                helper.assertTrue(recoveryReleasedTick[0] >= 0L || tick - targetMovedTick[0] <= 24L,
                        "an advancing recovery route must release MOVE for native goals within a bounded handoff");
                if (isGoalRunning(wolf, "LeapAtTargetGoal") && !wolf.onGround()) {
                    helper.assertFalse(recoveryRunning,
                            "native leap must own movement after the recovery handoff");
                    helper.assertTrue(recoveryReleasedTick[0] >= targetMovedTick[0],
                            "the leap must happen after the observed recovery route releases ownership");
                    helper.assertTrue(wolf.getSensing().hasLineOfSight(fixture.player),
                            "the new leap opportunity must be toward the visible nearby survivor");
                    fixture.close();
                    ZombiesNavigationTestTiming.succeed(helper);
                    return;
                }
                helper.assertTrue(tick - targetMovedTick[0] <= 100L,
                        "the wolf must use the new native leap opportunity after bounded recovery handoff");
                // Keep a visible target in vanilla leap range; do not start or tick any goal manually.
                fixture.player.moveTo(wolf.getX() + 3.0D,
                        helper.absolutePos(new BlockPos(0, 1, 0)).getY(), wolf.getZ(), 0.0F, 0.0F);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                fixture.close();
                throw failure;
            }
        });
        helper.runAtTickTime(415, () -> {
            String detail = "wolf must enter a real local recovery and then hand movement to native leap; "
                    + describeNavigation(wolf);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void newlyIgnitedCreeperSurvivesTheFirstExpiredRecycleScan(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:newlyIgnitedCreeperSurvivesTheFirstExpiredRecycleScan", false);
        buildRoom(helper, false);
        buildClosedChamber(helper);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 5.0D, 8.5D));
        Creeper creeper = (Creeper) fixture.spawn("creeper", new BlockPos(7, 1, 8));
        creeper.getRandom().setSeed(1701L);
        long[] igniteGameTime = {-1L};
        ZombiesWaveRuntimeState wave = fixture.waveState;
        ZombiesMobRecycleService recycler = new ZombiesMobRecycleService(fixture.ownership,
                new ZombiesMobLifecycleService(fixture.ownership, fixture.spawnService),
                () -> List.copyOf(fixture.targets));
        helper.onEachTick(() -> {
            try {
                long tick = helper.getTick();
                if (tick == 397L) {
                    var progress = ZombiesGroundNavigationService.getProgress(creeper);
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(progress != null && now - Math.max(progress.lastProgressGameTime(),
                                    progress.lastEngagementGameTime()) >= 320L,
                            "the Creeper must already have stalled past the ordinary recovery timeout");
                    helper.assertTrue(creeper.getTarget() == fixture.player && fixture.player.isAlive(),
                            "the stalled Creeper must still have a valid room target");
                    helper.assertFalse(creeper.getSensing().hasLineOfSight(fixture.player),
                            "ordinary visible-target engagement must not protect this fixture");
                    igniteGameTime[0] = now;
                    creeper.ignite();
                }
                // First observe at tick 80: the first scan that can expire its 320-tick monitor is tick 400.
                if (tick < 80L || tick > 400L) {
                    return;
                }
                String beforeScan = describeLateFuse(helper, creeper);
                ZombiesMobRecycleService.RecycleSummary result = recycler.tick(
                        fixture.roomId, helper.getLevel(), wave, tick);
                helper.assertTrue(result.requeued() == 0 && result.discarded() == 0 && !creeper.isRemoved(),
                        "a newly committed fuse must not be interrupted by the first expired recycle scan; result="
                                + result + "; igniteGameTime=" + igniteGameTime[0]
                                + "; before={" + beforeScan + "}; after={"
                                + describeLateFuse(helper, creeper) + "}");
                if (tick == 400L) {
                    var progress = ZombiesGroundNavigationService.getProgress(creeper);
                    helper.assertTrue(igniteGameTime[0] >= 0L && creeper.isIgnited() && progress != null
                                    && progress.actionGraceUntilGameTime() >= igniteGameTime[0] + 60L,
                            "new ignition must receive fresh, finite action grace despite the previous long stall; "
                                    + "igniteGameTime=" + igniteGameTime[0] + "; before={" + beforeScan
                                    + "}; after={" + describeLateFuse(helper, creeper) + "}");
                    helper.assertTrue(wave.activeZombies() == 1 && wave.remainingBudget() == 0
                                    && fixture.counter.roomCount(fixture.roomId) == 1
                                    && fixture.ownership.entryOf(creeper).isPresent(),
                            "the protected scan must retain the live fuse entity and its existing accounting");
                    fixture.close();
                    ZombiesNavigationTestTiming.succeed(helper);
                }
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                fixture.close();
                throw failure;
            }
        });
        helper.runAtTickTime(415, () -> {
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, "the first expired recycle scan must execute while the late ignition is protected");
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 500)
    public static void closeUnreachableTargetEventuallyRequeuesTheRealRoomMob(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:closeUnreachableTargetEventuallyRequeuesTheRealRoomMob", false);
        buildRoom(helper, false);
        // A close player stands on the roof of a one-cell chamber. No legal route or useful local move exists.
        buildClosedChamber(helper);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 5.0D, 8.5D));
        Mob mob = fixture.spawn("zombie", new BlockPos(7, 1, 8));
        ZombiesWaveRuntimeState wave = fixture.waveState;
        ZombiesMobRecycleService recycler = new ZombiesMobRecycleService(fixture.ownership,
                new ZombiesMobLifecycleService(fixture.ownership, fixture.spawnService),
                () -> List.copyOf(fixture.targets));
        long started = helper.getLevel().getGameTime();
        recycler.tick(fixture.roomId, helper.getLevel(), wave, 0L);
        helper.onEachTick(() -> {
            ZombiesMobRecycleService.RecycleSummary result = recycler.tick(
                    fixture.roomId, helper.getLevel(), wave, helper.getTick());
            if (result.requeued() == 0) {
                return;
            }
            try {
                helper.assertTrue(helper.getLevel().getGameTime() - started >= 320L,
                        "an unreachable mob must retain the full game-time recovery window");
                helper.assertTrue(result.requeued() == 1 && result.discarded() == 0,
                        "the first navigation failure must requeue exactly once");
                helper.assertTrue(mob.isRemoved(), "the recycled entity must actually leave the world");
                helper.assertTrue(wave.remainingBudget() == 1 && wave.activeZombies() == 0,
                        "recycling must restore one budget unit and remove one active mob");
                helper.assertTrue(wave.activeZombieEntityIdsSnapshot().isEmpty(),
                        "recycling must remove the old active entity ID");
                helper.assertTrue(fixture.counter.roomCount(fixture.roomId) == 0
                                && fixture.ownership.entitiesInRoom(fixture.roomId).isEmpty(),
                        "recycling must reconcile both ownership and the active-mob counter");
                helper.assertFalse(wave.isWaveComplete(), "a requeued mob must keep the wave open");
                recycler.tick(fixture.roomId, helper.getLevel(), wave, helper.getTick());
                helper.assertTrue(wave.remainingBudget() == 1,
                        "repeated lifecycle observation must not duplicate the requeued budget");
            } finally {
                fixture.close();
            }
            ZombiesNavigationTestTiming.succeed(helper);
        });
        helper.runAtTickTime(495, () -> {
            String detail = "a close target through a roof must not preserve the final mob forever; "
                    + describeNavigation(mob);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void unreachableUpperPlayerFallsBackToReachableRoomSurvivor(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:unreachableUpperPlayerFallsBackToReachableRoomSurvivor", false);
        buildRoom(helper, false);
        buildUpperPlatform(helper);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 6.0D, 5.5D));
        Mob mob = fixture.spawn("zombie", new BlockPos(7, 1, 8));
        helper.assertTrue(mob.getTarget() == fixture.player,
                "the initial closer target must be the inaccessible upper player");
        ServerPlayer reachable = fixture.addPlayer(new Vec3(17.5D, 1.0D, 17.5D));
        helper.onEachTick(() -> {
            if (mob.getTarget() == reachable && mob.distanceToSqr(reachable) < 16.0D) {
                try {
                    helper.assertTrue(fixture.player.isAlive(),
                            "fallback must work while the original target remains valid and alive");
                    helper.assertTrue(fixture.targets.contains(reachable),
                            "the alternate target must belong to the same room survivor supplier");
                } finally {
                    fixture.close();
                }
                ZombiesNavigationTestTiming.succeed(helper);
            }
        });
        helper.runAtTickTime(595, () -> {
            String detail = "bounded recovery must select and approach a reachable room survivor; "
                    + describeNavigation(mob);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void witherSkeletonWeaponChangesKeepOneNativeCombatGoal(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:witherSkeletonWeaponChangesKeepOneNativeCombatGoal", false);
        buildRoom(helper, false);
        Fixture fixture = new Fixture(helper, new Vec3(18.5D, 1.0D, 18.5D));
        WitherSkeleton skeleton = (WitherSkeleton) fixture.spawn("wither_skeleton", new BlockPos(3, 1, 3));
        helper.runAtTickTime(10, () -> {
            try {
                skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                assertNativeWeaponGoals(helper, skeleton, true);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                fixture.close();
                throw failure;
            }
        });
        helper.runAtTickTime(30, () -> {
            try {
                skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                assertNativeWeaponGoals(helper, skeleton, false);
                helper.assertTrue(ZombiesGroundNavigationService.getProgress(skeleton) != null,
                        "weapon reassessment must retain room navigation coordination");
            } finally {
                fixture.close();
            }
            ZombiesNavigationTestTiming.succeed(helper);
        });
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void spawnClearanceIsDiagnosticOnlyForAllBodySizes(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:spawnClearanceIsDiagnosticOnlyForAllBodySizes", false);
        buildRoom(helper, false);
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
            }
        }
        try (Fixture fixture = new Fixture(helper, new Vec3(18.5D, 1.0D, 18.5D))) {
            ZombiesMobSpawnService.SpawnResult tallSpawn = fixture.attemptSpawn(
                    "wither_skeleton", new BlockPos(8, 1, 8));
            ZombiesWaveRuntimeState tallWave = fixture.waveState;
            helper.assertTrue(tallSpawn.spawned() && tallSpawn.entity().orElseThrow().isAlive(),
                    "body clearance diagnostics must permit a Wither Skeleton in two-block headroom");
            helper.assertTrue(tallWave.remainingBudget() == 0 && tallWave.activeZombies() == 1,
                    "the accepted tall body must consume its budget and register as active");
            helper.assertTrue(fixture.counter.roomCount(fixture.roomId) == 1
                            && fixture.ownership.entitiesInRoom(fixture.roomId).size() == 1,
                    "the accepted tall body must retain room ownership and active count");
            Mob silverfish = fixture.spawn("silverfish", new BlockPos(8, 1, 8));
            helper.assertTrue(silverfish.isAlive() && fixture.waveState.remainingBudget() == 0
                            && fixture.waveState.activeZombies() == 1,
                    "the same location must permit a physically fitting Silverfish");
            helper.assertTrue(tallWave.remainingBudget() == 0 && tallWave.activeZombies() == 1
                            && fixture.counter.roomCount(fixture.roomId) == 2
                            && fixture.ownership.entitiesInRoom(fixture.roomId).size() == 2,
                    "both body sizes must remain owned and counted without changing the first wave's budget");
        }
        ZombiesNavigationTestTiming.succeed(helper);
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void installationPreservesNativeSpeciesGoalsAndWolfAnger(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:installationPreservesNativeSpeciesGoalsAndWolfAnger", false);
        buildRoom(helper, false);
        try (Fixture fixture = new Fixture(helper, new Vec3(18.5D, 1.0D, 18.5D))) {
            Wolf wolf = (Wolf) fixture.spawn("wolf", new BlockPos(3, 1, 3));
            assertGoalPresent(helper, wolf, "LeapAtTargetGoal");
            assertGoalPresent(helper, wolf, "FloatGoal");
            assertGoalPresent(helper, wolf, "WolfPanicGoal");
            helper.assertFalse(wolf.isTame(), "room wolf must remain untamed");
            helper.assertFalse(wolf.isOrderedToSit(), "room wolf must remain standing");
            helper.assertTrue(fixture.player.getUUID().equals(wolf.getPersistentAngerTarget()),
                    "navigation installation must preserve the room wolf's anger target");
            helper.assertTrue(wolf.getRemainingPersistentAngerTime() > 0,
                    "navigation installation must preserve persistent wolf anger");

            Mob silverfish = fixture.spawn("silverfish", new BlockPos(5, 1, 3));
            assertGoalPresent(helper, silverfish, "SilverfishMergeWithStoneGoal");
            assertGoalPresent(helper, silverfish, "SilverfishWakeUpFriendsGoal");
            assertGoalPresent(helper, silverfish, "FloatGoal");

            Mob vindicator = fixture.spawn("vindicator", new BlockPos(7, 1, 3));
            assertGoalPresent(helper, vindicator, "VindicatorBreakDoorGoal");
            assertGoalPresent(helper, vindicator, "RaiderOpenDoorGoal");
            assertGoalPresent(helper, vindicator, "VindicatorMeleeAttackGoal");

            Mob creeper = fixture.spawn("creeper", new BlockPos(9, 1, 3));
            assertGoalPresent(helper, creeper, "SwellGoal");
            assertGoalPresent(helper, creeper, "FloatGoal");
        }
        ZombiesNavigationTestTiming.succeed(helper);
    }

    @GameTest(setupTicks = 20, template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void excludedSpeciesAndOrdinaryZombieKeepTheirNavigation(GameTestHelper helper) {
        ZombiesNavigationTestTiming.begin(helper, "main:excludedSpeciesAndOrdinaryZombieKeepTheirNavigation", false);
        buildRoom(helper, false);
        try (Fixture fixture = new Fixture(helper, new Vec3(18.5D, 1.0D, 18.5D))) {
            int x = 3;
            for (String id : List.of("warden", "vex", "spider")) {
                Mob mob = fixture.spawn(id, new BlockPos(x, 1, 3));
                helper.assertFalse(ZombiesGroundNavigationService.supports(mob),
                        id + " must remain outside the ground-navigation allowlist");
                helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob) == null,
                        id + " must not receive the new ground-navigation controller");
                if (id.equals("spider")) {
                    // Excluding spiders must preserve their existing addon overrides as well as vanilla AI.
                    assertGoalPresent(helper, mob, "RoomMonsterObstacleJumpGoal");
                    assertGoalPresent(helper, mob, "RoomMonsterObstacleDetourGoal");
                    assertGoalPresent(helper, mob, "RoomMonsterDropDownChaseGoal");
                    assertGoalPresent(helper, mob, "RoomMonsterMeleeAttackGoal");
                }
                x += 4;
            }
            Zombie ordinary = EntityType.ZOMBIE.create(helper.getLevel());
            helper.assertTrue(ordinary != null, "ordinary zombie fixture must exist");
            try {
                helper.assertTrue(ZombiesGroundNavigationService.getProgress(ordinary) == null,
                        "ordinary non-room zombies must not receive the room navigation controller");
            } finally {
                ordinary.discard();
            }
        }
        ZombiesNavigationTestTiming.succeed(helper);
    }

    private static void climbNearbyStairs(GameTestHelper helper, String mobId) {
        buildRoom(helper, true);
        Fixture fixture = new Fixture(helper, new Vec3(7.5D, 6.0D, 5.5D));
        Mob mob = fixture.spawn(mobId, new BlockPos(7, 1, 8));
        double initialDistance = horizontalDistance(mob, fixture.player);
        double[] farthestDistance = {initialDistance};
        double upperFloorY = helper.absolutePos(new BlockPos(0, 6, 0)).getY();
        helper.onEachTick(() -> {
            farthestDistance[0] = Math.max(farthestDistance[0], horizontalDistance(mob, fixture.player));
            if (mob.getY() >= upperFloorY - 0.15D && horizontalDistance(mob, fixture.player) < 4.0D) {
                try {
                    helper.assertTrue(farthestDistance[0] > initialDistance + 2.0D,
                            mobId + " must follow the stairs even while moving farther from the player");
                    helper.assertTrue(ZombiesGroundNavigationService.getProgress(mob) != null,
                            mobId + " must use the controller installed by the real room spawn path");
                    helper.assertTrue(mob.isAlive(), mobId + " must reach the upper floor alive");
                } finally {
                    fixture.close();
                }
                ZombiesNavigationTestTiming.succeed(helper);
            }
        });
        helper.runAtTickTime(ROUTE_TIMEOUT - 5, () -> {
            String detail = mobId + " never reached the elevated player; position=" + mob.position()
                    + ", target=" + fixture.player.position()
                    + ", " + describeNavigation(mob);
            fixture.close();
            ZombiesNavigationTestTiming.fail(helper, detail);
        });
    }

    /** The only access to the upper floor is a five-step, three-block-wide staircase behind the spawn. */
    private static void buildRoom(GameTestHelper helper, boolean withStairs) {
        for (int x = 0; x <= 21; x++) {
            for (int z = 0; z <= 21; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 10, z), Blocks.STONE);
                if (x == 0 || x == 21 || z == 0 || z == 21) {
                    for (int y = 1; y < 10; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        if (!withStairs) {
            return;
        }
        buildUpperPlatform(helper);
        buildStairs(helper);
    }

    private static void buildUpperPlatform(GameTestHelper helper) {
        for (int x = 3; x <= 13; x++) {
            for (int z = 3; z <= 11; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
            }
            // Direct movement toward the player is blocked; the stairs remain nearby to the south.
            for (int y = 1; y < 5; y++) {
                helper.setBlock(new BlockPos(x, y, 7), Blocks.STONE);
            }
        }
    }

    private static void buildClosedChamber(GameTestHelper helper) {
        for (int x = 6; x <= 8; x++) {
            for (int z = 7; z <= 9; z++) {
                for (int y = 1; y <= 4; y++) {
                    if (x != 7 || z != 8 || y == 4) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }

    private static void buildStairs(GameTestHelper helper) {
        for (int step = 1; step <= 5; step++) {
            for (int x = 10; x <= 12; x++) {
                for (int y = 1; y <= step; y++) {
                    helper.setBlock(new BlockPos(x, y, 17 - step), Blocks.STONE);
                }
            }
        }
    }

    private static String describeNavigation(Mob mob) {
        var path = mob.getNavigation().getPath();
        var runtime = com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime.of(mob);
        return "position=" + mob.position() + ", navigationDone=" + mob.getNavigation().isDone()
                + ", path=" + path + ", canReach=" + (path == null ? "none" : path.canReach())
                + ", end=" + (path == null ? "none" : path.getEndNode())
                + ", execution=" + (runtime == null ? "legacy" : runtime.describe(mob))
                + ", runningGoals=" + mob.goalSelector.getRunningGoals()
                .map(goal -> goal.getGoal().getClass().getSimpleName()).toList();
    }

    private static String describeLateFuse(GameTestHelper helper, Creeper creeper) {
        return "testTick=" + helper.getTick() + ", gameTime=" + helper.getLevel().getGameTime()
                + ", entityTick=" + creeper.tickCount + ", alive=" + creeper.isAlive()
                + ", removed=" + creeper.isRemoved() + ", removalReason=" + creeper.getRemovalReason()
                + ", noAi=" + creeper.isNoAi() + ", ignited=" + creeper.isIgnited()
                + ", swell=" + creeper.getSwellDir()
                + ", progress=" + ZombiesGroundNavigationService.getProgress(creeper)
                + ", worldEntityPresent=" + (helper.getLevel().getEntity(creeper.getUUID()) == creeper)
                + ", levelPlayers=" + helper.getLevel().players().size()
                + ", forcedChunks=" + helper.getLevel().getForcedChunks().size()
                + ", " + describeNavigation(creeper);
    }

    private static void assertGoalPresent(GameTestHelper helper, Mob mob, String goalName) {
        helper.assertTrue(mob.goalSelector.getAvailableGoals().stream()
                        .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals(goalName)),
                mob.getType() + " must retain its native " + goalName);
    }

    private static boolean isGoalRunning(Mob mob, String goalName) {
        return mob.goalSelector.getRunningGoals()
                .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals(goalName));
    }

    private static void assertNativeWeaponGoals(GameTestHelper helper, WitherSkeleton skeleton, boolean bow) {
        long meleeGoals = skeleton.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof MeleeAttackGoal).count();
        long bowGoals = skeleton.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal() instanceof RangedBowAttackGoal<?>).count();
        helper.assertTrue(meleeGoals == (bow ? 0 : 1) && bowGoals == (bow ? 1 : 0),
                "equipment changes must select exactly one native combat goal; melee=" + meleeGoals
                        + ", bow=" + bowGoals);
    }

    private static double horizontalDistance(Mob mob, ServerPlayer player) {
        return Math.hypot(mob.getX() - player.getX(), mob.getZ() - player.getZ());
    }

    public static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final RoomId roomId = RoomId.of(BuiltInGameModes.ZOMBIES, "navigation-" + UUID.randomUUID());
        private final ModeEntityOwnershipRegistry ownership = ModeEntityOwnershipRegistry.instance();
        private final ZombiesActiveMobCounter counter = new ZombiesActiveMobCounter();
        private final List<Mob> mobs = new ArrayList<>();
        private final List<ServerPlayer> targets = new ArrayList<>();
        public final ServerPlayer player;
        public final ZombiesMobSpawnService spawnService;
        private ZombiesWaveRuntimeState waveState;

        Fixture(GameTestHelper helper, Vec3 relativePlayerPosition) {
            this(helper, relativePlayerPosition, new BlockPos(24, 12, 24));
        }

        public Fixture(GameTestHelper helper, Vec3 relativePlayerPosition, BlockPos templateSize) {
            this.helper = helper;
            player = addPlayer(relativePlayerPosition);
            spawnService = new ZombiesMobSpawnService(ownership, () -> List.copyOf(targets),
                    ZombiesRulesConfig.SpawnPointWeighting::new, counter);
            spawnService.configureNavigationContext(roomId, helper.getLevel(), new net.minecraft.world.phys.AABB(
                    helper.absolutePos(BlockPos.ZERO), helper.absolutePos(templateSize)));
        }

        ServerPlayer addPlayer(Vec3 relativePosition) {
            ServerPlayer added = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "navigation-test"));
            added.connection = new SilentPacketListener(added);
            added.setGameMode(GameType.SURVIVAL);
            Vec3 position = helper.absoluteVec(relativePosition);
            added.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
            targets.add(added);
            return added;
        }

        public RoomId roomId() {
            return roomId;
        }

        public ZombiesWaveRuntimeState waveState() {
            return waveState;
        }

        public ZombiesMobRecycleService createRecycler() {
            return new ZombiesMobRecycleService(ownership,
                    new ZombiesMobLifecycleService(ownership, spawnService), () -> List.copyOf(targets));
        }

        public int activeRoomCount() {
            return counter.roomCount(roomId);
        }

        public int ownedRoomCount() {
            return ownership.entitiesInRoom(roomId).size();
        }

        public void removeTarget(ServerPlayer target) {
            targets.remove(target);
        }

        public Mob spawn(String mobId, BlockPos position) {
            ZombiesMobSpawnService.SpawnResult result = attemptSpawn(mobId, position);
            helper.assertTrue(result.spawned(), "real room spawn must succeed for " + mobId + ": " + result);
            return result.entity().orElseThrow();
        }

        /** Supplemental wave-context fixtures still use the real production spawn and budget path. */
        public Mob spawn(String mobId, BlockPos position, ZombiesWaveDefinition wave) {
            ZombiesMobSpawnService.SpawnResult result = attemptSpawn(mobId, position, wave);
            helper.assertTrue(result.spawned(), "real configured-wave spawn must succeed for " + mobId + ": " + result);
            return result.entity().orElseThrow();
        }

        private ZombiesMobSpawnService.SpawnResult attemptSpawn(String mobId, BlockPos position) {
            ZombiesWaveDefinition wave = new Gson().fromJson(
                    "{\"wave\":1,\"maxAlive\":16,\"mobs\":[{\"entity\":\"minecraft:" + mobId
                            + "\",\"count\":1}]}", ZombiesWaveDefinition.class);
            wave.attachSource(null, 1, true);
            wave.applyDefaults(new ZombiesRulesConfig.Defaults());
            return attemptSpawn(mobId, position, wave);
        }

        private ZombiesMobSpawnService.SpawnResult attemptSpawn(String mobId, BlockPos position, ZombiesWaveDefinition wave) {
            java.util.Objects.requireNonNull(wave, "wave");
            BlockPos actualSpawn = helper.absolutePos(position);
            // Structure placement forces chunks asynchronously. Start the behavioral clock
            // only after the annotation's setup ticks, and verify actual entity readiness.
            helper.assertTrue(helper.getLevel().isPositionEntityTicking(actualSpawn)
                            && helper.getLevel().areEntitiesLoaded(new net.minecraft.world.level.ChunkPos(actualSpawn).toLong()),
                    "test environment must have a ticking, entity-loaded spawn chunk before room registration: " + actualSpawn);
            waveState = new ZombiesWaveRuntimeState();
            waveState.prepareTargetWave(wave.getWave());
            waveState.beginTargetWave(wave);
            ZombiesZombieSpawnData spawn = new ZombiesZombieSpawnData("test-spawn", 0, 1.0D,
                    helper.getLevel().dimension(), helper.absolutePos(position), 0.0F, 0.0F);
            ZombiesMapObjects objects = new ZombiesMapObjects(List.of(), List.of(spawn), List.of(),
                    List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of());
            ZombiesMobSpawnService.SpawnResult result = spawnService.spawnNext(roomId, helper.getLevel(), objects,
                    waveState, wave, Set.of(0));
            result.entity().ifPresent(mob -> {
                mob.getRandom().setSeed(ZombiesNavigationTestReport.SEED + mobs.size());
                mobs.add(mob);
            });
            return result;
        }

        @Override
        public void close() {
            mobs.forEach(Mob::discard);
            ownership.clearRoom(roomId);
            counter.clearRoom(roomId);
            spawnService.resetNavigationRuntime();
            targets.forEach(ServerPlayer::discard);
        }
    }

    private static final class SilentPacketListener extends ServerGamePacketListenerImpl {
        private SilentPacketListener(ServerPlayer player) {
            super(player.getServer(), new Connection(PacketFlow.SERVERBOUND), player);
        }

        @Override
        public void send(Packet<?> packet) {
            // The target is a real ServerPlayer, but no client connection is needed for server AI tests.
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener) {
            // Discard outbound packets for the detached test player.
        }
    }
}
