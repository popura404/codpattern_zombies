package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestTiming;
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

/** Supplemental N01 topology scaling; these are implementation-time fixtures, not a historical baseline. */
@GameTestHolder("codpattern_navigation_scaled_routes")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationScaledRouteGameTests {
    private static final int DEADLINE = 3200;
    private static final String TEMPLATE = "zombies_navigation_detour";
    private ZombiesNavigationScaledRouteGameTests() { }

    @GameTest(setupTicks=20,template=TEMPLATE,batch="navigation_scaled_16",timeoutTicks=DEADLINE+10)
    public static void originalZombieCompletesSixteenScaleUTopology(GameTestHelper helper) { start(helper,16,9,false); }
    @GameTest(setupTicks=20,template=TEMPLATE,batch="navigation_scaled_32",timeoutTicks=DEADLINE+10)
    public static void originalZombieCompletesThirtyTwoScaleUTopology(GameTestHelper helper) { start(helper,32,17,false); }
    @GameTest(setupTicks=20,template=TEMPLATE,batch="navigation_scaled_64",timeoutTicks=DEADLINE+10)
    public static void originalZombieCompletesSixtyFourScaleUTopology(GameTestHelper helper) { start(helper,64,33,false); }
    @GameTest(setupTicks=20,template=TEMPLATE,batch="navigation_scaled_128",timeoutTicks=DEADLINE+10)
    public static void originalZombieCompletesOneHundredTwentyEightScaleUTopology(GameTestHelper helper) { start(helper,128,65,false); }
    @GameTest(setupTicks=20,template=TEMPLATE,batch="navigation_scaled_branches",timeoutTicks=DEADLINE+10)
    public static void originalZombiePassesDeadEndBranchesToReachTheRealExit(GameTestHelper helper) { start(helper,64,33,true); }

    private static void start(GameTestHelper helper,int nominal,int arm,boolean branches) {
        ZombiesNavigationTestTiming.begin(helper, "u-scale-"+nominal+(branches?"-branches":""), true);
        int depth=arm+4;
        for(int x=0;x<14;x++) for(int z=0;z<depth;z++) for(int y=0;y<7;y++) {
            boolean shell=y==0||y==6||x==0||x==13||z==0||z==depth-1;
            helper.setBlock(new BlockPos(x,y,z),shell?Blocks.STONE:Blocks.AIR);
        }
        for(int z=1;z<=arm;z++) for(int y=1;y<=5;y++) helper.setBlock(new BlockPos(6,y,z),Blocks.STONE);
        if(branches) for(int end:new int[]{9,17,25}) {
            // Three real south-facing dead ends on the east side of the left approach lane.
            // The independent known witness remains at x=2.5, west of each partition.
            for(int z=end-4;z<=end;z++) for(int y=1;y<=5;y++) helper.setBlock(new BlockPos(3,y,z),Blocks.STONE);
            for(int x=4;x<=5;x++) for(int y=1;y<=5;y++) helper.setBlock(new BlockPos(x,y,end),Blocks.STONE);
        }
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(10.5,1,3.5),new BlockPos(14,7,depth));
        try {
            Mob mob=fixture.spawn("zombie",new BlockPos(3,1,3));
            Run run=new Run(helper,fixture,mob,nominal,arm,branches);
            helper.onEachTick(run::observe);
        } catch(RuntimeException|Error failure) { fixture.close();throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMobRecycleService recycler;
        final Mob mob;
        final UUID original;
        final Vec3 origin,start,target;
        final int nominal,arm;
        final boolean branches;
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        Vec3 previous;
        double travelled,farthest;
        boolean crossedFarEnd,finished;
        int requeued,discarded;
        long previousGeometry,previousExpansions;
        int maxRoomGeometry,maxRoomExpansions;

        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,Mob mob,int nominal,int arm,boolean branches) {
            this.helper=helper;this.fixture=fixture;this.mob=mob;this.nominal=nominal;this.arm=arm;this.branches=branches;
            original=mob.getUUID();origin=helper.absoluteVec(Vec3.ZERO);start=mob.position();previous=start;
            target=fixture.player.position();farthest=start.distanceTo(target);recycler=fixture.createRecycler();
            recycler.tick(fixture.roomId(),helper.getLevel(),fixture.waveState(),0);
        }
        void observe() {
            if(finished)return;
            try {
                helper.assertTrue(mob.isAlive()&&!mob.isRemoved()&&helper.getLevel().getEntity(original)==mob,
                        "the original UUID must complete the static U route without replacement");
                helper.assertTrue(fixture.player.isAlive()&&fixture.player.position().distanceToSqr(target)<1.0E-8,
                        "the fixed target must not move or disappear to help navigation");
                Vec3 current=mob.position();double movement=current.distanceTo(previous);
                helper.assertTrue(movement<2,"flat-route arrival must result from ordinary walking, never same-UUID teleportation");
                travelled+=movement;farthest=Math.max(farthest,current.distanceTo(target));
                Vec3 relative=current.subtract(origin),old=previous.subtract(origin);
                if(old.x<=6.5&&relative.x>6.5) {
                    helper.assertTrue(Math.min(old.z,relative.z)>=arm+1.0,
                            "the real entity must cross the wall plane beyond the solid far end");
                    crossedFarEnd=true;
                }
                previous=current;
                var recycled=recycler.tick(fixture.roomId(),helper.getLevel(),fixture.waveState(),helper.getTick());
                requeued+=recycled.requeued();discarded+=recycled.discarded();
                helper.assertTrue(requeued==0&&discarded==0,"a reachable static scaled route may not recycle an original mob");
                helper.assertTrue(fixture.waveState().activeZombies()==1&&fixture.waveState().remainingBudget()==0
                                &&fixture.activeRoomCount()==1&&fixture.ownedRoomCount()==1,
                        "real navigation and recycling must preserve the single consumed wave budget and ownership");
                LayeredNavigationRuntime runtime=LayeredNavigationRuntime.of(mob);
                if(runtime!=null) {
                    var stats=runtime.planningStats();
                    int geometry=(int)(stats.geometry()-previousGeometry),expansions=(int)(stats.expansions()-previousExpansions);
                    maxRoomGeometry=Math.max(maxRoomGeometry,geometry);maxRoomExpansions=Math.max(maxRoomExpansions,expansions);
                    helper.assertTrue(geometry<=256&&expansions<=1024&&runtime.schedulerMetrics().geometry()<=1024
                                    &&runtime.schedulerMetrics().expansions()<=4096,
                            "scaled graph work must obey the unchanged room and server budgets");
                    previousGeometry=stats.geometry();previousExpansions=stats.expansions();
                }
                if(helper.getTick()%20==0)trajectory.add(Map.of("tick",helper.getTick(),"position",point(current),"onGround",mob.onGround()));
                double reach=mob.getBbWidth()*2.0;
                boolean arrived=mob.onGround()&&Math.abs(mob.getY()-target.y)<.05&&mob.getTarget()==fixture.player
                        &&mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                        &&mob.getSensing().hasLineOfSight(fixture.player);
                if(arrived) {
                    helper.assertTrue(crossedFarEnd&&farthest>start.distanceTo(target)+.25,
                            "arrival must include the actual U detour that initially moves farther from the target");
                    helper.assertTrue(travelled+.15>=minimumWalkToEngage(),"travel must satisfy the independent solid-wall geometric lower bound");
                    finish(true,"same original UUID walked around the far end and reached the stationary target");
                } else if(helper.getTick()>=DEADLINE)finish(false,"original mob exceeded the fixed 3200-tick scaled-route deadline");
            } catch(RuntimeException|Error failure) {
                if(!finished)finish(false,failure.getMessage());
                throw failure;
            }
        }
        double minimumWalkToEngage() {
            double half=mob.getBbWidth()/2.0,dz=arm+1+half-3.5;
            double left=6-half-3.5,right=10.5-(7+half);
            double entire=Math.hypot(left,dz)+1+2*half+Math.hypot(right,dz);
            return entire-Math.sqrt(Math.pow(mob.getBbWidth()*2,2)+fixture.player.getBbWidth());
        }
        void finish(boolean success,String reason) {
            if(finished)return;
            finished=true;
            LayeredNavigationRuntime runtime=LayeredNavigationRuntime.of(mob);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("sourceRequirement","N01 scaled U topology and multiple real branch choices");
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("independentReferenceTraversalMeasured",false);
            report.put("deadlinePolicy","3200 ticks fixed before first execution of these supplemental fixtures; not retrospective pre-implementation calibration");
            report.put("nominalRouteScale",nominal);report.put("wallArmBlocks",arm);report.put("deadEndBranches",branches?3:0);
            report.put("knownGeometricWitness",List.of(List.of(3.5,1.0,3.5),List.of(branches?2.5:3.5,1.0,3.5),
                    List.of(branches?2.5:3.5,1.0,arm+1.5),List.of(10.5,1.0,arm+1.5),List.of(10.5,1.0,3.5)));
            report.put("knownWitnessLength",2.0*arm+3+(branches?2:0));report.put("minimumWalkToEngage",minimumWalkToEngage());
            report.put("uuid",original.toString());report.put("targetUuid",fixture.player.getUUID().toString());
            report.put("start",point(start));report.put("end",point(mob.position()));report.put("target",point(target));
            report.put("elapsedTicks",helper.getTick());report.put("frozenDeadlineTicks",DEADLINE);
            report.put("travelledDistance",travelled);report.put("farthestTargetDistance",farthest);report.put("crossedFarEnd",crossedFarEnd);
            report.put("mobMovedByFixture",false);report.put("realRecyclerCalled",true);report.put("requeued",requeued);report.put("discarded",discarded);
            report.put("remainingBudget",fixture.waveState().remainingBudget());report.put("activeZombies",fixture.waveState().activeZombies());
            report.put("maxRoomGeometryPerTick",maxRoomGeometry);report.put("maxRoomExpansionsPerTick",maxRoomExpansions);
            report.put("runtime",fixture.spawnService.navigationRuntimeMetrics());report.put("execution",runtime==null?"legacy":runtime.describe(mob));
            report.put("trajectory",trajectory);report.put("success",success);report.put("reason",reason);
            recycler.reset();fixture.close();
            report.put("controllersAfterCleanup",runtime==null?0:runtime.controllerCount());
            report.put("transientEntriesAfterCleanup",runtime==null?0:runtime.cacheStats().transientEntries());
            report.put("ownedAfterCleanup",fixture.ownedRoomCount());report.put("activeAfterCleanup",fixture.activeRoomCount());
            ZombiesNavigationTestReport.write("u-scale-"+nominal+(branches?"-branches":""),report);
            helper.assertTrue(success,reason);ZombiesNavigationTestTiming.succeed(helper);
        }
    }
    private static List<Double> point(Vec3 point) {return List.of(point.x,point.y,point.z);}
}
