package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationGameTests;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestTiming;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.TraversalEdge;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Observation-only physical acceptance shared by the separate N04 and N05 namespaces. */
final class ZombiesNavigationSupplementalDropFixture {
    static final int DEADLINE=600;
    enum Surface { STONE, SHAFT, SLAB }
    private ZombiesNavigationSupplementalDropFixture() { }

    static void context(GameTestHelper helper,int waveNumber,boolean residualHealth) {
        start(helper,Surface.STONE,waveNumber,residualHealth,"drop-context-wave-"+waveNumber
                +(residualHealth?"-two-health":"-full-health"));
    }

    static void surface(GameTestHelper helper,Surface surface) {
        start(helper,surface,1,true,surface==Surface.SHAFT?"drop-surface-one-block-shaft":"drop-surface-half-slab");
    }

    private static void start(GameTestHelper helper,Surface surface,int waveNumber,boolean residualHealth,String scenario) {
        ZombiesNavigationTestTiming.begin(helper, scenario, true);
        shell(helper);
        int platformY=surface==Surface.STONE?64:surface==Surface.SHAFT?32:8;
        if (surface==Surface.SHAFT) {
            // This hole is the only route to the lower floor, so a different platform edge cannot pass the test.
            for (int x=1;x<=18;x++) for (int z=1;z<=18;z++)
                if (x!=12 || z!=8) helper.setBlock(new BlockPos(x,platformY,z),Blocks.STONE);
            for (int y=1;y<platformY;y++) {
                helper.setBlock(new BlockPos(11,y,8),Blocks.STONE);
                helper.setBlock(new BlockPos(12,y,7),Blocks.STONE);
                helper.setBlock(new BlockPos(12,y,9),Blocks.STONE);
                if (y>=3) helper.setBlock(new BlockPos(13,y,8),Blocks.STONE);
            }
        } else {
            for (int x=3;x<=11;x++) for (int z=3;z<=11;z++)
                helper.setBlock(new BlockPos(x,platformY,z),Blocks.STONE);
        }
        if (surface==Surface.SLAB)
            for (int x=1;x<=18;x++) for (int z=1;z<=18;z++)
                helper.setBlock(new BlockPos(x,0,z),Blocks.STONE_SLAB);
        double landingY=surface==Surface.SLAB?.5:1;
        Vec3 target=new Vec3(surface==Surface.STONE?8.5:17.5,landingY,8.5);
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,target,new BlockPos(20,70,20));
        ZombiesMap map=new ZombiesMap(helper.getLevel(),fixture.roomId().mapName(),
                new AreaData(helper.absolutePos(BlockPos.ZERO),helper.absolutePos(new BlockPos(20,70,20))));
        FPSMCore.getInstance().registerMap(map.getGameType(),map);
        try {
            double healthMultiplier=waveNumber==20?2:1;
            ZombiesWaveDefinition wave=new Gson().fromJson("{\"wave\":"+waveNumber
                    +",\"healthMultiplier\":"+healthMultiplier+",\"speedMultiplier\":1,\"damageMultiplier\":1,"
                    +"\"maxAlive\":1,\"mobs\":[{\"entity\":\"minecraft:zombie\",\"count\":1}]}",ZombiesWaveDefinition.class);
            wave.attachSource(null,waveNumber,true);wave.applyDefaults(new ZombiesRulesConfig.Defaults());
            Mob mob=fixture.spawn("zombie",new BlockPos(8,platformY+1,8),wave);
            if (residualHealth) mob.setHealth(2);
            Run run=new Run(helper,fixture,map,mob,wave,surface,scenario,residualHealth);
            helper.onEachTick(run::tick);
        } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false); close(fixture,map);throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final ZombiesNavigationGameTests.Fixture fixture;
        final ZombiesMap map;
        final Mob mob;
        final ZombiesWaveDefinition wave;
        final Surface surface;
        final String scenario;
        final boolean residualHealth;
        final UUID originalId;
        final Vec3 start,target;
        final float initialHealth;
        final double expectedMaxHealth,expectedSpeed;
        final String expectedDifficulty=System.getProperty("codpattern.zombies.navigationDifficulty","easy");
        final LayeredNavigationRuntime runtime;
        final AABB shaft;
        final List<Map<String,Object>> trajectory=new ArrayList<>();
        Vec3 previous,firstLanding;
        TraversalEdge drop;
        long firstLandingTick=-1;
        Integer targetedNativeFallThreshold;
        double afterLandingDistance;
        boolean descending,managedFall,shaftInterior,finished;

        Run(GameTestHelper helper,ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map,Mob mob,
                ZombiesWaveDefinition wave,Surface surface,String scenario,boolean residualHealth) {
            this.helper=helper;this.fixture=fixture;this.map=map;this.mob=mob;this.wave=wave;
            this.surface=surface;this.scenario=scenario;this.residualHealth=residualHealth;
            originalId=mob.getUUID();start=mob.position();previous=start;target=fixture.player.position();
            initialHealth=mob.getHealth();runtime=LayeredNavigationRuntime.of(mob);
            expectedMaxHealth=DefaultAttributes.getSupplier(EntityType.ZOMBIE).getValue(Attributes.MAX_HEALTH)*wave.getHealthMultiplier();
            expectedSpeed=DefaultAttributes.getSupplier(EntityType.ZOMBIE).getValue(Attributes.MOVEMENT_SPEED)*wave.getSpeedMultiplier();
            shaft=new AABB(helper.absoluteVec(new Vec3(12,3,8)),helper.absoluteVec(new Vec3(13,32,9)));
            helper.assertTrue(runtime!=null,"supplemental DROP acceptance requires the layered room controller");
            helper.assertTrue(residualHealth?initialHealth==2:Math.abs(initialHealth-expectedMaxHealth)<1e-6,
                    "the real wave must start at exactly the configured full or residual health");
            checkContext();
        }

        void checkContext() {
            helper.assertTrue(helper.getLevel().getDifficulty().getSerializedName().equals(expectedDifficulty),
                    "the actual server difficulty must match the separately selected process: "+expectedDifficulty);
            helper.assertTrue(fixture.waveState().currentWave()==wave.getWave()
                            && fixture.waveState().targetWave()==wave.getWave(),
                    "the actual running wave and target wave must match the definition supplied to production spawnNext");
            helper.assertTrue(fixture.waveState().remainingBudget()==0 && fixture.waveState().activeZombies()==1
                            && fixture.waveState().activeZombieEntityIdsSnapshot().contains(originalId)
                            && fixture.activeRoomCount()==1 && fixture.ownedRoomCount()==1,
                    "exactly one original room-owned entity must consume the one-mob wave budget");
            helper.assertTrue(Math.abs(mob.getMaxHealth()-expectedMaxHealth)<1e-6
                            && Math.abs(mob.getAttributeValue(Attributes.MOVEMENT_SPEED)-expectedSpeed)<1e-6,
                    "production wave attributes must actually be applied without changing movement speed to help navigation");
        }

        void tick() {
            if (finished) return;
            try {
                checkContext();
                helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(originalId)==mob,
                        "one original living UUID must complete the physical descent and pursuit");
                helper.assertTrue(mob.getHealth()==initialHealth,"production fall protection must preserve the exact starting health");
                helper.assertTrue(fixture.player.position().distanceToSqr(target)<1e-8,"the target must remain stationary");
                helper.assertTrue(mob.position().distanceTo(previous)<12,"a discontinuous teleport cannot count as physical descent");
                if (mob.getTarget()==fixture.player) {
                    targetedNativeFallThreshold=mob.getMaxFallDistance();
                    helper.assertTrue(targetedNativeFallThreshold<start.y-target.y,
                            "the actual targeted native fall threshold must remain below the required managed drop");
                }
                TraversalEdge active=runtime.activeEdge(mob);
                if (active!=null && active.action()==TraversalEdge.Action.DROP) {
                    drop=active;
                    helper.assertTrue(Math.abs(active.to().feet().y-target.y)<1e-6,
                            "the planned DROP must preserve the actual first support height");
                    managedFall|=!mob.onGround() && runtime.describe(mob).contains("phase=FALL,");
                }
                descending|=!mob.onGround() && mob.getY()<start.y-2 && mob.getY()<previous.y-.01;
                if (surface==Surface.SHAFT && descending && mob.getY()>shaft.minY && mob.getBoundingBox().maxY<shaft.maxY) {
                    AABB body=mob.getBoundingBox();
                    helper.assertTrue(body.minX>=shaft.minX-1e-6 && body.maxX<=shaft.maxX+1e-6
                                    && body.minZ>=shaft.minZ-1e-6 && body.maxZ<=shaft.maxZ+1e-6,
                            "the whole falling body must physically fit inside the one-block shaft");
                    shaftInterior=true;
                }
                if (firstLanding!=null) {
                    Vec3 step=mob.position().subtract(previous);
                    afterLandingDistance+=Math.sqrt(step.x*step.x+step.z*step.z);
                    if (mob.onGround()) helper.assertTrue(Math.abs(mob.getY()-target.y)<1e-6,
                            "continued pursuit must stay on the true landing surface");
                } else if (descending && mob.onGround()) {
                    firstLanding=mob.position();firstLandingTick=helper.getTick();
                    helper.assertTrue(Math.abs(firstLanding.y-target.y)<1e-6,
                            "the first real onGround contact must be the expected exact support surface");
                    if (surface==Surface.SHAFT) helper.assertTrue(firstLanding.x>=shaft.minX && firstLanding.x<=shaft.maxX
                                    && firstLanding.z>=shaft.minZ && firstLanding.z<=shaft.maxZ,
                            "the initial contact must occur inside the shaft before using its bottom outlet");
                    sample();
                }
                previous=mob.position();
                if (helper.getTick()%5==0) sample();
                double reach=mob.getBbWidth()*2;
                boolean arrived=mob.onGround() && Math.abs(mob.getY()-target.y)<1e-6 && mob.getTarget()==fixture.player
                        && mob.distanceToSqr(fixture.player)<=reach*reach+fixture.player.getBbWidth()
                        && mob.getSensing().hasLineOfSight(fixture.player);
                if (arrived) {
                    helper.assertTrue(descending && firstLanding!=null && managedFall && drop!=null && targetedNativeFallThreshold!=null,
                            "arrival requires a naturally executed managed DROP and its first real landing");
                    helper.assertTrue(surface!=Surface.SHAFT || shaftInterior,"the entity must actually traverse the enclosed narrow shaft");
                    helper.assertTrue(surface==Surface.STONE || afterLandingDistance>=3,
                            "surface acceptance requires at least three real horizontal blocks of continued pursuit after landing");
                    finish(true,"the original entity naturally landed on the exact support surface and continued pursuit");
                } else if (helper.getTick()>=DEADLINE)
                    finish(false,"fixed 600-tick deadline exceeded; firstLanding="+firstLanding+", afterLandingDistance="
                            +afterLandingDistance+", "+runtime.describe(mob));
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }

        void sample() {
            trajectory.add(Map.of("tick",helper.getTick(),"feet",position(mob.position()),"onGround",mob.onGround(),
                    "health",mob.getHealth(),"execution",runtime.describe(mob)));
        }

        void finish(boolean success,String reason) {
            finished=true;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("uuid",originalId.toString());report.put("startFeet",position(start));report.put("endFeet",position(mob.position()));
            report.put("targetFeet",position(target));report.put("actualDescentHeight",start.y-target.y);
            report.put("elapsedTicks",helper.getTick());report.put("frozenDeadlineTicks",DEADLINE);
            report.put("surface",surface.name());report.put("requestedDifficulty",expectedDifficulty);
            report.put("actualDifficulty",helper.getLevel().getDifficulty().getSerializedName());
            report.put("configuredWave",wave.getWave());report.put("currentWave",fixture.waveState().currentWave());
            report.put("targetWave",fixture.waveState().targetWave());report.put("healthMultiplier",wave.getHealthMultiplier());
            report.put("speedMultiplier",wave.getSpeedMultiplier());report.put("expectedMaxHealth",expectedMaxHealth);
            report.put("effectiveMaxHealth",mob.getMaxHealth());report.put("expectedMovementSpeed",expectedSpeed);
            report.put("effectiveMovementSpeed",mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
            report.put("residualHealth",residualHealth);report.put("initialHealth",initialHealth);report.put("endHealth",mob.getHealth());
            report.put("targetedNativeFallThreshold",targetedNativeFallThreshold);report.put("managedFallObserved",managedFall);
            report.put("naturalDescentObserved",descending);report.put("shaftInteriorObserved",shaftInterior);
            report.put("firstLandingFeet",firstLanding==null?null:position(firstLanding));report.put("firstLandingTick",firstLandingTick);
            report.put("afterLandingHorizontalDistance",afterLandingDistance);report.put("runtime",fixture.spawnService.navigationRuntimeMetrics());
            report.put("trajectory",trajectory);report.put("success",success);report.put("reason",reason);
            try {
                close(fixture,map);
                helper.assertTrue(runtime.controllerCount()==0 && runtime.cacheStats().transientEntries()==0
                                && fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                        "the completed fixture must release its controllers, temporary geometry, ownership and active counts");
                report.put("cleanupVerified",true);
            } catch (RuntimeException | Error failure) {
                ZombiesNavigationTestTiming.finish(helper, false);
                report.put("success",false);report.put("cleanupVerified",false);
                report.put("reason",reason+"; cleanup failed: "+failure.getMessage());
                throw failure;
            } finally { ZombiesNavigationTestReport.write(scenario,report); }
            helper.assertTrue(success,reason);ZombiesNavigationTestTiming.succeed(helper);
        }
    }

    private static void shell(GameTestHelper helper) {
        for (int x=0;x<20;x++) for (int z=0;z<20;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);helper.setBlock(new BlockPos(x,69,z),Blocks.STONE);
            if (x==0 || x==19 || z==0 || z==19)
                for (int y=1;y<69;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
    }
    private static List<Double> position(Vec3 feet) { return List.of(feet.x,feet.y,feet.z); }
    private static void close(ZombiesNavigationGameTests.Fixture fixture,ZombiesMap map) {
        fixture.close();
        try { map.resetGame(); }
        finally { FPSMCore.getInstance().unregisterMap(map);map.getMapTeams().retireCreatedScoreboardTeams(); }
    }
}
