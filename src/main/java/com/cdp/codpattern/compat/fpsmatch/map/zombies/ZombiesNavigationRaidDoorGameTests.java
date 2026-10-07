package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real active-raid capability; the fixture never opens the door or changes navigator capabilities. */
@GameTestHolder("codpattern_navigation_raid")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationRaidDoorGameTests {
    private ZombiesNavigationRaidDoorGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_raid_door",timeoutTicks=210)
    public static void roomVindicatorInRealActiveRaidRunsNativeOpenDoorGoalAndContinuesPursuit(GameTestHelper helper) {
        ZombiesNavigationDoorGameTests.corridor(helper,false);
        BlockPos foot=new BlockPos(14,1,4),head=new BlockPos(15,1,4);
        helper.setBlock(foot.below(),Blocks.STONE);helper.setBlock(head.below(),Blocks.STONE);
        var bed=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST);
        helper.setBlock(foot,bed.setValue(BedBlock.PART,BedPart.FOOT));
        helper.setBlock(head,bed.setValue(BedBlock.PART,BedPart.HEAD));
        BlockPos actualHead=helper.absolutePos(head);
        var poi=helper.getLevel().getPoiManager();
        if (!poi.existsAtPosition(PoiTypes.HOME,actualHead))
            poi.add(actualHead,PoiTypes.forState(helper.getLevel().getBlockState(actualHead)).orElseThrow());
        helper.assertTrue(poi.take(type -> type.is(PoiTypes.HOME),(type,pos) -> pos.equals(actualHead),actualHead,1).isPresent(),
                "the fixture must claim an actual bed HOME POI before starting its real raid");
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(14.5,1,8.5),new BlockPos(24,12,24));
        Vindicator entity;
        try { entity=(Vindicator)fixture.spawn("vindicator",new BlockPos(5,1,8)); }
        catch (RuntimeException | Error failure) { fixture.close();poi.remove(actualHead);throw failure; }
        Raid[] raid={null};Vindicator[] mob={entity};LayeredNavigationRuntime[] runtime={LayeredNavigationRuntime.of(entity)};
        boolean[] finished={false},runningObserved={false},stoppedObserved={false},openedObserved={false},managedInteraction={false};
        boolean[] recoveryObserved={false};
        Vec3[] previous={entity.position()};long[] joinedAt={-1},openedAt={-1};
        BlockPos door=helper.absolutePos(new BlockPos(8,1,8));
        List<Map<String,Object>> trajectory=new ArrayList<>();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
        report.put("raidCreatedBy","ServerLevel.getRaids().createOrExtendRaid with BAD_OMEN");
        report.put("actualBedPoiClaimed",true);report.put("fixtureOpensDoor",false);report.put("fixtureChangesCanOpenDoors",false);
        report.put("spawnedAtTick",helper.getTick());report.put("uuid",entity.getUUID().toString());
        Runnable cleanup=() -> {
            if (raid[0]!=null) raid[0].stop();
            try { fixture.close(); }
            finally {
                if (poi.existsAtPosition(PoiTypes.HOME,actualHead) && poi.getFreeTickets(actualHead)==0) poi.release(actualHead);
                poi.remove(actualHead);
                helper.setBlock(head,Blocks.AIR);helper.setBlock(foot,Blocks.AIR);
            }
            if (runtime[0]!=null) {
                var cache=runtime[0].cacheStats();
                helper.assertTrue(runtime[0].controllerCount()==0 && runtime[0].planningStats().activeRequests()==0
                                && runtime[0].planningStats().searchRecords()==0 && cache.tiles()==0 && cache.transientEntries()==0
                                && fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                        "raid cleanup must release all room navigation and entity ownership state");
                report.put("cleanupControllers",runtime[0].controllerCount());report.put("cleanupCache",cache);
            }
        };
        helper.onEachTick(() -> {
            if (finished[0]) return;
            try {
                helper.assertTrue(helper.getTick()<200,"the independent real-raid observation window cannot be extended");
                if (raid[0]==null) {
                    helper.assertTrue(runtime[0]!=null && mob[0].isAlive() && !mob[0].isRemoved(),
                            "the pre-raid room entity must naturally run its installed recovery controller");
                    helper.assertTrue(!mob[0].hasActiveRaid() && !((GroundPathNavigation)mob[0].getNavigation()).getNodeEvaluator().canOpenDoors(),
                            "the first forty ticks must not grant native raid door capability");
                    recoveryObserved[0]|=mob[0].goalSelector.getRunningGoals()
                            .anyMatch(goal -> goal.getGoal()==runtime[0].movementGoal(mob[0]));
                    helper.assertTrue(mob[0].position().distanceTo(previous[0])<1,"recovery must use only normal entity movement");
                    previous[0]=mob[0].position();
                    helper.assertTrue(helper.getTick()<20 || helper.getLevel().isVillage(actualHead),
                            "the claimed physical bed must become an actual village POI");
                    if (helper.getTick()<40) return;
                    helper.assertTrue(recoveryObserved[0] && runtime[0].planningStats().expansions()>0,
                            "before enabling the raid, the actual room recovery Goal must have run and expanded a blocked-door plan");
                    report.put("recoveryGoalRanBeforeRaid",recoveryObserved[0]);
                    report.put("planningBeforeRaid",runtime[0].planningStats());
                    fixture.player.addEffect(new MobEffectInstance(MobEffects.BAD_OMEN,600,0));
                    raid[0]=helper.getLevel().getRaids().createOrExtendRaid(fixture.player);
                    helper.assertTrue(raid[0]!=null && raid[0].isActive(),"the real raid manager must create an active raid");
                    Vec3 beforeJoin=mob[0].position();
                    // existing=true skips the native join method's new-spawn relocation/finalization branch.
                    raid[0].joinRaid(1,mob[0],null,true);
                    helper.assertTrue(mob[0].position().distanceToSqr(beforeJoin)==0,
                            "joining the real raid must not move the already spawned room entity");
                    helper.assertTrue(runtime[0]!=null && mob[0].hasActiveRaid(),"the same room entity must retain its controller and active raid");
                    previous[0]=beforeJoin;joinedAt[0]=helper.getTick();
                    report.put("raidId",raid[0].getId());report.put("raidJoinedAtTick",joinedAt[0]);
                    report.put("feetBeforeAndAfterJoin",position(beforeJoin));return;
                }
                helper.assertTrue(entity.isAlive() && !entity.isRemoved() && helper.getLevel().getEntity(entity.getUUID())==entity,
                        "the original room vindicator must perform the native action and pursuit");
                helper.assertTrue(raid[0].isActive() && entity.hasActiveRaid() && helper.getLevel().isRaided(entity.blockPosition()),
                        "native door permission must come from a real active raid throughout execution");
                if (!((GroundPathNavigation)entity.getNavigation()).getNodeEvaluator().canOpenDoors()) {
                    helper.assertTrue(helper.getTick()-joinedAt[0]<3,
                            "Vindicator.customServerAiStep must naturally enable door opening in the raid area");
                    return;
                }
                helper.assertTrue(entity.position().distanceTo(previous[0])<1,"the fixture cannot relocate the raider through the door");
                boolean running=entity.goalSelector.getRunningGoals().anyMatch(goal -> goal.getGoal() instanceof OpenDoorGoal
                        && goal.getGoal().getClass().getSimpleName().equals("RaiderOpenDoorGoal"));
                runningObserved[0]|=running;stoppedObserved[0]|=runningObserved[0] && !running;
                var state=helper.getLevel().getBlockState(door);
                helper.assertTrue(state.is(Blocks.OAK_DOOR),"breaking or removing the door cannot substitute for the native open-door action");
                if (state.getValue(DoorBlock.OPEN)) {
                    openedObserved[0]=true;if (openedAt[0]<0) openedAt[0]=helper.getTick();
                }
                var active=runtime[0].activeEdge(entity);
                managedInteraction[0]|=active!=null && active.action()==TraversalEdge.Action.NATIVE_INTERACTION;
                if (helper.getTick()%2==0) trajectory.add(Map.of("tick",helper.getTick(),"feet",position(entity.position()),
                        "doorOpen",state.getValue(DoorBlock.OPEN),"nativeOpenDoorGoalRunning",running,"execution",runtime[0].describe(entity)));
                previous[0]=entity.position();
                double reach=entity.getBbWidth()*2;
                if (entity.onGround() && entity.getBoundingBox().minX>door.getX()+1 && entity.getTarget()==fixture.player
                        && entity.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                        && entity.getSensing().hasLineOfSight(fixture.player)) {
                    helper.assertTrue(runningObserved[0] && stoppedObserved[0] && openedObserved[0],
                            "the actual native raider door goal must start, open the intact door, finish, and permit physical pursuit");
                    finished[0]=true;report.put("success",true);report.put("openedAtTick",openedAt[0]);
                    report.put("elapsedTicks",helper.getTick());report.put("nativeOpenDoorGoalStarted",runningObserved[0]);
                    report.put("nativeOpenDoorGoalFinished",stoppedObserved[0]);report.put("managedInteractionEdgeObserved",managedInteraction[0]);
                    report.put("endFeet",position(entity.position()));report.put("trajectory",trajectory);
                    cleanup.run();report.put("raidStopped",raid[0].isStopped());
                    ZombiesNavigationTestReport.write("real-raid-native-door",report);helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                finished[0]=true;report.put("success",false);report.put("reason",failure.getMessage());
                report.put("elapsedTicks",helper.getTick());report.put("nativeOpenDoorGoalStarted",runningObserved[0]);
                report.put("nativeOpenDoorGoalFinished",stoppedObserved[0]);report.put("doorOpened",openedObserved[0]);report.put("trajectory",trajectory);
                try { cleanup.run(); } finally { ZombiesNavigationTestReport.write("real-raid-native-door",report); }
                throw failure;
            }
        });
    }
    private static List<Double> position(Vec3 point) { return List.of(point.x,point.y,point.z); }
}
