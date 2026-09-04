package com.cdp.codpattern.zombiesaddon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ZombiesAddonCompatibilityCompatTest {
    private static final String ADDON_VERSION = "0.1.0b";

    private ZombiesAddonCompatibilityCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        exactVersionIsAcceptedInBothDirections();
        missingOrDifferentAddonIsRejectedInBothDirections();
        metadataUsesIndependentMainVersionRange();
        physicalEntriesPreserveBootstrapOrdering();
        System.out.println("PASS Zombies addon entry and display-test compat");
    }

    private static void exactVersionIsAcceptedInBothDirections() {
        require(ZombiesAddonCompatibility.addonClientAcceptsServer(ADDON_VERSION, ADDON_VERSION),
                "addon client should accept an exact-version addon server");
        require(ZombiesAddonCompatibility.addonServerAcceptsClient(ADDON_VERSION, ADDON_VERSION),
                "addon server should accept an exact-version addon client");
        require(ZombiesAddonCompatibility.acceptsRemoteVersion(ADDON_VERSION, ADDON_VERSION, true),
                "DisplayTest client-to-server branch should accept the exact version");
        require(ZombiesAddonCompatibility.acceptsRemoteVersion(ADDON_VERSION, ADDON_VERSION, false),
                "DisplayTest server-to-client branch should accept the exact version");
    }

    private static void missingOrDifferentAddonIsRejectedInBothDirections() {
        require(!ZombiesAddonCompatibility.addonClientAcceptsServer(ADDON_VERSION, "ABSENT"),
                "addon client must reject a main-only server");
        require(!ZombiesAddonCompatibility.addonServerAcceptsClient(ADDON_VERSION, "ABSENT"),
                "addon server must reject a main-only client");
        require(!ZombiesAddonCompatibility.addonClientAcceptsServer(ADDON_VERSION, "0.1.0c"),
                "addon client must reject a different addon version");
        require(!ZombiesAddonCompatibility.addonServerAcceptsClient(ADDON_VERSION, "0.1.0c"),
                "addon server must reject a different addon version");
    }

    private static void metadataUsesIndependentMainVersionRange() throws Exception {
        String properties = read("gradle.properties");
        String metadata = read("src/main/resources/META-INF/mods.toml");

        require(properties.contains("mod_version=" + ADDON_VERSION),
                "addon project version must be " + ADDON_VERSION);
        require(properties.contains("codpattern_version_range=[0.8.0b,)"),
                "main mod compatibility must start at 0.8.0b");
        require(metadata.contains("versionRange=\"${codpattern_version_range}\""),
                "main dependency must use its independent compatibility range");
        require(!metadata.contains("versionRange=\"[${mod_version}]\""),
                "main dependency must not be coupled to the addon version");
    }

    private static void physicalEntriesPreserveBootstrapOrdering() throws Exception {
        String mainEntry = read("../codPattern/src/main/java/com/cdp/codpattern/CodPattern.java");
        String addonEntry = read(
                "src/main/java/com/cdp/codpattern/zombiesaddon/ZombiesAddon.java");
        String coreBootstrap = read(
                "../codPattern/src/main/java/com/cdp/codpattern/bootstrap/CoreBootstrap.java");
        String zombiesBootstrap = read(
                "src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesBootstrap.java");

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
