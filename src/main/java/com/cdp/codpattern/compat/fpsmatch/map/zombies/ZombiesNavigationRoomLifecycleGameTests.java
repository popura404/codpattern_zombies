package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.gametest.MapDeletionGameTests;
import com.cdp.codpattern.app.match.management.MapDeletionCoordinator;
import com.cdp.codpattern.app.match.management.MapManagementService;
import com.cdp.codpattern.app.match.persistence.ModeMapPersistenceRegistry;
import com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry;
import com.cdp.codpattern.app.match.runtime.termination.ForceEndCoordinator;
import com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService;
import com.cdp.codpattern.app.zombies.gametest.ZombiesNavigationTestReport;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesZombieSpawnData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesTeamNames;
import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.google.gson.Gson;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Actual map management entry points, with naturally scheduled navigation before each teardown. */
@GameTestHolder("codpattern_navigation_room_lifecycle")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationRoomLifecycleGameTests {
    private ZombiesNavigationRoomLifecycleGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_room_lifecycle",timeoutTicks=410)
    public static void forceEndReleasesNavigationAndSameMapReopensWithANewLifecycle(GameTestHelper helper) throws Exception {
        start(helper,false,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_room_lifecycle",timeoutTicks=410)
    public static void deletionReleasesNavigationAndTheRecreatedRoomUsesANewLifecycle(GameTestHelper helper) throws Exception {
        start(helper,true,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_room_lifecycle",timeoutTicks=410)
    public static void lastWaveCompletesNaturallyAndReleasesNavigationBeforeTheNextGeneration(GameTestHelper helper) throws Exception {
        start(helper,false,true);
    }

    private static void start(GameTestHelper helper,boolean delete,boolean finalWave) throws Exception {
        for (int x=0;x<24;x++) for (int z=0;z<24;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);helper.setBlock(new BlockPos(x,11,z),Blocks.STONE);
            if (x==0 || x==23 || z==0 || z==23)
                for (int y=1;y<11;y++) helper.setBlock(new BlockPos(x,y,z),Blocks.STONE);
        }
        // An actually impassable wall makes native navigation yield to the installed room controller.
        // The fixture never requests a plan or issues movement to manufacture queue activity.
        for (int z=1;z<=22;z++) for (int y=1;y<=10;y++) helper.setBlock(new BlockPos(8,y,z),Blocks.STONE);
        Run run=new Run(helper,delete,finalWave);
        try { run.beginSpawn();helper.onEachTick(run::tick); }
        catch (RuntimeException | Error failure) { run.cleanup();throw failure; }
    }

    private static final class Run {
        final GameTestHelper helper;
        final boolean delete;
        final boolean finalWave;
        final String name="navigation-lifecycle-"+UUID.randomUUID().toString().substring(0,8);
        final RoomTerminationService termination;
        final ServerPlayer player;
        final ZombiesWaveDefinition wave;
        final List<LayeredNavigationRuntime> runtimes=new ArrayList<>();
        final List<Mob> mobs=new ArrayList<>();
        final List<Map<String,Object>> observations=new ArrayList<>();
        ZombiesMap map;
        ZombiesMap firstMap;
        UUID generation,firstGeneration,operation;
        Mob mob;
        LayeredNavigationRuntime runtime;
        int round;
        boolean ending,finished,victoryObserved,endingObserved;

        Run(GameTestHelper helper,boolean delete,boolean finalWave) throws Exception {
            this.helper=helper;this.delete=delete;this.finalWave=finalWave;
            termination=RoomTerminationService.get(helper.getLevel().getServer());
            player=MapDeletionGameTests.player(helper);MapDeletionGameTests.online(player,true);
            wave=new Gson().fromJson("{\"wave\":1,\"maxAlive\":1,\"mobs\":[{\"entity\":\"minecraft:zombie\",\"count\":1}]}",
                    ZombiesWaveDefinition.class);
            wave.attachSource(null,1,true);wave.applyDefaults(new ZombiesRulesConfig.Defaults());
            try { createMap();firstMap=map; }
            catch (RuntimeException | Error failure) { cleanup();throw failure; }
        }

        void createMap() {
            map=new ZombiesMap(helper.getLevel(),name,new AreaData(helper.absolutePos(BlockPos.ZERO),
                    helper.absolutePos(new BlockPos(24,12,24))));
            FPSMCore.getInstance().registerMap(map.getGameType(),map);
            if (delete) ModeMapPersistenceRegistry.find(map.getGameType()).orElseThrow()
                    .save(map,FPSMCore.getInstance().getFPSMDataManager());
        }

        void beginSpawn() {
            if (!map.hasSurvivor(player.getUUID())) map.join(ZombiesTeamNames.SURVIVORS,player);
            player.setGameMode(GameType.SURVIVAL);
            Vec3 target=helper.absoluteVec(new Vec3(10.5,1,8.5));
            player.moveTo(target.x,target.y,target.z,0,0);
            map.playerStateService().markAlive(player.getUUID());map.connectionStateService().markOnline(player.getUUID());
            helper.assertTrue(map.hasSurvivor(player.getUUID()) && map.playerStateService().canInteract(player.getUUID()),
                    "the real map survivor supplier must contain the online eligible player");
            generation=termination.begin(map.roomId(),List.of(player.getUUID()),null);
            if (round==0) firstGeneration=generation;
            else helper.assertTrue(!generation.equals(firstGeneration),"reopening must create a new actual room generation");
            map.runtimeState().transitionTo(ZombiesGamePhase.WAVE_ACTIVE);map.isStart=true;
            var state=map.runtimeState().waveState();state.prepareTargetWave(1);state.beginTargetWave(wave);
            if (finalWave) state.configureMaxWave(1);
            BlockPos spawn=helper.absolutePos(new BlockPos(5,1,8));
            helper.assertTrue(helper.getLevel().isPositionEntityTicking(spawn)
                            && helper.getLevel().areEntitiesLoaded(new net.minecraft.world.level.ChunkPos(spawn).toLong()),
                    "a real entity-ticking spawn chunk is required before map ownership registration");
            ZombiesZombieSpawnData point=new ZombiesZombieSpawnData("map-lifecycle-spawn",0,1,
                    helper.getLevel().dimension(),spawn,0,0);
            ZombiesMapObjects objects=new ZombiesMapObjects(List.of(),List.of(point),List.of(),List.of(),List.of(),
                    List.of(),Optional.empty(),List.of(),List.of(),List.of(),List.of());
            var result=map.mobSpawnService().spawnNext(map.roomId(),helper.getLevel(),objects,state,wave,Set.of(0));
            helper.assertTrue(result.spawned(),"the actual map-owned spawn service must accept its wave entity: "+result);
            mob=result.entity().orElseThrow();mobs.add(mob);mob.getRandom().setSeed(ZombiesNavigationTestReport.SEED);
            runtime=LayeredNavigationRuntime.of(mob);
            helper.assertTrue(runtime!=null && runtime.controllerCount()==1 && runtime.eligible(mob,player),
                    "the map's own real survivor/context must install an eligible layered controller");
            if (round>0) {
                helper.assertTrue(runtime!=runtimes.get(0) && !runtime.context().lifecycleId().equals(runtimes.get(0).context().lifecycleId()),
                        "the replacement runtime must use a new navigation lifecycle UUID");
                helper.assertTrue(delete?map!=firstMap:map==firstMap,
                        "force end reuses the same map, while deletion recreates the registered room");
            }
            runtimes.add(runtime);assertOneMobBudget();ending=false;operation=null;
        }

        void assertOneMobBudget() {
            helper.assertTrue(map.runtimeState().waveState().remainingBudget()==0
                            && map.runtimeState().waveState().activeZombies()==1
                            && map.mobSpawnService().roomActiveZombies(map.roomId())==1
                            && ModeEntityOwnershipRegistry.instance().entitiesInRoom(map.roomId()).size()==1,
                    "one real spawn must consume exactly one budget and produce exactly one active owned entity");
        }

        void tick() {
            if (finished) return;
            try {
                helper.assertTrue(helper.getTick()<400,"the fixed map lifecycle observation deadline cannot be extended");
                if (!ending) {
                    helper.assertTrue(mob.isAlive() && !mob.isRemoved() && helper.getLevel().getEntity(mob.getUUID())==mob,
                            "the original map-owned entity must remain present until the management operation");
                    assertOneMobBudget();
                    boolean work=runtime.planningStats().requests()>0
                            && (runtime.planningStats().searchRecords()>0 || runtime.cacheStats().geometryCells()>0
                            || runtime.cacheStats().nodes()>0);
                    if (!work) return;
                    Map<String,Object> before=new LinkedHashMap<>();
                    before.put("round",round);before.put("generation",generation.toString());
                    before.put("navigationLifecycle",runtime.context().lifecycleId().toString());
                    before.put("uuid",mob.getUUID().toString());before.put("planningBefore",runtime.planningStats());
                    before.put("cacheBefore",runtime.cacheStats());before.put("remainingBudgetBefore",map.runtimeState().waveState().remainingBudget());
                    observations.add(before);
                    var server=helper.getLevel().getServer();
                    if (finalWave && round==0) {
                        helper.assertTrue(mob.hurt(helper.getLevel().damageSources().playerAttack(player),Float.MAX_VALUE),
                                "the last original wave entity must die through a real damage/death event");
                        helper.assertTrue(!mob.isAlive() && map.runtimeState().waveState().activeZombies()==0
                                        && map.runtimeState().waveState().remainingBudget()==0,
                                "normal death handling must consume the last active entity without creating retry budget");
                        before.put("completionTrigger","actual player damage killed the last wave mob");
                    } else if (delete) {
                        var result=MapDeletionCoordinator.get(server).submit(server.createCommandSourceStack(),map.roomId(),
                                MapManagementService.revision(server,map.roomId()),generation,UUID.randomUUID(),1);
                        operation=result.id();before.put("deletionOperation",operation.toString());before.put("initialDeletionStage",result.stage().name());
                    } else {
                        var result=MapManagementService.forceEnd(server.createCommandSourceStack(),map.roomId(),generation);
                        helper.assertTrue(result.outcome()==ForceEndCoordinator.Outcome.COMPLETED
                                        || result.outcome()==ForceEndCoordinator.Outcome.PENDING
                                        || result.outcome()==ForceEndCoordinator.Outcome.IN_PROGRESS,
                                "the actual force-end entry must accept the live generation: "+result);
                        before.put("forceEndOperation",result.operationId().toString());before.put("initialForceEndOutcome",result.outcome().name());
                    }
                    ending=true;
                }
                var server=helper.getLevel().getServer();
                if (finalWave && round==0) {
                    victoryObserved|=map.runtimeState().phase()==ZombiesGamePhase.VICTORY;
                    endingObserved|=map.runtimeState().phase()==ZombiesGamePhase.ENDING;
                    helper.assertTrue(map.runtimeState().phase()!=ZombiesGamePhase.FAILED,
                            "normal last-wave completion cannot be replaced by the failure/force-end route");
                    if (map.runtimeState().phase()!=ZombiesGamePhase.WAITING) return;
                    helper.assertTrue(victoryObserved && endingObserved,
                            "real map ticks must traverse VICTORY and ENDING before cleanup to WAITING");
                } else if (delete) {
                    var result=MapDeletionCoordinator.get(server).find(server.createCommandSourceStack(),map.roomId(),operation);
                    helper.assertTrue(result.stage()!=MapDeletionCoordinator.Stage.FAILED
                                    && result.stage()!=MapDeletionCoordinator.Stage.RECONFIRM_REQUIRED,
                            "deletion must complete through its actual coordinator: "+result);
                    if (result.stage()!=MapDeletionCoordinator.Stage.DELETED) return;
                    helper.assertTrue(!FPSMCore.getInstance().isRegistered(map) && map.deletionMembers().isEmpty()
                                    && !Files.exists(ServerMapStorage.get(server).paths().map(map.getGameType(),name)),
                            "completed deletion must evict survivors, unregister the map and archive its definition");
                } else if (termination.status(map.roomId()).outcome()!=ForceEndCoordinator.Outcome.COMPLETED) return;
                assertReleased(runtime,mob);
                Map<String,Object> observation=observations.get(observations.size()-1);
                observation.put("controllersAfter",runtime.controllerCount());observation.put("planningAfter",runtime.planningStats());
                observation.put("cacheAfter",runtime.cacheStats());observation.put("remainingBudgetAfter",map.runtimeState().waveState().remainingBudget());
                observation.put("completionTick",helper.getTick());
                if (round++==0) { if (delete) createMap();beginSpawn();return; }
                assertReleased(runtimes.get(0),mobs.get(0));
                finish(true,"both actual room generations released all navigation and ownership state");
            } catch (RuntimeException | Error failure) {
                if (!finished) finish(false,failure.getMessage());
                throw failure;
            }
        }

        void assertReleased(LayeredNavigationRuntime ended,Mob entity) {
            var cache=ended.cacheStats();
            helper.assertTrue(ended.controllerCount()==0 && LayeredNavigationRuntime.of(entity)==null
                            && ended.planningStats().activeRequests()==0 && ended.planningStats().searchRecords()==0
                            && cache.tiles()==0 && cache.nodes()==0 && cache.edges()==0 && cache.failures()==0
                            && cache.geometryCells()==0 && cache.portalPaths()==0 && cache.pinnedTiles()==0
                            && cache.portalEdgeReferences()==0 && cache.transientEntries()==0,
                    "the saved old runtime must retain no controllers, planning jobs, graph evidence, pins, or temporary leases");
            helper.assertTrue(entity.isRemoved() && ModeEntityOwnershipRegistry.instance().entitiesInRoom(map.roomId()).isEmpty()
                            && map.mobSpawnService().roomActiveZombies(map.roomId())==0
                            && map.runtimeState().waveState().activeZombies()==0 && map.runtimeState().waveState().remainingBudget()==0
                            && !termination.hasLease(map.roomId()) && map.runtimeState().phase()==ZombiesGamePhase.WAITING && !map.isStart,
                    "management cleanup must reclaim the real entity, ownership, active count, consumed wave and room lease");
        }

        void finish(boolean success,String reason) {
            finished=true;
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("supplementalCoverage",true);report.put("preImplementationBaseline",false);
            report.put("entryPoint",finalWave?"real last-mob death, then natural map phase ticks":
                    delete?"MapDeletionCoordinator.submit":"MapManagementService.forceEnd");
            report.put("generationStartEntryPoint","RoomTerminationService.begin with actual map-owned spawnNext");
            report.put("fullStartupVoteFlowAsserted",false);report.put("elapsedTicks",helper.getTick());
            report.put("normalVictoryObserved",victoryObserved);report.put("normalEndingObserved",endingObserved);
            report.put("rounds",observations);report.put("success",success);report.put("reason",reason);
            try { cleanup(); }
            catch (RuntimeException | Error failure) { report.put("success",false);report.put("cleanupFailure",failure.getMessage());throw failure; }
            finally { ZombiesNavigationTestReport.write(finalWave?"room-navigation-last-wave-completion":
                    delete?"room-navigation-delete-recreate":"room-navigation-force-end-reopen",report); }
            helper.assertTrue(success,reason);helper.succeed();
        }

        void cleanup() {
            if (map!=null) {
                try {
                    if (FPSMCore.getInstance().isRegistered(map)) map.resetGame();
                } finally {
                    mobs.forEach(Mob::discard);runtimes.forEach(LayeredNavigationRuntime::close);
                    // Ending a match intentionally keeps the survivor roster for reopening.
                    // Only the fixture teardown leaves that roster before unregistering its map.
                    map.leave(player);
                    FPSMCore.getInstance().unregisterMap(map);map.getMapTeams().retireCreatedScoreboardTeams();
                }
            }
            try { MapDeletionGameTests.online(player,false); }
            catch (Exception failure) { throw new IllegalStateException("Cannot unregister the lifecycle fixture player",failure); }
            finally { player.discard(); }
        }
    }
}
