package com.cdp.codpattern.compat.fpsmatch.map.zombies;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** N05 physical evidence supplementing, but never replacing, shape-only tests. */
@GameTestHolder("codpattern_navigation_drop_surfaces")
@PrefixGameTestTemplate(false)
public final class ZombiesNavigationDropSurfaceGameTests {
    private ZombiesNavigationDropSurfaceGameTests() { }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_surfaces",timeoutTicks=610)
    public static void originalZombieFallsThroughOneBlockShaftThenPursuesThroughBottomOutlet(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.surface(helper,ZombiesNavigationSupplementalDropFixture.Surface.SHAFT);
    }

    @GameTest(setupTicks=20,template="zombies_navigation_drop",batch="navigation_drop_surfaces",timeoutTicks=610)
    public static void originalZombieFirstLandsOnHalfSlabsThenPursuesOnTheirFractionalSurface(GameTestHelper helper) {
        ZombiesNavigationSupplementalDropFixture.surface(helper,ZombiesNavigationSupplementalDropFixture.Surface.SLAB);
    }
}
