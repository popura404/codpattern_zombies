package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.service.ZombiesBackpackRuntimeTestSupport;
import com.cdp.codpattern.config.zombies.ZombiesBackpackCompatSuite;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** ./gradlew runGameTestServer -PbackpackGameTests */
@GameTestHolder("codpattern_backpack")
@PrefixGameTestTemplate(false)
public final class ZombiesBackpackGameTests {
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 200, required = true)
    public static void configuration(GameTestHelper helper) throws Exception {
        ZombiesBackpackCompatSuite.main(new String[0]);
        ZombiesBackpackRuntimeTestSupport.repository();
        helper.succeed();
    }
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 200, required = true)
    public static void categoryCapAcrossThreeEntrypoints(GameTestHelper helper) {
        ZombiesBackpackRuntimeTestSupport.entrypoints(helper, null);
    }
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 200, required = true)
    public static void gunOverrideAcrossThreeEntrypoints(GameTestHelper helper) {
        ZombiesBackpackRuntimeTestSupport.entrypoints(helper, 257);
    }
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 200, required = true)
    public static void zeroReserveAcrossThreeEntrypoints(GameTestHelper helper) {
        ZombiesBackpackRuntimeTestSupport.entrypoints(helper, 0);
    }
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 100, required = true)
    public static void attachmentsReloadRefillUpgradeAndRestore(GameTestHelper helper) {
        ZombiesBackpackRuntimeTestSupport.attachmentsReloadRefillAndRestore(helper.getLevel());
        helper.succeed();
    }
    @GameTest(template = "empty", batch = "backpack", timeoutTicks = 100, required = true)
    public static void numericErrorsBeforeClearAndRuntimeRollback(GameTestHelper helper) throws Exception {
        ZombiesBackpackRuntimeTestSupport.invalidConfigBeforeStartupAndRuntimeFailure(helper.getLevel());
        helper.succeed();
    }
    private ZombiesBackpackGameTests() { }
}
