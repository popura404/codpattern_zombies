package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.runtime.ZombiesWaveRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesMobSpawnService;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** A rejected EntityJoinLevelEvent happens before the entity has a level removal callback. */
@GameTestHolder("codpattern_navigation_lifecycle")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationLifecycleGameTests {
    private ZombiesNavigationLifecycleGameTests() { }

    @GameTest(setupTicks = 20, template="zombies_navigation",batch="navigation_lifecycle",timeoutTicks=40)
    public static void cancelledRoomEntityJoinReleasesControllerAndPreservesTheSameWaveBudget(GameTestHelper helper) {
        for (int x=1;x<=21;x++) for (int z=1;z<=21;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
            helper.setBlock(new BlockPos(x,10,z),Blocks.STONE);
        }
        var fixture=new ZombiesNavigationGameTests.Fixture(helper,new Vec3(18.5,1,18.5),new BlockPos(24,12,24));
        ZombiesWaveDefinition wave=new Gson().fromJson(
                "{\"wave\":1,\"maxAlive\":16,\"mobs\":[{\"entity\":\"minecraft:zombie\",\"count\":1}]}",ZombiesWaveDefinition.class);
        wave.attachSource(null,1,true);wave.applyDefaults(new ZombiesRulesConfig.Defaults());
        ZombiesWaveRuntimeState state=new ZombiesWaveRuntimeState();state.beginTargetWave(wave);
        ZombiesZombieSpawnData point=new ZombiesZombieSpawnData("cancelled-join-spawn",0,1,
                helper.getLevel().dimension(),helper.absolutePos(new BlockPos(3,1,3)),0,0);
        ZombiesMapObjects objects=new ZombiesMapObjects(List.of(),List.of(point),List.of(),List.of(),List.of(),
                List.of(),Optional.empty(),List.of(),List.of(),List.of(),List.of());
        Mob[] cancelled={null};
        Mob[] acceptedMob={null};
        LayeredNavigationRuntime[] joinedRuntime={null};
        int[] controllersAtJoin={-1};
        Consumer<EntityJoinLevelEvent> listener=event -> {
            if (cancelled[0]!=null || event.getLevel()!=helper.getLevel() || !(event.getEntity() instanceof Mob candidate)
                    || !ModeEntityOwnershipRegistry.instance().roomIdOf(candidate)
                            .map(room -> room.encode().equals(fixture.roomId().encode())).orElse(false)) return;
            cancelled[0]=candidate;joinedRuntime[0]=LayeredNavigationRuntime.of(candidate);
            controllersAtJoin[0]=joinedRuntime[0]==null?0:joinedRuntime[0].controllerCount();
            event.setCanceled(true);
        };
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
        try {
            ZombiesMobSpawnService.SpawnResult rejected;
            MinecraftForge.EVENT_BUS.addListener(listener);
            try {
                rejected=fixture.spawnService.spawnNext(fixture.roomId(),helper.getLevel(),objects,state,wave,Set.of(0));
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener);
            }
            helper.assertTrue(cancelled[0]!=null && controllersAtJoin[0]==1 && joinedRuntime[0]!=null,
                    "the scoped listener must cancel exactly one room mob after its controller is installed");
            helper.assertTrue(!rejected.spawned() && rejected.entity().isEmpty()
                            && rejected.failureReason().orElse(null)==ZombiesMobSpawnService.SpawnFailureReason.ENTITY_ADD_FAILED,
                    "the actual spawnNext call must report the cancelled world insertion");
            helper.assertTrue(state.remainingBudget()==1 && state.activeZombies()==0
                            && fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                    "a rejected insertion must retain the wave budget and release ownership and active counts");
            helper.assertTrue(cancelled[0].isRemoved() && helper.getLevel().getEntity(cancelled[0].getUUID())==null,
                    "the rejected entity cannot remain in the level");
            assertReleased(helper,joinedRuntime[0],"rejected join");
            report.put("cancelledUuid",cancelled[0].getUUID().toString());
            report.put("controllersAtJoin",controllersAtJoin[0]);report.put("failureReason",rejected.failureReason().orElseThrow().name());
            report.put("sameWaveBudgetAfterFailure",state.remainingBudget());
            report.put("controllersAfterFailure",joinedRuntime[0].controllerCount());
            report.put("planningAfterFailure",joinedRuntime[0].planningStats());report.put("cacheAfterFailure",joinedRuntime[0].cacheStats());

            // Retry the very same runtime state and definition; do not replace the unconsumed wave.
            ZombiesMobSpawnService.SpawnResult accepted=fixture.spawnService.spawnNext(
                    fixture.roomId(),helper.getLevel(),objects,state,wave,Set.of(0));
            helper.assertTrue(accepted.spawned() && accepted.entity().isPresent(),
                    "unregistering the listener must allow the same room and wave to spawn normally");
            Mob mob=accepted.entity().orElseThrow();
            acceptedMob[0]=mob;
            mob.getRandom().setSeed(ZombiesNavigationTestReport.SEED);
            helper.assertTrue(state.remainingBudget()==0 && state.activeZombies()==1
                            && fixture.activeRoomCount()==1 && fixture.ownedRoomCount()==1,
                    "only the accepted retry may consume the pending budget and register one active mob");
            helper.assertTrue(LayeredNavigationRuntime.of(mob)==joinedRuntime[0] && joinedRuntime[0].controllerCount()==1,
                    "the room runtime must remain reusable without retaining the rejected controller");
            report.put("acceptedUuid",mob.getUUID().toString());report.put("sameWaveBudgetAfterSuccess",state.remainingBudget());
            helper.runAtTickTime(helper.getTick()+2,() -> {
                try {
                    helper.assertTrue(mob.isAlive() && helper.getLevel().getEntity(mob.getUUID())==mob,
                            "the retry must remain a real live entity through ordinary server ticks");
                    NavigationScheduler stopped=NavigationScheduler.forServer(helper.getLevel().getServer());
                    NavigationScheduler.stopServer(helper.getLevel().getServer());
                    helper.assertTrue(mob.isAlive() && LayeredNavigationRuntime.of(mob)==null,
                            "the navigation server-stop entry point must detach a live entity's runtime before entity cleanup");
                    assertReleased(helper,joinedRuntime[0],"navigation server stop");
                    helper.assertTrue(stopped.jobCount()==0 && stopped.roomCount()==0 && stopped.registeredRoomCount()==0
                                    && stopped.timing().transientEntries()==0,
                            "the removed scheduler must release all jobs, rooms, registrations, and server transient references");
                    report.put("actualNavigationServerStopEntryInvoked",true);
                    report.put("gameTestProcessStopped",false);
                    report.put("controllersAfterServerStop",joinedRuntime[0].controllerCount());
                    report.put("planningAfterServerStop",joinedRuntime[0].planningStats());
                    report.put("serverTransientEntriesAfterStop",stopped.timing().transientEntries());
                    mob.discard();fixture.close();
                    helper.assertTrue(fixture.activeRoomCount()==0 && fixture.ownedRoomCount()==0,
                            "final fixture cleanup must release its room counters and ownership");
                    assertReleased(helper,joinedRuntime[0],"final room cleanup");
                    report.put("controllersAfterCleanup",joinedRuntime[0].controllerCount());
                    report.put("planningAfterCleanup",joinedRuntime[0].planningStats());report.put("cacheAfterCleanup",joinedRuntime[0].cacheStats());
                    report.put("success",true);ZombiesNavigationTestReport.write("cancelled-room-entity-join",report);helper.succeed();
                } catch (RuntimeException | Error failure) {
                    report.put("success",false);report.put("reason",failure.getMessage());
                    ZombiesNavigationTestReport.write("cancelled-room-entity-join",report);throw failure;
                } finally { mob.discard();fixture.close(); }
            });
        } catch (RuntimeException | Error failure) {
            if (cancelled[0]!=null) cancelled[0].discard();
            if (acceptedMob[0]!=null) acceptedMob[0].discard();
            fixture.close();report.put("success",false);report.put("reason",failure.getMessage());
            ZombiesNavigationTestReport.write("cancelled-room-entity-join",report);throw failure;
        }
    }

    private static void assertReleased(GameTestHelper helper,LayeredNavigationRuntime runtime,String phase) {
        helper.assertTrue(runtime.controllerCount()==0 && runtime.planningStats().activeRequests()==0
                        && runtime.planningStats().searchRecords()==0 && runtime.cacheStats().transientEntries()==0,
                phase+" must retain no navigation controllers, planning jobs, search records, or transient geometry");
    }
}
