package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.ModeModules;
import com.cdp.codpattern.app.tdm.TdmModeModule;
import com.cdp.codpattern.app.zombies.ZombiesModeModule;
import java.util.ArrayList;
import java.util.List;

/** Focused regressions for the shared termination integration; no runtime-dependent skips. */
public final class ZombiesForceEndCompatTestSuite {
    private ZombiesForceEndCompatTestSuite() { }

    public static void main(String[] args) throws Throwable {
        ModeModules.contribute(TdmModeModule.INSTANCE);
        ModeModules.contribute(ZombiesModeModule.INSTANCE);
        ModeModules.freeze();
        List<String> tests = List.of(
                "com.cdp.codpattern.zombiesaddon.ZombiesAddonCompatibilityCompatTest",
                "com.cdp.codpattern.app.zombies.service.ZombiesCrashRecoveryServiceCompatTest",
                "com.cdp.codpattern.app.zombies.service.ZombiesReconnectRecoveryStaticContractCompatTest",
                "com.cdp.codpattern.app.zombies.service.ZombiesRoomLobbyFlowStaticContractCompatTest",
                "com.cdp.codpattern.app.zombies.service.ZombiesBuffRuntimeEffectStaticContractCompatTest",
                "com.cdp.codpattern.app.zombies.service.ZombiesWaveTextCompatTest");
        List<String> failures = new ArrayList<>();
        for (String test : tests) {
            try {
                ZombiesCompatSuiteRunner.run(new ZombiesCompatSuiteRunner.TestEntry(test, test, false), args);
            } catch (Throwable failure) {
                failures.add(test + ": " + failure);
                failure.printStackTrace();
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("PASS Zombies force-end integration regressions: " + tests.size() + "/" + tests.size());
    }
}
