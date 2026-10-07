package com.cdp.codpattern.verification.phase7;

import com.cdp.codpattern.app.match.GameModeRegistry;
import com.cdp.codpattern.app.match.GameModeRuntimeRegistry;
import com.cdp.codpattern.app.match.ModeModules;
import com.cdp.codpattern.app.match.model.ClientModePresentationRegistry;
import com.cdp.codpattern.app.tdm.TdmModeModule;
import com.cdp.codpattern.app.zombies.ZombiesModeModule;
import com.cdp.codpattern.architecture.ModeSplitVerificationRoots;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.jar.JarFile;

/** Verifies addon ownership and mode registration against the published main-mod JAR. */
public final class ModeSplitPhase7BoundaryCompatTest {
    private ModeSplitPhase7BoundaryCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        testRunsFromExpectedClassRoot();
        physicalArtifactsHaveIndependentOwnership();
        zombiesGatewaysStayOutsideTheCompositionShim();
        combinedDistributionProvidesAllThreeModes();
        verificationHarnessUsesPublishedDependency();
        System.out.println("PASS Phase 7 addon ownership, public gateway, published dependency, and combined modes compat");
    }

    private static void physicalArtifactsHaveIndependentOwnership() throws IOException {
        Path root = ModeSplitVerificationRoots.repositoryRoot();
        Path addonJava = root.resolve("src/main/java");
        require(Files.isDirectory(addonJava), "addon sources must belong to this checkout");
        require(!Files.exists(addonJava.resolve("com/cdp/codpattern/CodPattern.java")),
                "addon must not own or bundle the main entry point");
        try (JarFile mainJar = new JarFile(ModeSplitVerificationRoots.mainDependencyJar().toFile());
             var sources = Files.walk(addonJava)) {
            require(mainJar.getJarEntry("com/cdp/codpattern/app/tdm/TdmModeModule.class") != null,
                    "published main dependency must own TdmModeModule");
            require(mainJar.getJarEntry("com/cdp/codpattern/app/zombies/ZombiesModeModule.class") == null,
                    "published main dependency must not bundle ZombiesModeModule");
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String entry = addonJava.relativize(source).toString().replace('\\', '/')
                        .replaceFirst("\\.java$", ".class");
                require(mainJar.getJarEntry(entry) == null,
                        "addon production class collides with the published main dependency: " + entry);
            }
        }
    }

    private static void zombiesGatewaysStayOutsideTheCompositionShim() throws IOException {
        Map<String, List<String>> requiredPublicRoutes = Map.of(
                "com.cdp.codpattern.app.zombies.bootstrap.ZombiesBootstrap",
                List.of("ModeModules.contribute(ZombiesModeModule.INSTANCE)"),
                "com.cdp.codpattern.app.zombies.ZombiesModeModule",
                List.of("implements ModeModule", "ZombiesGameModeDefinitions.definitions()",
                        "playerLoginContributors()", "areaProtectionContributors()"),
                "com.cdp.codpattern.app.zombies.bootstrap.ZombiesClientBootstrap",
                List.of("ModeClientActionHandlers.register(", "ModeGuiOverlayContributors.register("),
                "com.cdp.codpattern.app.zombies.bootstrap.ZombiesNetworkPacketContributor",
                List.of("ModeNetworkPacketContributions.install(", "ModeNetworkPacketSlots."),
                "com.cdp.codpattern.app.zombies.model.ZombiesGameModeDefinitions",
                List.of("Optional.of(ZombiesRuntimeProvider.INSTANCE)"));

        for (Map.Entry<String, List<String>> entry : requiredPublicRoutes.entrySet()) {
            Path sourcePath = ModeSplitVerificationRoots.productionJavaSource(entry.getKey());
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            require(!source.contains("com.cdp.codpattern.CodPattern")
                            && !source.contains("CoreBootstrap")
                            && !source.contains("getDeclared")
                            && !source.contains("setAccessible"),
                    "addon gateway must not reach into the composition shim or reflective internals: "
                            + sourcePath);
            for (String requiredRoute : entry.getValue()) {
                require(source.contains(requiredRoute),
                        "addon gateway is missing public boundary route " + requiredRoute + " in " + sourcePath);
            }
        }
    }

    private static void combinedDistributionProvidesAllThreeModes() {
        ModeModules.contribute(TdmModeModule.INSTANCE);
        ModeModules.contribute(ZombiesModeModule.INSTANCE);
        ModeModules.freeze();

        List<String> gameTypes = GameModeRegistry.orderedDefinitions().stream()
                .map(definition -> definition.gameType())
                .toList();
        require(gameTypes.equals(List.of("frontline", "teamdeathmatch", "zombies")),
                "combined distribution should expose all three modes in stable order: " + gameTypes);
        for (String gameType : gameTypes) {
            require(GameModeRuntimeRegistry.find(gameType).isPresent(),
                    "combined distribution is missing runtime provider for " + gameType);
            require(ClientModePresentationRegistry.find(gameType).isPresent(),
                    "combined distribution is missing client presentation for " + gameType);
        }
    }

    private static void verificationHarnessUsesPublishedDependency() throws IOException {
        Path root = ModeSplitVerificationRoots.repositoryRoot();
        String settings = Files.readString(root.resolve("settings.gradle"), StandardCharsets.UTF_8);
        String build = Files.readString(root.resolve("build.gradle"), StandardCharsets.UTF_8);
        require(!Pattern.compile("(?m)^\\s*include\\s*\\(?[\"']?:?codpattern-main")
                        .matcher(settings).find(),
                "addon build must not include a main-mod source subproject");
        require(!settings.contains("../codPattern") && !build.contains("mainProject"),
                "standalone build must not reach a sibling main checkout");
        require(build.contains("maven.modrinth:cod-pattern:"),
                "addon must resolve its main dependency from the published Modrinth artifact");
        ModeSplitVerificationRoots.mainDependencyJar();
    }

    private static void testRunsFromExpectedClassRoot() throws Exception {
        String configured = System.getProperty("modeSplit.expectedTestClassRoots", "").trim();
        if (configured.isEmpty()) {
            return;
        }
        Path actualRoot = Path.of(ModeSplitPhase7BoundaryCompatTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
        List<Path> expectedRoots = List.of(configured.split(Pattern.quote(File.pathSeparator), -1)).stream()
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        require(expectedRoots.contains(actualRoot),
                "boundary compatibility test loaded from " + actualRoot
                        + " instead of its target-owned test output " + expectedRoots);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}
