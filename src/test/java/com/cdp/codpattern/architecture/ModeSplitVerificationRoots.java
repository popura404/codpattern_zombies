package com.cdp.codpattern.architecture;

import com.cdp.codpattern.app.match.model.GameModeDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Addon-owned paths for release-dependency verification; never searches sibling repositories. */
public final class ModeSplitVerificationRoots {
    private static final Path REPOSITORY_ROOT = findRepositoryRoot();

    private ModeSplitVerificationRoots() {
    }

    public static Path repositoryRoot() {
        return REPOSITORY_ROOT;
    }

    public static List<Path> productionJavaRoots() {
        return List.of(REPOSITORY_ROOT.resolve("src/main/java"));
    }

    public static List<Path> productionResourceRoots() {
        return List.of(REPOSITORY_ROOT.resolve("src/main/resources"));
    }

    public static Path productionJavaSource(String className) {
        return requireFile(REPOSITORY_ROOT.resolve("src/main/java")
                .resolve(className.replace('.', '/') + ".java"));
    }

    public static Path testResource(String relativePath) {
        return requireFile(REPOSITORY_ROOT.resolve("src/test/resources").resolve(relativePath));
    }

    public static Path resolveRepositoryPath(Path path) {
        return path.isAbsolute() ? path.normalize() : REPOSITORY_ROOT.resolve(path).normalize();
    }

    public static Path mainDependencyJar() {
        try {
            String configured = System.getProperty("codpattern.dependencyJar", "").trim();
            Path jar = configured.isEmpty()
                    ? Path.of(GameModeDefinition.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    : Path.of(configured);
            requireFile(jar);
            if (!jar.getFileName().toString().endsWith(".jar")) {
                throw new AssertionError("Main mod must be loaded from a published dependency JAR: " + jar);
            }
            return jar.toAbsolutePath().normalize();
        } catch (java.net.URISyntaxException exception) {
            throw new AssertionError("Cannot locate the main dependency JAR", exception);
        }
    }

    private static Path requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("Missing standalone verification input: " + path);
        }
        return path;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("codpattern.test.workspace", "."))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(current.resolve(
                    "src/main/java/com/cdp/codpattern/zombiesaddon/ZombiesAddon.java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Run verification from the addon checkout or set codpattern.test.workspace");
    }
}
