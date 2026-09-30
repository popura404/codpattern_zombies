package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import com.cdp.codpattern.app.match.BuiltInGameModes;
import com.cdp.codpattern.app.zombies.bootstrap.ZombiesItemRegister;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesAmmoBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesArmorStationData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesMysteryBoxData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesSodaMachineData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesUltimateMachineData;
import com.cdp.codpattern.app.zombies.map.object.ZombiesWeaponWallData;
import com.cdp.codpattern.app.zombies.service.ZombiesPurchasableBlockService;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.cdp.codpattern.common.block.ZombiesBoxInteractionBlock;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.mojang.serialization.JsonOps;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesBoxFacingPersistenceGameTests {
    private static final List<Direction> DIRECTIONS = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private ZombiesBoxFacingPersistenceGameTests() { }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 200, required = true)
    public static void capturedFacingSurvivesSaveReloadAndMissingBlockRepair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var service = ZombiesDeployToolService.instance();
        var player = FakePlayerFactory.getMinecraft(level);
        float originalYaw = player.getYRot();
        var stack = new ItemStack(ZombiesItemRegister.ZOMBIES_DEPLOY_TOOL.get());
        var core = FPSMCore.getInstance();
        String name = "box-facing-save-" + UUID.randomUUID().toString().substring(0, 8);
        List<BlockPos> positions = positions(helper, 24);
        Map<BlockPos, BlockState> original = clearTargets(level, positions);
        List<String> types = List.of(ZombiesDeployFieldSchema.WEAPON_WALL, ZombiesDeployFieldSchema.AMMO_BOX,
                ZombiesDeployFieldSchema.ARMOR_STATION, ZombiesDeployFieldSchema.SODA_MACHINE,
                ZombiesDeployFieldSchema.ULTIMATE_MACHINE, ZombiesDeployFieldSchema.MYSTERY_BOX);
        try {
            var registration = service.createMap(player, stack, ZombiesDeployDraft.empty().withMapDraft(name,
                    positions.get(0).offset(-2, -2, -2), positions.get(23).offset(2, 2, 2)));
            check(registration.success(), "create map for directional deployment: " + registration.code());
            ZombiesMap map = (ZombiesMap) core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
                String type = types.get(typeIndex);
                for (int directionIndex = 0; directionIndex < DIRECTIONS.size(); directionIndex++) {
                    Direction lookDirection = DIRECTIONS.get(directionIndex);
                    Direction facing = lookDirection.getOpposite();
                    player.setYRot(lookDirection.toYRot());
                    var fields = new HashMap<>(ZombiesDeployFieldSchema.defaultFields(type));
                    fields.put("objectId", type + "-" + facing.getName());
                    // Deliberately disagree with the player's view: the actual world click must overwrite this value.
                    fields.put("facing", lookDirection.getName());
                    var draft = new ZombiesDeployDraft(name, type, -1, ZombiesDeployFieldSchema.PROFILE_MVP3, fields);
                    var captured = service.captureWorldClick(player, stack, draft, positions.get(typeIndex * 4 + directionIndex), true);
                    check(captured.success(), "capture " + type + " facing " + facing + ": " + captured.code());
                    check(facing.getName().equals(ZombiesDeployTool.getDraft(stack).fields().get("facing")),
                            "world click must record the side facing the player for " + type);
                }
            }
            check(service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).success(), "save all 24 directional boxes");
            assertCapturedFacings(level, positions);
            ZombiesMapObjects saved = map.objects();
            check(ZombiesPurchasableBlockService.placements(saved).size() == 24, "all six kinds and four directions must persist");
            var codec = ZombiesMapObjects.CODEC.codec();
            var encoded = codec.encodeStart(JsonOps.INSTANCE, saved).result().orElseThrow();
            check(codec.parse(JsonOps.INSTANCE, encoded).result().orElseThrow().equals(saved),
                    "the full map-object JSON codec must preserve every facing");

            service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack));
            check(core.unregisterMap(map), "unregister the original map before disk reload");
            core.getFPSMDataManager().readData();
            ZombiesMap loaded = (ZombiesMap) core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            check(loaded != map && loaded.objects().equals(saved), "disk reload must restore every saved box direction");
            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            ZombiesPurchasableBlockService.ensureMissingObjects(loaded.objects(), level.getServer()::getLevel);
            assertCapturedFacings(level, positions);
            helper.succeed();
        } finally {
            service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack));
            core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).ifPresent(core::unregisterMap);
            player.setYRot(originalYaw);
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void facingOnlyUpdatesAtExistingPositionsCanBeRolledBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = positions(helper, 6);
        Map<BlockPos, BlockState> original = clearTargets(level, positions);
        try {
            ZombiesMapObjects before = objects(level, positions, Direction.SOUTH);
            ZombiesPurchasableBlockService.synchronize(ZombiesMapObjects.EMPTY, before, level.getServer()::getLevel);
            for (Direction facing : DIRECTIONS) {
                var changes = ZombiesPurchasableBlockService.synchronize(before, objects(level, positions, facing),
                        level.getServer()::getLevel);
                for (int index = 0; index < positions.size(); index++) {
                    assertFacing(level, positions.get(index), boxes().get(index), facing);
                }
                changes.rollback();
                for (int index = 0; index < positions.size(); index++) {
                    assertFacing(level, positions.get(index), boxes().get(index), Direction.SOUTH);
                }
            }
            helper.succeed();
        } finally {
            restore(level, original);
        }
    }

    private static ZombiesMapObjects objects(ServerLevel level, List<BlockPos> positions, Direction facing) {
        var dimension = level.dimension();
        return new ZombiesMapObjects(List.of(), List.of(), List.of(),
                List.of(new ZombiesWeaponWallData("wall", dimension, positions.get(0), Optional.empty(), facing)),
                List.of(new ZombiesAmmoBoxData("ammo", Map.of("1", 100), dimension, positions.get(1), Optional.empty(), facing)),
                List.of(new ZombiesArmorStationData("armor", 1, 100, 0.75D, dimension, positions.get(2), Optional.empty(), facing)),
                Optional.empty(),
                List.of(new ZombiesSodaMachineData("soda", "juggernog", 100, false, dimension, positions.get(3), Optional.empty(), facing)),
                List.of(new ZombiesUltimateMachineData("ultimate", 1, Map.of(), false, dimension, positions.get(4), Optional.empty(), facing)),
                List.of(new ZombiesMysteryBoxData("mystery", 100, List.of(), dimension, positions.get(5), Optional.empty(), facing)),
                List.of());
    }

    private static void assertCapturedFacings(ServerLevel level, List<BlockPos> positions) {
        for (int typeIndex = 0; typeIndex < boxes().size(); typeIndex++) {
            for (int directionIndex = 0; directionIndex < DIRECTIONS.size(); directionIndex++) {
                assertFacing(level, positions.get(typeIndex * 4 + directionIndex), boxes().get(typeIndex),
                        DIRECTIONS.get(directionIndex).getOpposite());
            }
        }
    }

    private static void assertFacing(ServerLevel level, BlockPos pos, Block expected, Direction facing) {
        BlockState actual = level.getBlockState(pos);
        check(actual.is(expected) && actual.getValue(ZombiesBoxInteractionBlock.FACING) == facing,
                "expected " + expected + " facing " + facing + " at " + pos + ", got " + actual);
    }

    private static List<Block> boxes() {
        return List.of(CodPatternBlockRegister.ZOMBIES_WEAPON_WALL_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get(), CodPatternBlockRegister.ZOMBIES_ARMOR_STATION_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_SODA_MACHINE_BOX.get(), CodPatternBlockRegister.ZOMBIES_ULTIMATE_MACHINE_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get());
    }

    private static List<BlockPos> positions(GameTestHelper helper, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> helper.absolutePos(new BlockPos(0, 3 + index * 2, 0))).toList();
    }

    private static Map<BlockPos, BlockState> clearTargets(ServerLevel level, List<BlockPos> positions) {
        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            original.put(pos, level.getBlockState(pos));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        return original;
    }

    private static void restore(ServerLevel level, Map<BlockPos, BlockState> original) {
        original.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
