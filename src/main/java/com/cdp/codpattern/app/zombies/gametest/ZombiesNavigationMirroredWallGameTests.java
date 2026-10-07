package com.cdp.codpattern.app.zombies.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Reflect both the collision box and physical start/target, preserving the existing 500-tick assertions. */
@GameTestHolder("codpattern_navigation_mirrored_walls")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationMirroredWallGameTests {
    private ZombiesNavigationMirroredWallGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_mirrored_walls",timeoutTicks=510)
    public static void mirroredHalfThicknessWallPreservesPhysicalRecovery(GameTestHelper helper) {
        ZombiesNavigationRecoveryGameTests.runMirroredWall(helper,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation",batch="navigation_mirrored_walls",timeoutTicks=510)
    public static void rotatedAndMirroredThinWallPreservesPhysicalRecovery(GameTestHelper helper) {
        ZombiesNavigationRecoveryGameTests.runMirroredWall(helper,true);
    }
}
