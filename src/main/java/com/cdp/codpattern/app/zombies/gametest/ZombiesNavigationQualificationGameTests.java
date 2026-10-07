package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesGroundNavigationService;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Real entity ticks cancel ineligible pursuits and reacquire an eligible room survivor. */
@GameTestHolder("codpattern_navigation_qualification")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationQualificationGameTests {
    private static final int INVALIDATE_TICK=100, REPLACE_TICK=140, DEADLINE=400;
    private enum Change { DEATH, LEAVE_ROOM, DIMENSION }
    private ZombiesNavigationQualificationGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_qualification",timeoutTicks=DEADLINE+10)
    public static void deadTargetIsCancelledBeforeEligibleReplacement(GameTestHelper helper) { run(helper,Change.DEATH); }
    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_qualification",timeoutTicks=DEADLINE+10)
    public static void departedSurvivorIsCancelledBeforeEligibleReplacement(GameTestHelper helper) { run(helper,Change.LEAVE_ROOM); }
    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_qualification",timeoutTicks=DEADLINE+10)
    public static void transferredPlayerIsCancelledBeforeEligibleReplacement(GameTestHelper helper) { run(helper,Change.DIMENSION); }

    private static void run(GameTestHelper helper,Change change) {
        for (int x=0;x<24;x++) for (int z=0;z<24;z++) for (int y=0;y<7;y++)
            helper.setBlock(new BlockPos(x,y,z),y==0 || y==6 || x==0 || x==23 || z==0 || z==23
                    || x==9 ? Blocks.STONE:Blocks.AIR);
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(15.5,1,5.5));
        try { helper.onEachTick(new Run(helper,fixture,fixture.spawn("zombie",new BlockPos(3,1,5)),change)::tick); }
        catch (RuntimeException | Error failure) { fixture.close();throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final Mob mob;
        final UUID originalId;
        final Change change;
        final LayeredNavigationRuntime runtime;
        ServerPlayer replacement;
        boolean changed,cancelled,finished,sawOriginalTarget,sawPlanning;
        long cancellationTick=-1;
        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,Mob mob,Change change) {
            this.helper=helper;this.fixture=fixture;this.mob=mob;this.change=change;
            originalId=mob.getUUID();runtime=LayeredNavigationRuntime.of(mob);
            helper.assertTrue(runtime!=null,"qualification acceptance needs an installed layered room controller");
        }
        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                        "target changes must preserve the original room-owned monster UUID");
                helper.assertTrue(fixture.ownedRoomCount()==1 && fixture.activeRoomCount()==1
                                && fixture.waveState().remainingBudget()==0,
                        "target changes must not replace the entity or requeue its consumed wave budget");
                long tick=helper.getTick();
                if (!changed) {
                    sawOriginalTarget|=mob.getTarget()==fixture.player;
                    sawPlanning|=runtime.planningStats().expansions()>0 || runtime.planningStats().geometry()>0;
                    if (tick>=INVALIDATE_TICK) invalidate();
                } else {
                    helper.assertFalse(ZombiesGroundNavigationService.isLayeredTargetEligible(mob,fixture.player),
                            "the departed, dead, or transferred original target must remain ineligible");
                    if (replacement==null && mob.getTarget()!=fixture.player && runtime.activeEdge(mob)==null
                            && runtime.planningStats().activeRequests()==0) {
                        if (!cancelled) cancellationTick=tick;
                        cancelled=true;
                    }
                    if (tick>=INVALIDATE_TICK+24) helper.assertTrue(cancelled && mob.getTarget()!=fixture.player,
                            "native handoff and rechecks must cancel the old pursuit within 24 real entity ticks");
                    if (tick>=REPLACE_TICK && replacement==null) {
                        helper.assertTrue(cancelled,"cancellation must be observed before introducing another survivor");
                        replacement=fixture.addPlayer(new Vec3(3.5,1,14.5));
                    }
                    if (replacement!=null && mob.getTarget()==replacement && mob.onGround()
                            && mob.distanceToSqr(replacement)<=4 && mob.getSensing().hasLineOfSight(replacement)) {
                        helper.assertTrue(ZombiesGroundNavigationService.isLayeredTargetEligible(mob,replacement),
                                "replacement must satisfy the same production room eligibility predicate");
                        finish(true,"original monster physically reacquired an eligible room survivor");
                    }
                }
                if (!finished && tick>=DEADLINE) finish(false,"target cancellation/reacquisition deadline: "+runtime.describe(mob));
            } catch (RuntimeException | Error failure) {
                if (!finished) { finished=true;write(false,failure.getMessage());fixture.close(); }
                throw failure;
            }
        }
        void invalidate() {
            helper.assertTrue(sawOriginalTarget && sawPlanning,"invalidate an actual active room pursuit with planning work");
            switch (change) {
                case DEATH -> fixture.player.setHealth(0);
                case LEAVE_ROOM -> fixture.removeTarget(fixture.player);
                case DIMENSION -> {
                    var destination=helper.getLevel().getServer().getLevel(Level.NETHER);
                    helper.assertTrue(destination!=null && destination!=helper.getLevel(),"a distinct real server dimension is required");
                    fixture.player.teleportTo(destination,.5,80,.5,0,0);
                    helper.assertTrue(fixture.player.serverLevel()==destination,"the actual ServerPlayer must transfer dimensions");
                }
            }
            changed=true;
        }
        void finish(boolean success,String reason) {
            finished=true;write(success,reason);fixture.close();
            if (success) helper.succeed();else helper.fail(reason);
        }
        void write(boolean success,String reason) {
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("change",change.name());report.put("originalUuid",originalId.toString());
            report.put("originalTargetUuid",fixture.player.getUUID().toString());
            report.put("replacementTargetUuid",replacement==null?null:replacement.getUUID().toString());
            report.put("actualOriginalTargetDimension",fixture.player.level().dimension().location().toString());
            report.put("oldPursuitCancelledTick",cancellationTick);report.put("elapsedTestTicks",helper.getTick());
            report.put("frozenDeadlineTicks",DEADLINE);report.put("planning",runtime.planningStats());
            report.put("success",success);report.put("reason",reason);
            ZombiesNavigationTestReport.write("qualification-"+change.name().toLowerCase(java.util.Locale.ROOT),report);
        }
    }
}
