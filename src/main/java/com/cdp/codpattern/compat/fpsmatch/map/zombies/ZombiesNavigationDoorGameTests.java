package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestTiming;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Player-operated door changes only; native raider door interaction is tested separately. */
@GameTestHolder("codpattern_navigation_doors")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDoorGameTests {
    private ZombiesNavigationDoorGameTests() { }
    enum Scenario { COLD, RECOVERY, CLOSURE }

    // Separate batches guarantee the 100-tick observations have no competing room work.
    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_door_cold",timeoutTicks=110)
    public static void genuinelyColdRoomCrossesNewlyOpenedOakDoorWithinOneHundredTicks(GameTestHelper helper) {
        start(helper,Scenario.COLD);
    }
    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_door_recovery",timeoutTicks=210)
    public static void roomBlockedByClosedOakDoorRecoversWithinOneHundredTicksOfOpening(GameTestHelper helper) {
        start(helper,Scenario.RECOVERY);
    }
    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_door_closure",timeoutTicks=250)
    public static void closingOakDoorStopsExistingRouteThenOpeningRestoresPursuit(GameTestHelper helper) {
        start(helper,Scenario.CLOSURE);
    }

    static void corridor(GameTestHelper helper,boolean open) {
        for (int x=2;x<=17;x++) {
            helper.setBlock(new BlockPos(x,0,8),Blocks.STONE);helper.setBlock(new BlockPos(x,3,8),Blocks.STONE);
            for (int y=1;y<=3;y++) {
                helper.setBlock(new BlockPos(x,y,7),Blocks.STONE);helper.setBlock(new BlockPos(x,y,9),Blocks.STONE);
            }
        }
        for (int y=1;y<=3;y++) {
            helper.setBlock(new BlockPos(2,y,8),Blocks.STONE);helper.setBlock(new BlockPos(17,y,8),Blocks.STONE);
        }
        var state=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.EAST)
                .setValue(DoorBlock.OPEN,open).setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER);
        helper.setBlock(new BlockPos(8,1,8),state);
        helper.setBlock(new BlockPos(8,2,8),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
    }

    private static void start(GameTestHelper helper,Scenario scenario) {
        ZombiesNavigationTestTiming.begin(helper, "oak-door-"+scenario.name().toLowerCase(java.util.Locale.ROOT), true);
        corridor(helper,scenario==Scenario.CLOSURE);
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(14.5,1,8.5),new BlockPos(24,12,24));
        try {
            Mob mob=fixture.spawn("zombie",new BlockPos(5,1,8));
            Run run=new Run(helper,fixture,mob,scenario);
            if (scenario==Scenario.COLD) run.open();
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false); fixture.close();throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final Mob mob;
        final LayeredNavigationRuntime runtime;
        final Scenario scenario;
        final UUID uuid;
        final BlockPos door;
        final Vec3 target;
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        final Map<String,Object> report=new LinkedHashMap<>();
        Vec3 previous,openedFeet;
        AABB closedPlane;
        long openedAt=-1,closedAt=-1,routeStartedAt=-1,crossedAt=-1;
        boolean finished,closedExistingPath;

        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,Mob mob,Scenario scenario) {
            this.helper=helper;this.fixture=fixture;this.mob=mob;this.scenario=scenario;
            runtime=LayeredNavigationRuntime.of(mob);uuid=mob.getUUID();previous=mob.position();target=fixture.player.position();
            door=helper.absolutePos(new BlockPos(8,1,8));
            helper.assertTrue(runtime!=null,"door recovery requires the installed layered room runtime");
            helper.assertTrue(runtime.cacheStats().nodes()==0 && runtime.cacheStats().geometryCells()==0
                            && runtime.planningStats().requests()==0,"each door fixture must start with an actually cold room cache");
            closedPlane=helper.getLevel().getBlockState(door).setValue(DoorBlock.OPEN,false)
                    .getCollisionShape(helper.getLevel(),door,CollisionContext.of(mob)).move(door.getX(),door.getY(),door.getZ()).bounds();
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("nativeDoorInteractionAsserted",false);report.put("doorActor","player via DoorBlock.use");
            report.put("uuid",uuid.toString());report.put("coldAtRoomBirth",true);report.put("cacheAtBirth",runtime.cacheStats());
        }

        void useDoor() {
            var state=helper.getLevel().getBlockState(door);
            helper.assertTrue(state.is(Blocks.OAK_DOOR),"the physical oak door cannot be removed to repair the route");
            ((DoorBlock)state.getBlock()).use(state,helper.getLevel(),door,fixture.player,InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(door),Direction.WEST,door,false));
        }

        void open() {
            report.put("cacheAtOpening",runtime.cacheStats());
            boolean cold=runtime.cacheStats().tiles()==0 && runtime.cacheStats().nodes()==0
                    && runtime.cacheStats().geometryCells()==0 && runtime.planningStats().requests()==0;
            report.put("coldAtOpening",cold);
            if (scenario==Scenario.COLD) helper.assertTrue(cold,"the cold-opening case cannot reset or merely rename a warm runtime");
            helper.assertTrue(!helper.getLevel().getBlockState(door).getValue(DoorBlock.OPEN),"opening requires the actual closed state");
            useDoor();openedAt=helper.getTick();openedFeet=mob.position();
            helper.assertTrue(helper.getLevel().getBlockState(door).getValue(DoorBlock.OPEN),"player interaction must really open the door");
        }

        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(uuid)==mob,
                        "the original entity must survive door closure and reopening");
                helper.assertTrue(fixture.player.position().distanceToSqr(target)<1e-8,"the chase target may not move to help door recovery");
                helper.assertTrue(mob.position().distanceTo(previous)<1,"no teleport or artificial displacement may cross the door");
                var scheduler=NavigationScheduler.forServer(helper.getLevel().getServer());
                helper.assertTrue(scheduler.registeredRoomCount()==1 && scheduler.roomCount()<=1,
                        "the fixed 100-tick door gate requires a single room with no competing navigation budget");
                helper.assertTrue(!((GroundPathNavigation)mob.getNavigation()).getNodeEvaluator().canOpenDoors(),
                        "the fixture must not grant an ordinary room zombie native door-opening capability");
                if (scenario==Scenario.CLOSURE && closedAt<0) {
                    var path=mob.getNavigation().getPath();
                    if (path!=null && !path.isDone() && mob.getBoundingBox().maxX<closedPlane.minX-.25) {
                        helper.assertTrue(helper.getLevel().getBlockState(door).getValue(DoorBlock.OPEN),"the original route must start with an open door");
                        useDoor();closedAt=helper.getTick();closedExistingPath=true;
                        report.put("nativePathNodesAtClosure",path.getNodeCount());
                        report.put("activeEdgeAtClosure",runtime.activeEdge(mob));
                    } else helper.assertTrue(helper.getTick()<40,"the open-door fixture must naturally obtain an active path before closure");
                }
                boolean closed=!helper.getLevel().getBlockState(door).getValue(DoorBlock.OPEN);
                if (closed) helper.assertTrue(mob.getBoundingBox().maxX<=closedPlane.minX+1e-4,
                        "the whole body must remain on the original side of a physically closed door");
                if (openedAt<0 && (scenario==Scenario.RECOVERY && helper.getTick()>=100
                        || scenario==Scenario.CLOSURE && closedAt>=0 && helper.getTick()-closedAt>=100)) open();
                if (openedAt>=0) {
                    var path=mob.getNavigation().getPath();
                    if (routeStartedAt<0 && (path!=null && !path.isDone() || runtime.activeEdge(mob)!=null)
                            && mob.position().distanceToSqr(openedFeet)>.01) routeStartedAt=helper.getTick();
                    if (crossedAt<0 && mob.getBoundingBox().minX>door.getX()+1) crossedAt=helper.getTick();
                    double reach=mob.getBbWidth()*2;
                    boolean arrived=mob.onGround() && Math.abs(mob.getY()-target.y)<1e-6 && mob.getTarget()==fixture.player
                            && mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                            && mob.getSensing().hasLineOfSight(fixture.player);
                    if (arrived) {
                        helper.assertTrue(routeStartedAt>=openedAt && crossedAt>=openedAt
                                        && helper.getTick()-openedAt<=100 && (scenario!=Scenario.CLOSURE || closedExistingPath),
                                "an actual new movement and complete physical crossing must restore pursuit within 100 ticks");
                        finish(true,"original UUID physically crossed the player-opened oak door and reached melee range");
                    } else if (helper.getTick()-openedAt>=100) finish(false,"fixed 100-tick opening recovery deadline exceeded: "+runtime.describe(mob));
                }
                if (helper.getTick()%5==0) trajectory.add(Map.of("tick",helper.getTick(),"feet",position(mob.position()),
                        "doorOpen",!closed,"execution",runtime.describe(mob)));
                previous=mob.position();
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }

        void finish(boolean success,String reason) {
            finished=true;report.put("closedAtTick",closedAt);report.put("openedAtTick",openedAt);
            report.put("routeStartedAtTick",routeStartedAt);report.put("fullyCrossedAtTick",crossedAt);
            report.put("elapsedAfterOpening",openedAt<0?null:helper.getTick()-openedAt);
            report.put("closedExistingPath",closedExistingPath);report.put("endFeet",position(mob.position()));
            report.put("runtime",fixture.spawnService.navigationRuntimeMetrics());report.put("trajectory",trajectory);
            report.put("success",success);report.put("reason",reason);
            try {
                fixture.close();
                var cache=runtime.cacheStats();
                helper.assertTrue(runtime.controllerCount()==0 && runtime.planningStats().activeRequests()==0
                                && runtime.planningStats().searchRecords()==0 && cache.tiles()==0 && cache.transientEntries()==0
                                && fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                        "door cleanup must release every controller, planning request, cached tile, transient lease, and ownership entry");
                report.put("cleanupControllers",runtime.controllerCount());report.put("cleanupCache",cache);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                report.put("success",false);report.put("cleanupFailure",failure.getMessage());throw failure;
            } finally { ZombiesNavigationTestReport.write("oak-door-"+scenario.name().toLowerCase(java.util.Locale.ROOT),report); }
            helper.assertTrue(success,reason);ZombiesNavigationTestTiming.succeed(helper);
        }
    }
    private static List<Double> position(Vec3 point) { return List.of(point.x,point.y,point.z); }
}
