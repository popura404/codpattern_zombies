package com.cdp.codpattern.app.zombies.service;

import java.util.List;

/** Source and close-response checks that do not require the Forge launch transformer. */
public final class ZombiesDeployCompatTestSuite {
    private ZombiesDeployCompatTestSuite() { }

    public static void main(String[] args) throws Throwable {
        com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraftSessionCompatTest.closeWaitsForTheMatchingSuccessfulResponse();
        int skipped = ZombiesCompatSuiteRunner.runAll(List.of(
                new ZombiesCompatSuiteRunner.TestEntry("Deploy object editor static contract",
                        "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployObjectEditorStaticContractCompatTest", false),
                new ZombiesCompatSuiteRunner.TestEntry("Deploy GUI, interaction and localization contracts",
                        "com.cdp.codpattern.app.zombies.deploy.ZombiesDeployGuiStaticContractCompatTest", false)), args);
        if (skipped != 0) {
            throw new AssertionError("Deploy regression gate must not skip tests");
        }
        System.out.println("PASS deployment source contracts and close response behavior; runGameTestServer covers runtime checks");
    }
}
