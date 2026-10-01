package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfigCompatTest;
import com.cdp.codpattern.app.zombies.service.ZombiesBarrierGroupRulesPurchaseCompatTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Run with -PbarrierGroupRulesGameTests; requires Forge's transformed runtime, not plain JavaExec. */
@GameTestHolder("codpattern_barrier_rules")
@PrefixGameTestTemplate(false)
public final class ZombiesBarrierGroupRulesGameTests {
    @GameTest(template="empty", batch="barrier_group_rules", timeoutTicks=200, required=true)
    public static void configuration(GameTestHelper helper) throws Exception {
        ZombiesBarrierGroupsConfigCompatTest.main(new String[0]);
        passed("configuration"); helper.succeed();
    }
    @GameTest(template="empty", batch="barrier_group_rules", timeoutTicks=200, required=true)
    public static void purchase(GameTestHelper helper) throws Exception {
        ZombiesBarrierGroupRulesPurchaseCompatTest.run(helper.getLevel());
        passed("purchase"); helper.succeed();
    }
    @GameTest(template="empty", batch="barrier_group_rules", timeoutTicks=200, required=true)
    public static void editor(GameTestHelper helper) throws Exception {
        ZombiesBarrierGroupRulesEditorCompatTest.main(new String[0]);
        passed("editor"); helper.succeed();
    }
    private static void passed(String name) throws Exception {
        Path result=Path.of(System.getProperty("codpattern.test.workspace", "."))
                .resolve(System.getProperty("codpattern.test.results", "build/verification/barrier-group-rules/forge-results.txt"));
        Files.createDirectories(result.getParent());
        Files.writeString(result,name+":PASS\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
