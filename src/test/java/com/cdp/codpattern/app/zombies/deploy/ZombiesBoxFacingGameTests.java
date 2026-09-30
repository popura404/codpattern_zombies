package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.cdp.codpattern.common.block.ZombiesBoxInteractionBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesBoxFacingGameTests {
    private ZombiesBoxFacingGameTests() { }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void placingEveryBoxFromEachSideFacesThePlayer(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = FakePlayerFactory.getMinecraft(level);
        Vec3 originalPlayerPosition = player.position();
        float originalYaw = player.getYRot();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockState originalSupport = level.getBlockState(pos.below());
        BlockState originalBlock = level.getBlockState(pos);
        try {
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            for (ZombiesBoxInteractionBlock box : boxes()) {
                check(box.defaultBlockState().getValue(ZombiesBoxInteractionBlock.FACING) == Direction.NORTH,
                        box + " must retain the original north-facing default for existing worlds");
                for (Direction lookDirection : Direction.Plane.HORIZONTAL) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    player.setPos(pos.getX() + 0.5D - lookDirection.getStepX() * 2,
                            pos.getY(), pos.getZ() + 0.5D - lookDirection.getStepZ() * 2);
                    player.setYRot(lookDirection.toYRot());
                    ItemStack stack = new ItemStack(box);
                    var hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false);
                    var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
                    check(context.getClickedPos().equals(pos), "fixture must place above the clicked support");
                    check(((BlockItem) stack.getItem()).place(context).consumesAction(),
                            box + " must be placeable while looking " + lookDirection);
                    BlockState placed = level.getBlockState(pos);
                    check(placed.is(box) && placed.getValue(ZombiesBoxInteractionBlock.FACING) == lookDirection.getOpposite(),
                            box + " front must face the player when placed while looking " + lookDirection);
                }
            }
            helper.succeed();
        } finally {
            player.setPos(originalPlayerPosition.x, originalPlayerPosition.y, originalPlayerPosition.z);
            player.setYRot(originalYaw);
            level.setBlock(pos, originalBlock, Block.UPDATE_ALL);
            level.setBlock(pos.below(), originalSupport, Block.UPDATE_ALL);
        }
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void structureRotationsAndMirrorsPreserveBoxFacing(GameTestHelper helper) {
        for (ZombiesBoxInteractionBlock box : boxes()) {
            check(box.getStateDefinition().getPossibleStates().size() == 4,
                    box + " must expose exactly the four horizontal facing states");
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BlockState state = box.defaultBlockState().setValue(ZombiesBoxInteractionBlock.FACING, facing);
                check(state.rotate(Rotation.NONE).equals(state), "zero rotation must retain " + facing);
                check(state.rotate(Rotation.CLOCKWISE_90).getValue(ZombiesBoxInteractionBlock.FACING) == facing.getClockWise(),
                        box + " must turn clockwise from " + facing);
                check(state.rotate(Rotation.CLOCKWISE_180).getValue(ZombiesBoxInteractionBlock.FACING) == facing.getOpposite(),
                        box + " must turn halfway from " + facing);
                check(state.rotate(Rotation.COUNTERCLOCKWISE_90).getValue(ZombiesBoxInteractionBlock.FACING) == facing.getCounterClockWise(),
                        box + " must turn counterclockwise from " + facing);
                check(state.mirror(Mirror.NONE).equals(state), "no mirror must retain " + facing);
                Direction leftRight = facing.getAxis() == Direction.Axis.Z ? facing.getOpposite() : facing;
                Direction frontBack = facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
                check(state.mirror(Mirror.LEFT_RIGHT).getValue(ZombiesBoxInteractionBlock.FACING) == leftRight,
                        box + " must mirror north/south from " + facing);
                check(state.mirror(Mirror.FRONT_BACK).getValue(ZombiesBoxInteractionBlock.FACING) == frontBack,
                        box + " must mirror east/west from " + facing);
                check(!state.canOcclude(), box + " must stay non-occluding in every direction");
            }
        }
        helper.succeed();
    }

    private static List<ZombiesBoxInteractionBlock> boxes() {
        return List.of(CodPatternBlockRegister.ZOMBIES_WEAPON_WALL_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get(), CodPatternBlockRegister.ZOMBIES_ARMOR_STATION_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_SODA_MACHINE_BOX.get(), CodPatternBlockRegister.ZOMBIES_ULTIMATE_MACHINE_BOX.get(),
                CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
