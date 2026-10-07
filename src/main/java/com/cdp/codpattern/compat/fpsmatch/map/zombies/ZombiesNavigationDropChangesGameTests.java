package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Supplemental N06 tests: the only interventions are a block platform or one native knockback event. */
@GameTestHolder("codpattern_navigation_drop_changes")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDropChangesGameTests {
    private static final int DEPTH=32, PLATFORM_Y=16, DEADLINE=600;
    private static final double KNOCKBACK_STRENGTH=.25;
    private ZombiesNavigationDropChangesGameTests() { }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_changes",timeoutTicks=DEADLINE+10)
    public static void newPlatformDuringFallCausesRealEarlyLandingAndContinuedChase(GameTestHelper helper) {
        start(helper,false);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_changes",timeoutTicks=DEADLINE+10)
    public static void nativeKnockbackDuringFallPreservesLandingAndContinuedChase(GameTestHelper helper) {
        start(helper,true);
    }

    private static void start(GameTestHelper helper,boolean knockback) {
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
            LayeredNavigationRuntime runtime=LayeredNavigationRuntime.of(mob);
            helper.assertTrue(runtime!=null,"supplemental committed DROP changes require the layered engine");
            Run run=new Run(helper,fixture,map,mob,runtime,knockback);
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) { close(fixture,map);throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMap map;
        final Mob mob;
        final LayeredNavigationRuntime runtime;
        final boolean knockback;
        final UUID originalId;
        final float initialHealth;
        final Vec3 initialFeet,target;
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        Vec3 previous,eventFeet,firstLanding,knockbackDirection;
        TraversalEdge oldDrop;
        boolean changed,exceededEnvelope,oldRouteCleared,freshNavigation,finished;
        long eventTick=-1,firstLandingTick=-1;
        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map,Mob mob,
                LayeredNavigationRuntime runtime,boolean knockback) {
            this.helper=helper;this.fixture=fixture;this.map=map;this.mob=mob;this.runtime=runtime;this.knockback=knockback;
            originalId=mob.getUUID();initialHealth=mob.getHealth();initialFeet=mob.position();
            previous=initialFeet;target=fixture.player.position();
        }
        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                        "the original living UUID must perform the entire changed descent");
                helper.assertTrue(mob.getHealth()==initialHealth,"production fall protection must preserve health");
                helper.assertTrue(mob.position().distanceTo(previous)<12,"a teleport cannot satisfy changed descent");
                helper.assertTrue(fixture.player.position().distanceToSqr(target)<1e-8,"the target remains stationary");
                TraversalEdge edge=runtime.activeEdge(mob);
                String execution=runtime.describe(mob);
                if (!changed && edge!=null && edge.action()==TraversalEdge.Action.DROP && !mob.onGround()
                        && execution.contains("phase=FALL,") && mob.getY()<edge.from().feet().y-2) {
                    oldDrop=edge;eventFeet=mob.position();eventTick=helper.getTick();
                    if (knockback) {
                        Vec3 heading=edge.controlPoints().get(1).subtract(edge.from().feet());
                        knockbackDirection=new Vec3(heading.x,0,heading.z).normalize();
                        helper.assertTrue(knockbackDirection.lengthSqr()>.99,"the original DROP must have a real horizontal departure");
                        // LivingEntity.knockback applies ordinary resistance and its own native impulse semantics.
                        // No test code sets position, delta movement, vertical speed, or movement commands.
                        mob.knockback(KNOCKBACK_STRENGTH,-knockbackDirection.x,-knockbackDirection.z);
                    } else {
                        int x=(int)Math.floor(edge.to().feet().x),z=(int)Math.floor(edge.to().feet().z);
                        int y=helper.absolutePos(new BlockPos(0,PLATFORM_Y,0)).getY();
                        AABB platform=new AABB(x-1,y,z-1,x+2,y+1,z+2);
                        helper.assertTrue(platform.maxY+3<mob.getY() && !platform.intersects(mob.getBoundingBox()),
                                "the new platform must appear well below the falling body");
                        for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++)
                            helper.getLevel().setBlockAndUpdate(new BlockPos(x+dx,y,z+dz),Blocks.STONE.defaultBlockState());
                        NavigationScheduler.forServer(helper.getLevel().getServer()).invalidate(helper.getLevel(),platform,
                                "supplemental DROP gained an intermediate platform");
                    }
                    changed=true;sample(execution);
                }
                if (changed && firstLanding==null) {
                    if (!mob.onGround()) {
                        helper.assertTrue(runtime.activeEdge(mob)!=null && runtime.activeEdge(mob).equals(oldDrop),
                                "an already airborne entity must retain its committed descent until real contact");
                        Vec3 departure=oldDrop.controlPoints().get(1);
                        exceededEnvelope|=Math.abs(mob.getX()-departure.x)>.21 || Math.abs(mob.getZ()-departure.z)>.21;
                    } else {
                        firstLanding=mob.position();firstLandingTick=helper.getTick();
                        double expectedY=knockback?target.y:helper.absolutePos(new BlockPos(0,PLATFORM_Y+1,0)).getY();
                        helper.assertTrue(Math.abs(firstLanding.y-expectedY)<1e-6,
                                "the first observed contact must be the actual changed landing surface");
                        helper.assertTrue(!knockback || exceededEnvelope,
                                "native knockback must really carry the body beyond the old horizontal drift envelope");
                        sample(execution);
                    }
                }
                if (firstLanding!=null) {
                    TraversalEdge current=runtime.activeEdge(mob);
                    oldRouteCleared|=current==null || !current.equals(oldDrop);
                    if (oldRouteCleared && current!=null && !current.equals(oldDrop)
                            && Math.abs(current.from().feet().y-firstLanding.y)<1e-6) freshNavigation=true;
                    var nativePath=mob.getNavigation().getPath();
                    if (oldRouteCleared && nativePath!=null && nativePath.getNodeCount()>0
                            && Math.abs(nativePath.getNode(0).y-firstLanding.y)<1e-6) freshNavigation=true;
                }
                previous=mob.position();
                if (helper.getTick()%5==0) sample(execution);
                double reach=mob.getBbWidth()*2;
                boolean arrived=mob.onGround() && Math.abs(mob.getY()-target.y)<1e-6 && mob.getTarget()==fixture.player
                        && mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                        && mob.getSensing().hasLineOfSight(fixture.player);
                if (arrived) {
                    helper.assertTrue(changed && firstLanding!=null && oldRouteCleared && freshNavigation,
                            "arrival requires the natural event trigger, real contact, old-route disposal, and new navigation from that surface");
                    finish(true,"the original entity landed on the changed surface and resumed chase");
                } else if (helper.getTick()>=DEADLINE)
                    finish(false,"fixed 600-tick changed-DROP deadline exceeded; event="+changed+", firstLanding="+firstLanding
                            +", oldRouteCleared="+oldRouteCleared+", freshNavigation="+freshNavigation+", "+execution);
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }
        void sample(String execution) {
            trajectory.add(Map.of("tick",helper.getTick(),"feet",position(mob.position()),"onGround",mob.onGround(),"execution",execution));
        }
        void finish(boolean success,String reason) {
            finished=true;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("uuid",originalId.toString());report.put("startFeet",position(initialFeet));report.put("endFeet",position(mob.position()));
            report.put("targetFeet",position(target));report.put("elapsedTicks",helper.getTick());report.put("frozenDeadlineTicks",DEADLINE);
            report.put("event",knockback?"one native LivingEntity.knockback":"nine stone blocks below the falling body");
            report.put("eventObserved",changed);report.put("eventTick",eventTick);report.put("eventFeet",eventFeet==null?null:position(eventFeet));
            report.put("firstLandingFeet",firstLanding==null?null:position(firstLanding));report.put("firstLandingTick",firstLandingTick);
            report.put("outsideOldDriftEnvelopeObserved",exceededEnvelope);report.put("oldRouteCleared",oldRouteCleared);
            report.put("newNavigationFromActualSurfaceObserved",freshNavigation);
            report.put("initialHealth",initialHealth);report.put("endHealth",mob.getHealth());
            report.put("knockbackStrength",knockback?KNOCKBACK_STRENGTH:null);
            report.put("knockbackResistance",mob.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
            report.put("knockbackDirection",knockbackDirection==null?null:position(knockbackDirection));
            report.put("trajectory",trajectory);report.put("success",success);report.put("reason",reason);
            try { ZombiesNavigationTestReport.write(knockback?"drop-change-native-knockback":"drop-change-early-platform",report); }
            finally { close(fixture,map); }
            helper.assertTrue(success,reason);helper.succeed();
        }
    }
    private static List<Double> position(Vec3 feet) { return List.of(feet.x,feet.y,feet.z); }
    private static void close(ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map) {
        fixture.close();
        try { map.resetGame(); }
        finally { FPSMCore.getInstance().unregisterMap(map);map.getMapTeams().retireCreatedScoreboardTeams(); }
    }
}
