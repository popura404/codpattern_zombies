package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.compat.fpsmatch.data.CodMapPersistence;
import com.cdp.codpattern.compat.fpsmatch.data.zombies.ZombiesMapData;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.config.zombies.ZombiesConfigPaths;
import com.cdp.codpattern.config.zombies.ZombiesConfigRepository;
import com.phasetranscrystal.fpsmatch.common.service.MapCreationService;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.save.FPSMDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.UUID;

/** Real Forge registry, addon construction, disk persistence and reconstruction. */
@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MapStorageGameTests {
    @GameTest(template = "empty", batch = "map_storage", timeoutTicks = 200, required = true)
    public static void mapAndAddonRulesRoundTrip(GameTestHelper helper) {
        String name = "storage-地图-" + UUID.randomUUID().toString().substring(0, 8);
        var core = FPSMCore.getInstance();
        var server = helper.getLevel().getServer();
        var storage = ServerMapStorage.get(server);
        try {
            var result = MapCreationService.instance().createMap(FakePlayerFactory.getMinecraft(helper.getLevel()),
                    BuiltInGameModes.ZOMBIES, name, new BlockPos(-16, 0, -16), new BlockPos(16, 256, 16));
            check(result.success(), "addon map creation failed: " + result.code());
            ZombiesMap map = (ZombiesMap) core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            var file = storage.paths().map("zombies", name).resolve("map.json");
            check(Files.isRegularFile(file), "map JSON must be inside save-local maps/zombies");
            var room = ZombiesConfigPaths.zombiesMapRoom(server, name);
            check(room.startsWith(file.getParent().resolve("rules")), "room rules share map directory");
            check(Files.isRegularFile(room), "addon startup generated room rules");
            var roomJson = com.google.gson.JsonParser.parseString(Files.readString(room)).getAsJsonObject();
            roomJson.getAsJsonObject("room").addProperty("intermissionSeconds", 17);
            String rules = roomJson.toString();
            Files.writeString(room, rules);
            var loadedRules = ZombiesConfigRepository.loadResult(server, name);
            check(loadedRules.config().room().getRoom().getIntermissionSeconds() == 17, "custom room rule loaded");
            check(Files.readString(room).equals(rules), "reloading must preserve custom rule content");
            CodMapPersistence.saveMap(map);
            check(core.unregisterMap(map), "unregister for disk reload");
            core.getFPSMDataManager().readData();
            var reloaded = core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            check(reloaded != map, "readData must reconstruct map from new path");
            check(reloaded.getMapName().equals(name), "logical Chinese map name preserved");
            var status = core.getFPSMDataManager().deleteData(ZombiesMapData.MapData.class, name);
            check(status == FPSMDataManager.DeleteStatus.DELETED, "map deletion must archive successfully");
            check(!Files.exists(file.getParent()), "map and its rules must be archived together");
            core.unregisterMap(reloaded);
            core.getFPSMDataManager().readData();
            check(core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).isEmpty(), "archived map must not reload");
            helper.succeed();
        } catch (Throwable e) {
            e.printStackTrace(); helper.fail("Map storage integration: " + e);
        } finally {
            core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).ifPresent(core::unregisterMap);
        }
    }
    @GameTest(template = "empty", batch = "map_storage", timeoutTicks = 200, required = true)
    public static void builtInMapsRoundTrip(GameTestHelper helper) {
        var core = FPSMCore.getInstance();
        var server = helper.getLevel().getServer();
        var storage = ServerMapStorage.get(server);
        String name = "builtin-storage-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            for (String mode : java.util.List.of(BuiltInGameModes.FRONTLINE, BuiltInGameModes.TEAM_DEATHMATCH)) {
                var created = MapCreationService.instance().createMap(FakePlayerFactory.getMinecraft(helper.getLevel()),
                        mode, name, new BlockPos(-16, 0, -16), new BlockPos(16, 256, 16));
                check(created.success(), mode + " creation failed: " + created.code());
                var map = created.map();
                var file = storage.paths().map("builtin/" + mode, name).resolve("map.json");
                check(Files.isRegularFile(file), mode + " map saved under builtin");
                check(core.unregisterMap(map), mode + " unregister before reload");
                core.getFPSMDataManager().readData();
                var reloaded = core.getMapByTypeWithName(mode, name).orElseThrow();
                check(reloaded != map, mode + " reconstructed from disk");
                Class<?> dataClass = mode.equals(BuiltInGameModes.FRONTLINE)
                        ? com.cdp.codpattern.compat.fpsmatch.data.CodTdmMapData.MapData.class
                        : com.cdp.codpattern.compat.fpsmatch.data.CodTacticalTdmMapData.MapData.class;
                check(core.getFPSMDataManager().deleteData(dataClass, name) == FPSMDataManager.DeleteStatus.DELETED,
                        mode + " delete archives map");
                core.unregisterMap(reloaded);
                core.getFPSMDataManager().readData();
                check(core.getMapByTypeWithName(mode, name).isEmpty(), mode + " deleted map stays absent");
            }
            helper.succeed();
        } catch (Throwable e) {
            e.printStackTrace(); helper.fail("Built-in map storage integration: " + e);
        } finally {
            for (String mode : java.util.List.of(BuiltInGameModes.FRONTLINE, BuiltInGameModes.TEAM_DEATHMATCH)) {
                core.getMapByTypeWithName(mode, name).ifPresent(core::unregisterMap);
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
