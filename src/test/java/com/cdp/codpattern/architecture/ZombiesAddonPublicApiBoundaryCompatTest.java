package com.cdp.codpattern.architecture;

import com.cdp.codpattern.app.match.model.GameModeDefinition;
import com.cdp.codpattern.app.zombies.ZombiesModeModule;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ZombiesAddonPublicApiBoundaryCompatTest {
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([^;]+);$");

    private ZombiesAddonPublicApiBoundaryCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = ModeSplitVerificationRoots.repositoryRoot();
        Path addonJava = root.resolve("src/main/java");
        List<Path> addonSources;
        try (Stream<Path> paths = Files.walk(addonJava)) {
            addonSources = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        require(!addonSources.isEmpty(), "physical addon source root contains no Java sources");

        int publicMainImports = 0;
        try (JarFile mainJar = new JarFile(ModeSplitVerificationRoots.mainDependencyJar().toFile())) {
            for (Path source : addonSources) {
                String relative = normalize(root.relativize(source));
                String text = Files.readString(source, StandardCharsets.UTF_8);
                require(!text.contains("import com.cdp.codpattern.CodPattern;")
                                && !text.contains("CodPattern.MODID")
                                && !text.contains("com.cdp.codpattern.bootstrap.CoreBootstrap")
                                && !text.contains("import com.cdp.codpattern.bootstrap."),
                        "addon source reaches back into the combined shim/core bootstrap: " + relative);

                Matcher imports = IMPORT.matcher(text);
                while (imports.find()) {
                    String imported = imports.group(1).trim();
                    JarEntry target = resolvePublishedType(mainJar, imported);
                    if (target == null) {
                        continue;
                    }
                    try (var bytecode = mainJar.getInputStream(target)) {
                      require((new ClassReader(bytecode).getAccess() & Opcodes.ACC_PUBLIC) != 0,
                            "addon source imports a non-public main type: " + relative + " -> " + imported);
                    }
                    publicMainImports++;
                }
            }
        }

        List<GameModeDefinition> definitions = ZombiesModeModule.INSTANCE.definitions();
        require(definitions.size() == 1 && "zombies".equals(definitions.get(0).gameType()),
                "Zombies definition facade must expose exactly the addon-owned Zombies definition");
        require(publicMainImports > 0, "boundary audit did not inspect any addon-to-main public API imports");

        System.out.println("PASS Phase 7 Zombies addon public-API boundary: "
                + addonSources.size() + " addon sources, " + publicMainImports + " public main imports");
    }

    private static JarEntry resolvePublishedType(JarFile mainJar, String imported) {
        String candidate = imported;
        while (!candidate.isBlank()) {
            JarEntry bytecode = mainJar.getJarEntry(candidate.replace('.', '/') + ".class");
            if (bytecode != null) {
                return bytecode;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(0, dot);
        }
        return null;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
