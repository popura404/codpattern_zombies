package com.cdp.codpattern.zombiesaddon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZombiesAddonCompatibilityCompatTest {
    private ZombiesAddonCompatibilityCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        exactVersionIsAcceptedInBothDirections();
        missingOrDifferentAddonIsRejectedInBothDirections();
        physicalEntriesPreserveBootstrapOrdering();
        System.out.println("PASS Zombies addon entry and display-test compat");
    }

    private static void exactVersionIsAcceptedInBothDirections() {
        require(ZombiesAddonCompatibility.addonClientAcceptsServer("0.7.6b", "0.7.6b"),
                "addon client should accept an exact-version addon server");
        require(ZombiesAddonCompatibility.addonServerAcceptsClient("0.7.6b", "0.7.6b"),
                "addon server should accept an exact-version addon client");
        require(ZombiesAddonCompatibility.acceptsRemoteVersion("0.7.6b", "0.7.6b", true),
                "DisplayTest client-to-server branch should accept the exact version");
        require(ZombiesAddonCompatibility.acceptsRemoteVersion("0.7.6b", "0.7.6b", false),
                "DisplayTest server-to-client branch should accept the exact version");
    }

    private static void missingOrDifferentAddonIsRejectedInBothDirections() {
        require(!ZombiesAddonCompatibility.addonClientAcceptsServer("0.7.6b", "ABSENT"),
                "addon client must reject a main-only server");
        require(!ZombiesAddonCompatibility.addonServerAcceptsClient("0.7.6b", "ABSENT"),
                "addon server must reject a main-only client");
        require(!ZombiesAddonCompatibility.addonClientAcceptsServer("0.7.6b", "0.7.6c"),
                "addon client must reject a different addon version");
        require(!ZombiesAddonCompatibility.addonServerAcceptsClient("0.7.6b", "0.7.6c"),
                "addon server must reject a different addon version");
    }

    private static void physicalEntriesPreserveBootstrapOrdering() throws Exception {
        String mainEntry = read("src/main/java/com/cdp/codpattern/CodPattern.java");
        String addonEntry = read(
                "../zombies-addon/src/main/java/com/cdp/codpattern/zombiesaddon/ZombiesAddon.java");
        String coreBootstrap = read(
                "src/main/java/com/cdp/codpattern/bootstrap/CoreBootstrap.java");
        String zombiesBootstrap = read(
                "../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesBootstrap.java");

        require(mainEntry.contains("CoreBootstrap.install(modEventBus);")
                        && !mainEntry.contains("ZombiesBootstrap"),
                "main entry must install only CoreBootstrap");
        require(addonEntry.contains("ZombiesAddonCompatibility.install(localVersion);")
                        && addonEntry.contains("ZombiesBootstrap.install(modEventBus);"),
                "addon entry must install topology enforcement and ZombiesBootstrap");
        require(zombiesBootstrap.contains("ZombiesNetworkPacketContributor.install();"),
                "addon construction must install packet contributions");
        require(coreBootstrap.contains("modEventBus.addListener(CoreBootstrap::onCommonSetup);")
                        && coreBootstrap.contains("ModNetworkChannel.register();"),
                "main must register the real channel from the later common-setup callback");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
