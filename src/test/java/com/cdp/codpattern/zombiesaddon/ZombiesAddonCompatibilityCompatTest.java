package com.cdp.codpattern.zombiesaddon;

import com.cdp.codpattern.architecture.ModeSplitVerificationRoots;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ZombiesAddonCompatibilityCompatTest {
    private static final String ADDON_VERSION = "0.2.4b";

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
        Path addonRoot = ModeSplitVerificationRoots.repositoryRoot();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(addonRoot.resolve("gradle.properties"))) {
            properties.load(reader);
        }
        String metadata = read(addonRoot.resolve("src/main/resources/META-INF/mods.toml"));

        require(ADDON_VERSION.equals(properties.getProperty("mod_version")),
                "addon project version must be " + ADDON_VERSION);
        String mainVersion = properties.getProperty("codpattern_version", "");
        require(!mainVersion.isBlank()
                        && ("[" + mainVersion + ",)").equals(properties.getProperty("codpattern_version_range")),
                "main compatibility must start at the independently pinned published main version");
        require(metadata.contains("versionRange=\"${codpattern_version_range}\""),
                "main dependency must use its independent compatibility range");
        require(!metadata.contains("versionRange=\"[${mod_version}]\""),
                "main dependency must not be coupled to the addon version");
    }

    private static void physicalEntriesPreserveBootstrapOrdering() throws Exception {
        Path addonRoot = ModeSplitVerificationRoots.repositoryRoot();
        String addonEntry = read(addonRoot.resolve(
                "src/main/java/com/cdp/codpattern/zombiesaddon/ZombiesAddon.java"));
        String zombiesBootstrap = read(addonRoot.resolve(
                "src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesBootstrap.java"));

        require(addonEntry.contains("ZombiesAddonCompatibility.install(localVersion);")
                        && addonEntry.contains("ZombiesBootstrap.install(modEventBus);"),
                "addon entry must install topology enforcement and ZombiesBootstrap");
        require(zombiesBootstrap.contains("ZombiesNetworkPacketContributor.install();"),
                "addon construction must install packet contributions");
    }

    /** Optional cross-project source audit, invoked only by runMainSourceIntegrationCompat. */
    public static void mainSourceContracts(Path mainRoot) throws Exception {
        String mainEntry = read(mainRoot.resolve("src/main/java/com/cdp/codpattern/CodPattern.java"));
        String coreBootstrap = read(mainRoot.resolve(
                "src/main/java/com/cdp/codpattern/bootstrap/CoreBootstrap.java"));
        require(mainEntry.contains("CoreBootstrap.install(modEventBus);")
                        && !mainEntry.contains("ZombiesBootstrap"),
                "main entry must install only CoreBootstrap");
        require(coreBootstrap.contains("modEventBus.addListener(CoreBootstrap::onCommonSetup);")
                        && coreBootstrap.contains("ModNetworkChannel.register();"),
                "main must register the real channel from the later common-setup callback");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
