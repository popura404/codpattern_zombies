package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.runtime.termination.ForceEndCoordinator;
import com.cdp.codpattern.app.match.management.MapManagementService;
import com.cdp.codpattern.app.match.management.MapMutationService;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.persistence.ModeMapPersistenceRegistry;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.config.zombies.ZombiesConfigPaths;
import com.cdp.codpattern.config.zombies.ZombiesRoomConfig;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import net.minecraft.world.level.Level;
import java.nio.file.Files;
import com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService;
import com.cdp.codpattern.app.zombies.map.object.ZombiesBarrierData;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierBlockRuntimeService;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;

@GameTestHolder("codpattern_termination")
@PrefixGameTestTemplate(false)
public final class ZombiesForceEndGameTests {
    private ZombiesForceEndGameTests() { }

    @GameTest(template = "empty", batch = "zombies_termination", timeoutTicks = 200)
    public static void allRoomPhasesCanBeForceEnded(GameTestHelper helper) {
        var level = helper.getLevel();
        var service = RoomTerminationService.get(level.getServer());
        for (var phase : ZombiesGamePhase.values()) {
            var map = new ZombiesMap(level, "force-"+phase.key()+"-"+UUID.randomUUID().toString().substring(0,8),
                    new AreaData(BlockPos.ZERO,new BlockPos(8,8,8)));
            FPSMCore.getInstance().registerMap(map.getGameType(), map);
            var room = map.roomId();
            try {
                service.begin(room,List.of(),null);
                map.runtimeState().transitionTo(phase);
                map.isStart = phase.isRoundRunning();
                var mob = EntityType.ZOMBIE.create(level);
                mob.moveTo(helper.absolutePos(BlockPos.ZERO).getCenter());
                com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry.instance().register(room,mob);
                level.addFreshEntity(mob);
                var generation=service.generation(room);
                var result=MapManagementService.forceEnd(level.getServer().createCommandSourceStack(),room,generation);
                helper.assertTrue(result.outcome()==ForceEndCoordinator.Outcome.COMPLETED, phase+" failed: "+result.failures());
                helper.assertTrue(map.runtimeState().phase()==ZombiesGamePhase.WAITING && !map.isStart, phase+" must reset mode state");
                helper.assertTrue(mob.isRemoved() && !service.hasLease(room), phase+" must reclaim entities and occupancy");
                var duplicate=service.forceEnd(level.getServer().createCommandSourceStack(),room,generation);
                helper.assertTrue(result.operationId().equals(duplicate.operationId()), "duplicate must preserve operation identity");
                service.begin(room,List.of(),null);
                helper.assertTrue(!generation.equals(service.generation(room)), "clean room must allow another generation");
                service.forceEnd(level.getServer().createCommandSourceStack(),room,service.generation(room));
            } finally { FPSMCore.getInstance().unregisterMap(map); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "zombies_management", timeoutTicks = 200)
    public static void managementRenameLoadsPreservedRules(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        String name = "manage-z-" + UUID.randomUUID().toString().substring(0, 8);
        RoomId original = RoomId.of("zombies", name);
        RoomId target = RoomId.of("zombies", name + "-renamed");
        var rules = ZombiesRoomConfig.defaults();
        rules.getRoom().setStartVoteRequiredPercent(73);
        var roomFile = ZombiesConfigPaths.zombiesMapRoom(server, name);
        Files.createDirectories(roomFile.getParent());
        Files.writeString(roomFile, new com.google.gson.Gson().toJson(rules));
        var map = new ZombiesMap(helper.getLevel(), name, new AreaData(BlockPos.ZERO, new BlockPos(8, 8, 8)));
        var endpoint = new SpawnPointData(Level.NETHER, new BlockPos(8, 72, -4), 60F, 7F);
        map.setMatchEndTeleportPoint(endpoint);
        var core = FPSMCore.getInstance();
        core.registerMap("zombies", map);
        var provider = ModeMapPersistenceRegistry.find("zombies").orElseThrow();
        provider.save(map, core.getFPSMDataManager());
        try {
            helper.assertTrue(MapManagementService.list(server).stream().anyMatch(row -> row.roomId().equals(original)),
                    "installed addon map is discovered through its runtime provider");
            var detail = MapManagementService.detail(server, original).orElseThrow();
            helper.assertTrue(detail.endPointSupported() && detail.endPoint().orElseThrow().equals(endpoint),
                    "Zombies exposes configured end point through the shared detail contract");
            var renamed = MapMutationService.rename(server, original, detail.revision(), target.mapName());
            helper.assertTrue(renamed.outcome() == MapMutationService.Outcome.RENAMED, "Zombies rename succeeds: " + renamed);
            var replacement = (ZombiesMap) core.getMapByTypeWithName("zombies", target.mapName()).orElseThrow();
            helper.assertTrue(replacement.serverConfig().getRoom().getRoom().getStartVoteRequiredPercent() == 73,
                    "replacement constructor loads non-default rules at the final destination");
            helper.assertTrue(replacement.matchEndTeleportPoint().orElseThrow().equals(endpoint),
                    "Zombies definition preserves configured end point");
            var deleted = MapMutationService.delete(server, target, MapManagementService.revision(server, target));
            helper.assertTrue(deleted.outcome() == MapMutationService.Outcome.DELETED, "Zombies deletion succeeds: " + deleted);
            helper.assertTrue(!Files.exists(ServerMapStorage.get(server).paths().map("zombies", target.mapName())),
                    "Zombies deletion archives its full definition and rules directory");
            helper.succeed();
        } finally {
            core.getMapByTypeWithName("zombies", target.mapName()).ifPresent(value -> {
                core.unregisterMap(value);
                value.getMapTeams().retireCreatedScoreboardTeams();
            });
            core.unregisterMap(map);
            map.getMapTeams().retireCreatedScoreboardTeams();
        }
    }

    @GameTest(template = "empty", batch = "zombies_barrier_recovery", timeoutTicks = 100)
    public static void barrierFailureRetainsDurableFootprint(GameTestHelper helper) {
        var level=helper.getLevel();
        var room=com.cdp.codpattern.app.match.model.RoomId.of("zombies","barrier-recovery-"+UUID.randomUUID());
        var pos=helper.absolutePos(new BlockPos(0,2,0));
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var barrier=new ZombiesBarrierData("recovery-wall",1,0,true,level.dimension(),pos,pos.above(),pos);
        var original=ZombiesBarrierBlockRuntimeService.instance();
        original.placeActiveBarriers(room,List.of(barrier),ignored->false,dimension->level);
        try {
            try {
                original.clearRoom(room,dimension->null);
                throw new AssertionError("Missing dimension cannot confirm barrier deletion");
            } catch (IllegalStateException expected) { }
            helper.assertTrue(original.cellAt(room,level,pos).isPresent(),"failed cleanup must retain the cell");
            var restored=new ZombiesBarrierBlockRuntimeService();
            restored.clearRoom(room,dimension->level);
            helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir(),
                    "a recreated service must recover the saved footprint and delete the barrier");
            helper.assertTrue(restored.cellAt(room,level,pos).isEmpty(),"only confirmed removal clears tracking");
            helper.succeed();
        } finally { original.clearRoom(room,dimension->level); }
    }
}
