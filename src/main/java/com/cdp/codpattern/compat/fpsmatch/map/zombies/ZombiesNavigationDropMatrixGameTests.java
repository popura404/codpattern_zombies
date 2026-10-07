package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Supplemental physical depth/health matrix; these cases were added after implementation. */
@GameTestHolder("codpattern_navigation_drop_matrix")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDropMatrixGameTests {
    private ZombiesNavigationDropMatrixGameTests() { }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalFullHealthZombieDropsThreeBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,3,null);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalTwoHealthZombieDropsThreeBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,3,2.0F);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalTwoHealthZombieDropsEightBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,8,2.0F);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalFullHealthZombieDropsSixteenBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,16,null);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalTwoHealthZombieDropsSixteenBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,16,2.0F);
    }

    @GameTest(setupTicks = 20, template="zombies_navigation_drop",batch="navigation_drop_matrix",timeoutTicks=610)
    public static void originalTwoHealthZombieDropsThirtyTwoBlocks(GameTestHelper helper) {
        ZombiesNavigationLongRouteGameTests.supplementalDrop(helper,32,2.0F);
    }
}
