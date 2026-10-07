package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestTiming;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Two independent dynamic events; neither fixture moves or directly steers the room mob. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDynamicDropGameTests {
    private static final int DEPTH=32;
    private static final int DEADLINE=600;
    private ZombiesNavigationDynamicDropGameTests() { }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_dynamic_drop",timeoutTicks=DEADLINE+10)
    public static void blockedDepartureBeforeLeavingSelectsAnotherRealDrop(GameTestHelper helper) {
        start(helper,false);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_dynamic_drop",timeoutTicks=DEADLINE+10)
    public static void lostTargetDuringFallStillLandsOriginalEntity(GameTestHelper helper) {
        start(helper,true);
    }

    private static void start(GameTestHelper helper,boolean targetLeaves) {
        ZombiesNavigationTestTiming.begin(helper, targetLeaves ? "drop-dynamic-target-loss" : "drop-dynamic-blocked-departure", true);
        for (int x=0;x<20;x++) for (int z=0;z<20;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
            helper.setBlock(new BlockPos(x,69,z),Blocks.STONE);
            if (x==0 || x==19 || z==0 || z==19)
                for (int y=1;y<69;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        for (int x=3;x<=11;x++) for (int z=3;z<=11;z++)
            helper.setBlock(new BlockPos(x,DEPTH,z),Blocks.STONE);
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(8.5,1,8.5),new BlockPos(20,70,20));
        ZombiesMap map=new ZombiesMap(helper.getLevel(),fixture.roomId().mapName(),
                new AreaData(helper.absolutePos(BlockPos.ZERO),helper.absolutePos(new BlockPos(20,70,20))));
        FPSMCore.getInstance().registerMap(map.getGameType(),map);
        try {
            Mob mob=fixture.spawn("zombie",new BlockPos(8,DEPTH+1,8));
            mob.getRandom().setSeed(ZombiesNavigationTestReport.SEED);
            LayeredNavigationRuntime runtime=LayeredNavigationRuntime.of(mob);
            helper.assertTrue(runtime!=null,"dynamic DROP acceptance requires the layered engine");
            Run run=new Run(helper,fixture,map,mob,runtime,targetLeaves);
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false); close(fixture,map); throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMap map;
        final Mob mob;
        final LayeredNavigationRuntime runtime;
        final boolean targetLeaves;
        final UUID originalId;
        final Vec3 target;
        Vec3 previous;
        TraversalEdge changedEdge;
        boolean descending,changed,landed,finished,replacementDropExecuted;
        long landingTick;
        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map,Mob mob,
                LayeredNavigationRuntime runtime,boolean targetLeaves) {
            this.helper=helper;this.fixture=fixture;this.map=map;this.mob=mob;this.runtime=runtime;
            this.targetLeaves=targetLeaves;originalId=mob.getUUID();previous=mob.position();target=fixture.player.position();
        }
        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                        "dynamic DROP must preserve the original living room entity");
                helper.assertTrue(mob.position().distanceTo(previous)<12,"a teleport cannot satisfy natural descent");
                helper.assertTrue(fixture.player.position().distanceToSqr(target)<1e-8,
                        "the target event must not move either entity");
                TraversalEdge edge=runtime.activeEdge(mob);
                String description=runtime.describe(mob);
                if (!changed && edge!=null && edge.action()==TraversalEdge.Action.DROP) {
                    if (!targetLeaves && mob.onGround() && (description.contains("phase=APPROACH,")
                            || description.contains("phase=COMMIT,"))) {
                        Vec3 departure=edge.controlPoints().get(1);
                        BlockPos block=BlockPos.containing(departure);
                        AABB obstruction=new AABB(block).expandTowards(0,2,0);
                        helper.assertTrue(!mob.getBoundingBox().intersects(obstruction),
                                "the dynamic wall must not be placed inside the mob");
                        for (int y=0;y<3;y++) helper.getLevel().setBlockAndUpdate(block.above(y),Blocks.STONE.defaultBlockState());
                        NavigationScheduler.forServer(helper.getLevel().getServer()).invalidate(helper.getLevel(),obstruction,
                                "dynamic DROP fixture changed departure");
                        changed=true;changedEdge=edge;
                    } else if (targetLeaves && !mob.onGround() && description.contains("phase=FALL,")
                            && mob.getY()<edge.from().feet().y-1) {
                        fixture.removeTarget(fixture.player);
                        helper.assertTrue(!runtime.context().eligible(mob,fixture.player),
                                "removing the room target must invalidate the production target predicate");
                        changed=true;changedEdge=edge;
                    }
                }
                descending|=!mob.onGround() && mob.getY()<previous.y-.01;
                if (changed && !mob.onGround() && !targetLeaves && edge!=null && edge.action()==TraversalEdge.Action.DROP) {
                    helper.assertTrue(edge.controlPoints().get(1).distanceToSqr(changedEdge.controlPoints().get(1))>.01,
                            "a blocked old departure must be replaced before the entity falls");
                    replacementDropExecuted=true;
                }
                if (changed && targetLeaves && !mob.onGround())
                    helper.assertTrue(edge!=null && edge.equals(changedEdge),
                            "target loss must retain the committed landing column");
                if (descending && mob.onGround() && Math.abs(mob.getY()-target.y)<.05 && !landed) {
                    landed=true;landingTick=helper.getTick();
                }
                previous=mob.position();
                if (targetLeaves && changed && landed && helper.getTick()>=landingTick+5) {
                    helper.assertTrue(mob.onGround() && Math.abs(mob.getY()-target.y)<.05,
                            "the original entity must remain on its real landing surface");
                    helper.assertTrue(runtime.activeEdge(mob)==null,"a lost target must clear the old route after landing");
                    finish();
                } else if (!targetLeaves && changed && landed) {
                    double reach=mob.getBbWidth()*2;
                    if (mob.getTarget()==fixture.player && mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                            && mob.getSensing().hasLineOfSight(fixture.player)) {
                        helper.assertTrue(replacementDropExecuted,"arrival must execute a replacement managed DROP");
                        finish();
                    }
                }
                if (!finished && helper.getTick()>=DEADLINE)
                    ZombiesNavigationTestTiming.fail(helper, "dynamic DROP exceeded fixed 600 ticks; changed="+changed+", landed="+landed+", "+description);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                if (!finished) { finished=true;close(fixture,map); }
                throw failure;
            }
        }
        void finish() { finished=true;close(fixture,map);ZombiesNavigationTestTiming.succeed(helper); }
    }
    private static void close(ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map) {
        fixture.close();fixture.player.discard();
        try { map.resetGame(); }
        finally { FPSMCore.getInstance().unregisterMap(map);map.getMapTeams().retireCreatedScoreboardTeams(); }
    }
}
