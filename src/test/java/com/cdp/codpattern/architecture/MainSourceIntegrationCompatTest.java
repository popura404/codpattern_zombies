package com.cdp.codpattern.architecture;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployGuiStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.FpsmToolCreativeTabStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.FpsmToolPermissionStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.ModeScopedToolPresentationStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.ZombiesBoxBlockEntryStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.ZombiesRoomLobbyFlowStaticContractCompatTest;
import com.cdp.codpattern.app.zombies.service.ZombiesWaveRuntimeStaticContractCompatTest;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonCompatibilityCompatTest;

import java.nio.file.Files;
import java.nio.file.Path;

/** Optional joint-source audit; ordinary addon verification needs only the published main-mod artifact. */
public final class MainSourceIntegrationCompatTest {
    private MainSourceIntegrationCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        String configuredRoot = System.getProperty("codpattern.mainSourceRoot");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "Joint-source auditing requires -Dcodpattern.mainSourceRoot=<main-mod-project-root>");
        }
        Path mainSourceRoot = Path.of(configuredRoot).toRealPath();
        Path mainBootstrap = mainSourceRoot.resolve(
                "src/main/java/com/cdp/codpattern/bootstrap/CoreBootstrap.java");
        if (!Files.isRegularFile(mainBootstrap)) {
            throw new IllegalArgumentException(
                    "codpattern.mainSourceRoot must point to the main mod project root: " + mainSourceRoot);
        }

        ZombiesDeployGuiStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        FpsmToolCreativeTabStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        FpsmToolPermissionStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        ModeScopedToolPresentationStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        ZombiesBoxBlockEntryStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        ZombiesRoomLobbyFlowStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        ZombiesWaveRuntimeStaticContractCompatTest.mainSourceContracts(mainSourceRoot);
        ZombiesAddonCompatibilityCompatTest.mainSourceContracts(mainSourceRoot);

        System.out.println("PASS explicit main-source integration compat");
    }
}
