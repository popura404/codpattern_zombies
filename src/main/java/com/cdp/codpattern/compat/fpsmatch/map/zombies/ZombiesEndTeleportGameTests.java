package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import com.cdp.codpattern.app.match.editor.ModeMapEditorSchemas;
import com.cdp.codpattern.app.match.management.EndTeleportService;
import com.cdp.codpattern.app.match.management.MapManagementService;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.runtime.termination.RoomTerminationService;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesItemRegister;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraft;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployToolService;
import com.cdp.codpattern.compat.fpsmatch.data.CodMapPersistence;
import com.cdp.codpattern.compat.fpsmatch.data.zombies.ZombiesMapData;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.network.map.MapAdminData;
import com.cdp.codpattern.network.map.MapAdminRequestPacket;
import com.cdp.codpattern.network.map.MapAdminResponsePacket;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;

@GameTestHolder("codpattern_termination")
@PrefixGameTestTemplate(false)
public final class ZombiesEndTeleportGameTests {
    @GameTest(template = "empty", batch = "zombies_end_teleport", timeoutTicks = 300)
    public static void sharedEditorDefaultsCommandsAndPersistence(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var core = FPSMCore.getInstance();
        var storage = ServerMapStorage.get(server);
        var defaultsFile = storage.paths().defaults();
        byte[] oldDefaults = Files.exists(defaultsFile) ? Files.readAllBytes(defaultsFile) : null;
        var maps = new ArrayList<ZombiesMap>();
        var admin = player(helper, true);
        var denied = player(helper, false);
        var first = new SpawnPointData(helper.getLevel().dimension(), new BlockPos(12, 70, -4), 45, 0);
        var second = new SpawnPointData(helper.getLevel().dimension(), new BlockPos(20, 72, -8), 90, 0);
        String prefix = "z-endtp-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Files.deleteIfExists(defaultsFile);
            var unset = new ZombiesMap(helper.getLevel(), prefix + "-unset", new AreaData(BlockPos.ZERO, new BlockPos(4, 4, 4)));
            maps.add(unset);
            EndTeleportService.registerAndSaveNew(server, unset);
            helper.assertTrue(unset.matchEndTeleportPoint().isEmpty(), "missing defaults leave Zombies unset");
            helper.assertTrue(ModeMapEditorSchemas.supportsMatchEndTeleport("zombies"), "Zombies declares the shared editor schema");
            var room = RoomId.of("zombies", unset.getMapName());
            helper.assertTrue(EndTeleportService.read(admin, room).editable(), "shared GUI can edit an idle Zombies map");
            assertFailedSaveRestoresUnset(helper, admin, unset, first);
            EndTeleportService.save(admin, null, EndTeleportService.read(admin, null).revision(), first);
            helper.assertTrue(unset.matchEndTeleportPoint().isEmpty(), "changing defaults does not change existing maps");

            var deploy = new ItemStack(ZombiesItemRegister.ZOMBIES_DEPLOY_TOOL.get());
            admin.setItemInHand(InteractionHand.MAIN_HAND, deploy);
            var created = ZombiesDeployToolService.instance().createMap(admin, deploy, ZombiesDeployDraft.empty()
                    .withMapDraft(prefix + "-tool", BlockPos.ZERO, new BlockPos(4, 4, 4)));
            helper.assertTrue(created.success(), "Zombies deployment succeeds: " + created.code());
            var toolMap = (ZombiesMap) core.getMapByTypeWithName("zombies", prefix + "-tool").orElseThrow();
            maps.add(toolMap);
            helper.assertTrue(toolMap.matchEndTeleportPoint().orElseThrow().equals(first), "deployment copies saved global default");
            EndTeleportService.save(admin, null, EndTeleportService.read(admin, null).revision(), second);
            helper.assertTrue(toolMap.matchEndTeleportPoint().orElseThrow().equals(first), "later defaults do not overwrite maps");

            var source = server.createCommandSourceStack().withPermission(4);
            var createdWithLatestDefault = ZombiesDeployToolService.instance().createMap(admin, deploy, ZombiesDeployDraft.empty()
                    .withMapDraft(prefix + "-latest-default", BlockPos.ZERO, new BlockPos(4, 4, 4)));
            helper.assertTrue(createdWithLatestDefault.success(), "second Zombies deployment succeeds: " + createdWithLatestDefault.code());
            var latestDefaultMap = (ZombiesMap) core.getMapByTypeWithName("zombies", prefix + "-latest-default").orElseThrow();
            maps.add(latestDefaultMap);
            helper.assertTrue(latestDefaultMap.matchEndTeleportPoint().orElseThrow().equals(second), "deployment copies latest default");

            var before = EndTeleportService.read(admin, room);
            var request = MapAdminRequestPacket.teleport(MapAdminRequestPacket.Operation.SAVE_END_POINT,
                    UUID.randomUUID(), 1, room, before.revision(), MapAdminData.EndPoint.from(first));
            helper.assertTrue(request.process(denied).code() == MapAdminResponsePacket.Code.DENIED, "non-admin cannot edit Zombies end point");
            var result = request.process(admin);
            helper.assertTrue(result.code() == MapAdminResponsePacket.Code.OK, "GUI request saves Zombies end point: " + result.result());
            helper.assertTrue(request.process(admin).equals(result), "duplicate request is idempotent");
            helper.assertTrue(EndTeleportService.save(admin, room, before.revision(), second).code().equals("stale"), "stale GUI revision is rejected");
            helper.assertTrue(MapManagementService.detail(server, room).orElseThrow().endPoint().orElseThrow().equals(first), "detail and editor show the same point");
            var file = storage.paths().map("zombies", unset.getMapName()).resolve("map.json");
            var saved = ZombiesMapData.MapData.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(file))).result().orElseThrow();
            helper.assertTrue(saved.endtp().orElseThrow().equals(first), "Zombies codec reloads persisted end point");

            var termination = RoomTerminationService.get(server);
            termination.begin(room, java.util.List.of(), first);
            helper.assertTrue(!EndTeleportService.read(admin, room).editable(), "active room blocks end-point editing");
            helper.assertTrue(!EndTeleportService.save(admin, room, MapManagementService.revision(server, room), second).code().equals("saved"), "active room rejects save");
            termination.forceEnd(source, room, termination.generation(room));
            helper.assertTrue(server.getCommands().getDispatcher().execute("cdp map endtp show " + unset.getMapName(), source) == 1, "endtp show supports Zombies");
            server.getCommands().getDispatcher().execute("cdp map endtp set", source.withPosition(new net.minecraft.world.phys.Vec3(30, 74, -10)));
            helper.assertTrue(unset.matchEndTeleportPoint().orElseThrow().getPosition().equals(new BlockPos(30, 74, -10)), "batch command includes Zombies");
            helper.succeed();
        } finally {
            for (var map : maps) {
                core.unregisterMap(map);
                map.getMapTeams().retireCreatedScoreboardTeams();
                var folder = storage.paths().map("zombies", map.getMapName());
                if (Files.exists(folder)) try (var paths = Files.walk(folder)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
            if (oldDefaults == null) Files.deleteIfExists(defaultsFile);
            else Files.write(defaultsFile, oldDefaults);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertFailedSaveRestoresUnset(GameTestHelper helper, ServerPlayer admin, ZombiesMap map,
                                                     SpawnPointData point) throws Exception {
        var manager = FPSMCore.getInstance().getFPSMDataManager();
        var field = manager.getClass().getDeclaredField("registry");
        field.setAccessible(true);
        var registry = (java.util.Map<Class<?>, com.mojang.datafixers.util.Pair<String,
                com.phasetranscrystal.fpsmatch.core.data.save.ISavePort<?>>>) field.get(manager);
        var original = registry.get(ZombiesMapData.MapData.class);
        var file = ServerMapStorage.get(admin.server).paths().map("zombies", map.getMapName()).resolve("map.json");
        String before = Files.readString(file);
        var broken = new com.phasetranscrystal.fpsmatch.core.data.save.ISavePort<ZombiesMapData.MapData>() {
            @Override public com.mojang.serialization.Codec<ZombiesMapData.MapData> codec() { return ZombiesMapData.MapData.CODEC; }
            @Override public com.google.gson.JsonElement encodeToJson(ZombiesMapData.MapData data) {
                throw new IllegalStateException("Injected Zombies end-point save failure");
            }
        };
        try {
            registry.put(ZombiesMapData.MapData.class, com.mojang.datafixers.util.Pair.of(original.getFirst(), broken));
            var room = RoomId.of("zombies", map.getMapName());
            try {
                EndTeleportService.save(admin, room, MapManagementService.revision(admin.server, room), point);
                throw new AssertionError("injected save failure was not reported");
            } catch (IllegalStateException expected) { }
            helper.assertTrue(map.matchEndTeleportPoint().isEmpty() && Files.readString(file).equals(before), "failed save restores unset state and original file");
        } finally { registry.put(ZombiesMapData.MapData.class, original); }
    }

    private static ServerPlayer player(GameTestHelper helper, boolean admin) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "z-endtp-test")) {
            @Override public boolean hasPermissions(int permission) { return admin && permission <= 2; }
        };
    }
}
