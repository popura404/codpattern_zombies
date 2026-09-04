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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Combined-classpath checks that complement the isolated Phase 7 compiler and fresh-JVM fence. */
public final class ModeSplitPhase7BoundaryCompatTest {
    private static final Path OWNERSHIP_MANIFEST =
            ModeSplitVerificationRoots.resolveRepositoryPath(
                    Path.of("docs/mode-split/phase0/ownership-manifest.tsv"));

    private ModeSplitPhase7BoundaryCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        testRunsFromExpectedClassRoot();
        physicalSourceRootsContainBothRepositories();
        zombiesGatewaysStayOutsideTheCompositionShim();
        combinedDistributionProvidesAllThreeModes();
        verificationHarnessHasDocumentedTwoStageTopologyGates();
        System.out.println("PASS Phase 7 ownership, gateway, combined-distribution, and two-stage topology compat");
    }

    private static void physicalSourceRootsContainBothRepositories() throws IOException {
        List<Path> javaRoots = ModeSplitVerificationRoots.productionJavaRoots();
        List<Path> resourceRoots = ModeSplitVerificationRoots.productionResourceRoots();
        require(javaRoots.size() == 2 && javaRoots.stream().allMatch(Files::isDirectory),
                "physical verification must receive main and addon Java roots: " + javaRoots);
        require(resourceRoots.size() == 2 && resourceRoots.stream().allMatch(Files::isDirectory),
                "physical verification must receive main and addon resource roots: " + resourceRoots);
        require(Files.isRegularFile(ModeSplitVerificationRoots.productionJavaSource(
                        "com.cdp.codpattern.app.tdm.TdmModeModule")),
                "main physical root must own TdmModeModule");
        require(Files.isRegularFile(ModeSplitVerificationRoots.productionJavaSource(
                        "com.cdp.codpattern.app.zombies.ZombiesModeModule")),
                "addon physical root must own ZombiesModeModule");
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

    private static void verificationHarnessHasDocumentedTwoStageTopologyGates() throws IOException {
        String stage = System.getProperty("modeSplit.verificationStage", "combined");
        Path settingsPath = ModeSplitVerificationRoots.resolveRepositoryPath(Path.of(
                System.getProperty("modeSplit.settingsFile", "settings.gradle")));
        String settings = Files.readString(settingsPath, StandardCharsets.UTF_8);
        if ("target".equals(stage) || "target-rehearsal".equals(stage)) {
            require(Pattern.compile("(?m)^\\s*include\\s*\\(?[\"']?:?codpattern-main")
                            .matcher(settings).find(),
                    "addon target build must include the sibling main project");
        } else {
            throw new AssertionError("unknown modeSplit.verificationStage: " + stage);
        }
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

    private enum Owner {
        FUTURE_MAIN,
        ZOMBIES_ADDON,
        COMPOSITION_SHIM
    }

    private enum MatchKind {
        EXACT,
        PREFIX,
        REGEX
    }

    private record OwnershipRule(Owner owner, MatchKind kind, String expression, Pattern pattern) {
        private boolean matches(String path) {
            return switch (kind) {
                case EXACT -> path.equals(expression);
                case PREFIX -> path.startsWith(expression);
                case REGEX -> pattern.matcher(path).matches();
            };
        }
    }

    private record OwnershipManifest(List<OwnershipRule> rules) {
        private static OwnershipManifest read(Path path) throws IOException {
            require(Files.isRegularFile(path), "missing final ownership manifest: " + path);
            List<OwnershipRule> rules = new ArrayList<>();
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = rawLine.split("\\t", -1);
                require(fields.length >= 3, "invalid final ownership manifest row: " + rawLine);
                Owner owner = Owner.valueOf(fields[0].trim());
                MatchKind kind = MatchKind.valueOf(fields[1].trim());
                String expression = fields[2].trim();
                if (kind != MatchKind.REGEX) {
                    expression = expression.replace('\\', '/');
                }
                rules.add(new OwnershipRule(
                        owner,
                        kind,
                        expression,
                        kind == MatchKind.REGEX ? Pattern.compile(expression) : null));
            }
            require(!rules.isEmpty(), "final ownership manifest must contain rules");
            return new OwnershipManifest(List.copyOf(rules));
        }

        private Owner ownerOf(String rawPath) {
            String path = rawPath.replace('\\', '/');
            Owner resolved = null;
            for (OwnershipRule rule : rules) {
                if (rule.matches(path)) {
                    resolved = rule.owner();
                }
            }
            return resolved;
        }
    }
}
