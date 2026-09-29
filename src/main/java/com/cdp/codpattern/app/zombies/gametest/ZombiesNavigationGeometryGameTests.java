package com.cdp.codpattern.app.zombies.gametest;

import com.cdp.codpattern.app.zombies.service.ZombiesNavigationGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Geometry classification must leave native stepping to actual entity movement. */
@GameTestHolder("codpattern_navigation")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationGeometryGameTests {
    private ZombiesNavigationGeometryGameTests() { }

    @GameTest(template = "zombies_navigation", batch = "zombies_navigation_geometry", timeoutTicks = 65)
    public static void nativeStepObstacleIsUnknownWhileTallWallsRemainBlocked(GameTestHelper helper) {
        for (int x = 3; x <= 10; x++) {
            for (int z = 4; z <= 6; z++) {
                for (int y = 0; y <= 4; y++) {
                    boolean solid = y == 0 || y == 4 || z == 4 || z == 6;
                    helper.setBlock(new BlockPos(x, y, z), solid ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        Zombie mob = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(mob != null, "the geometry fixture must create a zombie");
        Vec3 start = helper.absoluteVec(new Vec3(4.5D, 1.0D, 5.5D));
        Vec3 end = helper.absoluteVec(new Vec3(8.5D, 1.0D, 5.5D));
        mob.moveTo(start.x, start.y, start.z, 0.0F, 0.0F);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        mob.setOnGround(true);
        helper.getLevel().addFreshEntity(mob);
        try {
            helper.assertTrue(ZombiesNavigationGeometry.checkSegment(mob, start, end)
                            == ZombiesNavigationGeometry.SegmentStatus.CLEAR,
                    "an unobstructed level corridor must be CLEAR");
            Long clearSignature = ZombiesNavigationGeometry.collisionSignature(mob, start, end);
            helper.assertTrue(clearSignature != null, "the loaded corridor must have a geometry signature");
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(6, y, 5), Blocks.STONE);
            }
            helper.assertTrue(ZombiesNavigationGeometry.checkSegment(mob, start, end)
                            == ZombiesNavigationGeometry.SegmentStatus.BLOCKED,
                    "a three-block wall must remain BLOCKED");
            Long wallSignature = ZombiesNavigationGeometry.collisionSignature(mob, start, end);
            helper.assertTrue(wallSignature != null && !wallSignature.equals(clearSignature),
                    "adding the blocking wall must change the actual collision signature");
            for (int y = 2; y <= 3; y++) {
                helper.setBlock(new BlockPos(6, y, 5), Blocks.AIR);
            }
            helper.setBlock(new BlockPos(6, 1, 5), Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.OPEN, false).setValue(TrapDoorBlock.HALF, Half.BOTTOM));
            helper.assertTrue(ZombiesNavigationGeometry.checkSegment(mob, start, end)
                            == ZombiesNavigationGeometry.SegmentStatus.UNKNOWN,
                    "a level sweep through a low step must defer to native movement");
            Long stepSignature = ZombiesNavigationGeometry.collisionSignature(mob, start, end);
            helper.assertTrue(stepSignature != null && !stepSignature.equals(wallSignature)
                            && stepSignature.equals(ZombiesNavigationGeometry.collisionSignature(mob, start, end)),
                    "replacing the wall by a low shape must produce a changed, repeatable collision signature");
        } catch (RuntimeException | Error failure) {
            mob.discard();
            throw failure;
        }
        boolean[] roseOntoObstacle = {false};
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) {
                return;
            }
            try {
                helper.assertTrue(mob.isAlive(), "the same fixture zombie must perform the physical step");
                // Exercise Entity.move's actual collision/step solver, with no jump command or Y teleport.
                mob.setDeltaMovement(Vec3.ZERO);
                mob.move(MoverType.SELF, new Vec3(0.125D, -0.08D, 0.0D));
                roseOntoObstacle[0] |= mob.getY() > start.y + 0.1D;
                if (mob.getX() >= end.x && Math.abs(mob.getY() - start.y) < 0.01D) {
                    helper.assertTrue(roseOntoObstacle[0],
                            "native movement must physically rise onto the low obstacle before passing it");
                    finished[0] = true;
                    mob.discard();
                    helper.succeed();
                } else if (helper.getTick() >= 60) {
                    helper.fail("native movement did not cross the low obstacle; position=" + mob.position()
                            + ", start=" + start + ", end=" + end + ", rose=" + roseOntoObstacle[0]);
                }
            } catch (RuntimeException | Error failure) {
                finished[0] = true;
                mob.discard();
                throw failure;
            }
        });
    }
}
