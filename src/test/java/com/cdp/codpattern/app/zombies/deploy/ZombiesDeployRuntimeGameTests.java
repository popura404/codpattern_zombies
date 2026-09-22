package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.CodPatternConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Deployment regressions require Forge's event and registry transformations. */
@GameTestHolder(CodPatternConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ZombiesDeployRuntimeGameTests {
    private ZombiesDeployRuntimeGameTests() { }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void stagedSelectionRollbackAndResponseCodecs(GameTestHelper helper) {
        run(helper, "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraftSessionCompatTest", "main");
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void deployObjectCrud(GameTestHelper helper) {
        run(helper, "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployObjectEditorCompatTest", "runAll");
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void issueTargets(GameTestHelper helper) {
        run(helper, "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployToolServiceIssueTargetCompatTest", "main");
    }

    @GameTest(template = "empty", batch = "zombies_deploy", timeoutTicks = 40, required = true)
    public static void issueRequestCodecs(GameTestHelper helper) {
        run(helper, "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployIssueRoutingCompatTest", "main");
    }

    private static void run(GameTestHelper helper, String className, String methodName) {
        try {
            Class<?> test = Class.forName(className);
            if ("main".equals(methodName)) {
                test.getMethod(methodName, String[].class).invoke(null, (Object) new String[0]);
            } else {
                test.getMethod(methodName).invoke(null);
            }
            helper.succeed();
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            helper.fail("Deployment regression failed: " + cause);
        }
    }
}
