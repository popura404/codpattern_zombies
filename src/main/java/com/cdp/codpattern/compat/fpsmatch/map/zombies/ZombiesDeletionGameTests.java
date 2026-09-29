package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.management.MapDeletionCoordinator;
import com.cdp.codpattern.app.match.management.MapManagementService;
import com.cdp.codpattern.app.match.persistence.ModeMapPersistenceRegistry;
import com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService;
import com.cdp.codpattern.app.match.gametest.MapDeletionGameTests;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;

@GameTestHolder("codpattern_termination")
@PrefixGameTestTemplate(false)
public final class ZombiesDeletionGameTests {
    @GameTest(template = "empty", batch = "zombies_deletion", timeoutTicks = 200)
    public static void allPhasesDeleteAfterRecoveryAndEvictResidualSpectators(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var termination = RoomTerminationService.get(server);
        var service = MapDeletionCoordinator.get(server);
        for (var phase : ZombiesGamePhase.values()) {
            var map = new ZombiesMap(helper.getLevel(), "delete-" + phase.key() + "-" + UUID.randomUUID().toString().substring(0,8),
                    new AreaData(BlockPos.ZERO, new BlockPos(8,8,8)));
            FPSMCore.getInstance().registerMap(map.getGameType(), map);
            ModeMapPersistenceRegistry.find(map.getGameType()).orElseThrow().save(map, FPSMCore.getInstance().getFPSMDataManager());
            var player = MapDeletionGameTests.player(helper); MapDeletionGameTests.online(player, true);
            var survivor = MapDeletionGameTests.player(helper); MapDeletionGameTests.online(survivor, true);
            try {
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                termination.capture(map.roomId(), player);
                survivor.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                termination.capture(map.roomId(), survivor);
                map.getMapTeams().getTeams().get(0).join(survivor);
                // Zombies normally rejects spectator admission; detect and remove underlying residue too.
                map.getMapTeams().getSpectatorTeam().join(player);
                termination.begin(map.roomId(), List.of(player.getUUID(), survivor.getUUID()), null);
                map.runtimeState().transitionTo(phase); map.isStart = phase.isRoundRunning();
                var result = service.submit(server.createCommandSourceStack(), map.roomId(), MapManagementService.revision(server, map.roomId()),
                        termination.generation(map.roomId()), UUID.randomUUID(), 1);
                helper.assertTrue(result.stage() == MapDeletionCoordinator.Stage.DELETED, phase + " deletion: " + result);
                helper.assertTrue(map.deletionMembers().isEmpty() && !FPSMCore.getInstance().isRegistered(map), phase + " roster and registration cleared");
                helper.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND)
                        && survivor.getInventory().getItem(0).is(Items.DIAMOND), phase + " eviction does not clear recovered inventory");
            } finally { MapDeletionGameTests.online(player, false); MapDeletionGameTests.online(survivor, false); FPSMCore.getInstance().unregisterMap(map); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "zombies_deletion", timeoutTicks = 200)
    public static void unresolvedEntityRetainsMapAndBlocksDeploymentUntilCleanup(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var map = new ZombiesMap(helper.getLevel(), "delete-resource-" + UUID.randomUUID().toString().substring(0,8),
                new AreaData(BlockPos.ZERO, new BlockPos(8,8,8)));
        FPSMCore.getInstance().registerMap(map.getGameType(), map);
        ModeMapPersistenceRegistry.find(map.getGameType()).orElseThrow().save(map, FPSMCore.getInstance().getFPSMDataManager());
        var service = MapDeletionCoordinator.get(server); var termination = RoomTerminationService.get(server);
        try {
            termination.begin(map.roomId(), List.of(), null);
            var entity = EntityType.ZOMBIE.create(helper.getLevel());
            com.cdp.codpattern.app.match.runtime.ModeEntityOwnershipRegistry.instance().register(map.roomId(), entity);
            var pending = service.submit(server.createCommandSourceStack(), map.roomId(), MapManagementService.revision(server, map.roomId()),
                    termination.generation(map.roomId()), UUID.randomUUID(), 1);
            helper.assertTrue(pending.stage() == MapDeletionCoordinator.Stage.ENDING && FPSMCore.getInstance().isRegistered(map), "unloaded entity retains map");
            helper.assertTrue(!termination.canJoin(map.roomId(), UUID.randomUUID()), "deletion blocks addon admission");
            try { map.setMatchEndTeleportPoint(null); throw new AssertionError("editing during deletion accepted"); }
            catch (IllegalStateException expected) { }
            termination.reclaimLoadedEntity(entity);
            termination.forceEnd(server.createCommandSourceStack(), map.roomId(), termination.generation(map.roomId()));
            for (int n=0; n<20; n++) service.tick();
            helper.assertTrue(service.find(server.createCommandSourceStack(), map.roomId(), pending.id()).stage()
                    == MapDeletionCoordinator.Stage.DELETED, "confirmed resource cleanup permits deletion");
            helper.succeed();
        } finally { FPSMCore.getInstance().unregisterMap(map); }
    }
}
