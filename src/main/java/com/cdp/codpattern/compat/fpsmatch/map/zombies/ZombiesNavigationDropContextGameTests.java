package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** N04 wave/health pairs run in separately configured EASY, NORMAL and HARD server processes. */
@GameTestHolder("codpattern_navigation_drop_context")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDropContextGameTests {
    private ZombiesNavigationDropContextGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_context",timeoutTicks=610)
    public static void waveOneFullHealthZombieDropsSixtyFourBlocksAndContinuesPursuit(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.context(helper,1,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_context",timeoutTicks=610)
    public static void waveOneTwoHealthZombieDropsSixtyFourBlocksAndContinuesPursuit(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.context(helper,1,true);
    }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_context",timeoutTicks=610)
    public static void waveTwentyFullHealthZombieDropsSixtyFourBlocksAndContinuesPursuit(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.context(helper,20,false);
    }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_context",timeoutTicks=610)
    public static void waveTwentyTwoHealthZombieDropsSixtyFourBlocksAndContinuesPursuit(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.context(helper,20,true);
    }
}
