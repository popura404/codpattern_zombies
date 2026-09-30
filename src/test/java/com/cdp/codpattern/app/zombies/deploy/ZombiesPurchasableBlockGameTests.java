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
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesPurchasableBlockGameTests {
    private ZombiesPurchasableBlockGameTests() { }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void boxesKeepEveryAdjacentSolidFaceVisible(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockState stone = Blocks.STONE.defaultBlockState();
        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        original.put(center, level.getBlockState(center));
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = center.relative(direction);
            original.put(adjacent, level.getBlockState(adjacent));
            level.setBlock(adjacent, stone, Block.UPDATE_ALL);
        }
        try {
            level.setBlock(center, stone, Block.UPDATE_ALL);
            check(!Block.shouldRenderFace(stone, level, center.above(), Direction.DOWN, center),
                    "control: a full stone cube must hide the adjacent stone face");
            for (Block box : boxes()) {
                BlockState state = box.defaultBlockState();
                level.setBlock(center, state, Block.UPDATE_ALL);
                for (Direction direction : Direction.values()) {
                    check(Block.shouldRenderFace(stone, level, center.relative(direction), direction.getOpposite(), center),
                            box + " must not hide the adjacent " + direction + " face");
                }
                check(!state.isSuffocating(level, center), box + " must not act as a suffocating full cube");
                check(!state.isViewBlocking(level, center), box + " must not block the entire view");
            }
            helper.succeed();
        } finally {
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void synchronizationPlacesAllBoxesAndRollbackRestoresWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = positions(helper, 3, 6);
        Map<BlockPos, BlockState> original = prepare(level, positions);
        BlockPos secondWallPos = helper.absolutePos(new BlockPos(0, 23, 0));
        original.putAll(prepare(level, List.of(secondWallPos)));
        try {
            level.setBlock(positions.get(1), Blocks.GRASS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(positions.get(2), boxes().get(2).defaultBlockState(), Block.UPDATE_ALL);
            Map<BlockPos, BlockState> before = snapshot(level, positions);
            before.put(secondWallPos, level.getBlockState(secondWallPos));
            ZombiesMapObjects base = objects(level, positions);
            var walls = new ArrayList<>(base.weaponWalls());
            walls.add(new ZombiesWeaponWallData("second-wall", level.dimension(), secondWallPos, Optional.empty()));
            ZombiesMapObjects objects = new ZombiesMapObjects(base.initialSpawns(), base.zombieSpawns(), base.barriers(),
                    walls, base.ammoBoxes(), base.armorStations(), base.powerSwitch(), base.sodaMachines(),
                    base.ultimateMachines(), base.mysteryBoxes(), base.windows());
            var changes = ZombiesPurchasableBlockService.synchronize(ZombiesMapObjects.EMPTY, objects,
                    level.getServer()::getLevel);
            assertBoxes(level, positions);
            check(level.getBlockState(secondWallPos).is(boxes().get(0)), "multiple boxes of the same type must be placed");

            var repeated = ZombiesPurchasableBlockService.synchronize(objects, objects, level.getServer()::getLevel);
            repeated.rollback();
            assertBoxes(level, positions);
            changes.rollback();
            assertStates(level, before, "rollback must restore air, replaceable grass, and existing matching blocks");
            changes.rollback();
            assertStates(level, before, "repeated rollback must have no further effect");
            helper.succeed();
        } finally {
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void missingBoxesAreRepairedWithoutOverwritingMapBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = positions(helper, 3, 6);
        Map<BlockPos, BlockState> original = prepare(level, positions);
        try {
            ZombiesMapObjects objects = objects(level, positions);
            ZombiesPurchasableBlockService.synchronize(ZombiesMapObjects.EMPTY, objects, level.getServer()::getLevel);
            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(positions.get(1), Blocks.GRASS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(positions.get(4), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            ZombiesPurchasableBlockService.ensureMissingObjects(objects, level.getServer()::getLevel);
            for (int index = 0; index < positions.size(); index++) {
                Block expected = index == 4 ? Blocks.STONE : boxes().get(index);
                check(level.getBlockState(positions.get(index)).is(expected),
                        "repair must restore missing boxes while preserving the solid conflict at index " + index);
            }
            level.setBlock(positions.get(4), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            ZombiesPurchasableBlockService.ensureMissingObjects(objects, level.getServer()::getLevel);
            assertBoxes(level, positions);
            helper.succeed();
        } finally {
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void conflictingMoveKeepsAllPreviousObjectsAndDestinationBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> oldPositions = positions(helper, 3, 6);
        List<BlockPos> newPositions = positions(helper, 23, 6);
        Map<BlockPos, BlockState> original = prepare(level, oldPositions);
        original.putAll(prepare(level, newPositions));
        try {
            ZombiesMapObjects previous = objects(level, oldPositions);
            ZombiesPurchasableBlockService.synchronize(ZombiesMapObjects.EMPTY, previous, level.getServer()::getLevel);
            level.setBlock(newPositions.get(1), Blocks.GRASS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(newPositions.get(5), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            Map<BlockPos, BlockState> before = snapshot(level, oldPositions);
            before.putAll(snapshot(level, newPositions));
            boolean rejected = false;
            try {
                ZombiesPurchasableBlockService.synchronize(previous, objects(level, newPositions), level.getServer()::getLevel);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            check(rejected, "moving six objects into a solid conflict must fail");
            assertStates(level, before, "a failed multi-object move must preserve every old and destination state");
            helper.succeed();
        } finally {
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void neighborConflictRollsBackEveryAlreadyPlacedBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = positions(helper, 3, 6);
        Map<BlockPos, BlockState> original = prepare(level, positions);
        AtomicBoolean changedTarget = new AtomicBoolean();
        Consumer<BlockEvent.NeighborNotifyEvent> conflict = event -> {
            if (event.getLevel() == level && event.getPos().equals(positions.get(1))
                    && event.getState().is(boxes().get(1)) && changedTarget.compareAndSet(false, true)) {
                level.setBlock(positions.get(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(conflict);
        try {
            level.setBlock(positions.get(1), Blocks.GRASS.defaultBlockState(), Block.UPDATE_ALL);
            Map<BlockPos, BlockState> before = snapshot(level, positions);
            boolean rejected = false;
            try {
                ZombiesPurchasableBlockService.synchronize(ZombiesMapObjects.EMPTY, objects(level, positions),
                        level.getServer()::getLevel);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            check(changedTarget.get(), "fixture must create a conflict after both the wall and ammo box were placed");
            check(rejected, "a target changed by neighbor updates must abort placement");
            before.put(positions.get(2), Blocks.STONE.defaultBlockState());
            assertStates(level, before, "failure must undo every prior placement and preserve the external solid conflict");
            helper.succeed();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(conflict);
            restore(level, original);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 100, required = true)
    public static void draftSaveCreatesDeletesAndRepairsThreeBoxKinds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> positions = positions(helper, 3, 3);
        Map<BlockPos, BlockState> original = prepare(level, positions);
        var service = ZombiesDeployToolService.instance();
        var player = FakePlayerFactory.getMinecraft(level);
        var stack = new ItemStack(ZombiesItemRegister.ZOMBIES_DEPLOY_TOOL.get());
        var core = FPSMCore.getInstance();
        String name = "box-deploy-" + UUID.randomUUID().toString().substring(0, 8);
        ZombiesMap map = null;
        try {
            BlockPos origin = positions.get(0);
            var registration = service.createMap(player, stack, ZombiesDeployDraft.empty().withMapDraft(name,
                    origin.offset(-2, -2, -2), origin.offset(2, 12, 2)));
            check(registration.success(), "create fixture map: " + registration.code());
            map = (ZombiesMap) core.getMapByTypeWithName(BuiltInGameModes.ZOMBIES, name).orElseThrow();
            List<String> types = List.of(ZombiesDeployFieldSchema.WEAPON_WALL,
                    ZombiesDeployFieldSchema.AMMO_BOX, ZombiesDeployFieldSchema.ARMOR_STATION);
            for (int index = 0; index < types.size(); index++) {
                var added = service.addObject(player, stack, draft(name, types.get(index), -1, level, positions.get(index)));
                check(added.success(), "stage " + types.get(index) + ": " + added.code());
            }
            check(service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).success(), "save creates all three box blocks");
            assertBoxes(level, positions);
            check(map.objects().weaponWalls().size() == 1 && map.objects().ammoBoxes().size() == 1
                    && map.objects().armorStations().size() == 1, "save retains all three definitions");

            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            check(service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).success(),
                    "saving an unchanged draft must repair missing world blocks");
            assertBoxes(level, positions);

            for (int index = 0; index < types.size(); index++) {
                var deleted = service.deleteObject(player, stack, draft(name, types.get(index), 0, level, positions.get(index)));
                check(deleted.success(), "stage deletion of " + types.get(index) + ": " + deleted.code());
            }
            check(service.saveDraft(player, stack, ZombiesDeployTool.getDraft(stack)).success(), "save removes all three box blocks");
            check(map.objects().weaponWalls().isEmpty() && map.objects().ammoBoxes().isEmpty()
                    && map.objects().armorStations().isEmpty(), "save removes all three definitions");
            for (BlockPos pos : positions) {
                check(level.getBlockState(pos).isAir(), "deleted box must not remain in the world at " + pos);
            }
            helper.succeed();
        } finally {
            service.discardDraft(player, stack, ZombiesDeployTool.getDraft(stack));
            if (map != null) {
                core.unregisterMap(map);
            }
            restore(level, original);
        }
    }

    private static List<Block> boxes() {
        return List.of(CodPatternBlockRegister.ZOMBIES_WEAPON_WALL_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get(), CodPatternBlockRegister.ZOMBIES_ARMOR_STATION_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_SODA_MACHINE_BOX.get(), CodPatternBlockRegister.ZOMBIES_ULTIMATE_MACHINE_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get());
    }

    private static List<BlockPos> positions(GameTestHelper helper, int firstY, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> helper.absolutePos(new BlockPos(0, firstY + index * 3, 0))).toList();
    }

    private static ZombiesMapObjects objects(ServerLevel level, List<BlockPos> positions) {
        var dimension = level.dimension();
        return new ZombiesMapObjects(List.of(), List.of(), List.of(),
                List.of(new ZombiesWeaponWallData("wall", dimension, positions.get(0), Optional.empty())),
                List.of(new ZombiesAmmoBoxData("ammo", Map.of("1", 100), dimension, positions.get(1), Optional.empty())),
                List.of(new ZombiesArmorStationData("armor", 1, 100, 0.75D, dimension, positions.get(2), Optional.empty())),
                Optional.empty(),
                List.of(new ZombiesSodaMachineData("soda", "juggernog", 100, false, dimension, positions.get(3), Optional.empty())),
                List.of(new ZombiesUltimateMachineData("ultimate", 1, Map.of(), false, dimension, positions.get(4), Optional.empty())),
                List.of(new ZombiesMysteryBoxData("mystery", 100, List.of(), dimension, positions.get(5), Optional.empty())),
                List.of());
    }

    private static ZombiesDeployDraft draft(String name, String type, int selectedIndex, ServerLevel level, BlockPos pos) {
        var fields = new HashMap<>(ZombiesDeployFieldSchema.defaultFields(type));
        fields.put("objectId", "test-" + type);
        fields.put("dimension", level.dimension().location().toString());
        fields.put("posX", Integer.toString(pos.getX()));
        fields.put("posY", Integer.toString(pos.getY()));
        fields.put("posZ", Integer.toString(pos.getZ()));
        fields.put("interactionX", Integer.toString(pos.getX()));
        fields.put("interactionY", Integer.toString(pos.getY()));
        fields.put("interactionZ", Integer.toString(pos.getZ()));
        return new ZombiesDeployDraft(name, type, selectedIndex, ZombiesDeployFieldSchema.PROFILE_MVP3, fields);
    }

    private static Map<BlockPos, BlockState> prepare(ServerLevel level, List<BlockPos> positions) {
        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            original.put(pos.below(), level.getBlockState(pos.below()));
            original.put(pos, level.getBlockState(pos));
            level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        return original;
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, List<BlockPos> positions) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        positions.forEach(pos -> result.put(pos, level.getBlockState(pos)));
        return result;
    }

    private static void assertBoxes(ServerLevel level, List<BlockPos> positions) {
        for (int index = 0; index < positions.size(); index++) {
            check(level.getBlockState(positions.get(index)).is(boxes().get(index)),
                    "expected " + boxes().get(index) + " at " + positions.get(index));
        }
    }

    private static void assertStates(ServerLevel level, Map<BlockPos, BlockState> expected, String message) {
        expected.forEach((pos, state) -> check(level.getBlockState(pos).equals(state), message + " at " + pos));
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
