package com.cdp.codpattern.app.zombies.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FpsmToolCreativeTabStaticContractCompatTest {
    private static final Path CORE_BOOTSTRAP = Path.of("src/main/java/com/cdp/codpattern/bootstrap/CoreBootstrap.java");
    private static final Path ZOMBIES_BOOTSTRAP = Path.of("src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesBootstrap.java");
    private static final Path CREATIVE_TAB_REGISTER = Path.of(
            "src/main/java/com/phasetranscrystal/fpsmatch/common/item/FPSMCreativeModeTabRegister.java");
    private static final Path ITEM_REGISTER = Path.of("src/main/java/com/phasetranscrystal/fpsmatch/common/item/FPSMItemRegister.java");
    private static final Path ZOMBIES_ITEM_REGISTER = Path.of(
            "src/main/java/com/cdp/codpattern/app/zombies/bootstrap/ZombiesItemRegister.java");
    private static final Path ZOMBIES_BLOCK_REGISTER = Path.of(
            "src/main/java/com/cdp/codpattern/common/block/CodPatternBlockRegister.java");

    private FpsmToolCreativeTabStaticContractCompatTest() {
    }

    public static void main(String[] args) throws IOException {
        String zombiesBootstrap = Files.readString(ZOMBIES_BOOTSTRAP);
        String zombiesItemRegister = Files.readString(ZOMBIES_ITEM_REGISTER);
        String zombiesBlockRegister = Files.readString(ZOMBIES_BLOCK_REGISTER);
        requireContains(zombiesBootstrap, "modEventBus.addListener(ZombiesItemRegister::onBuildCreativeModeTabContents);",
                "Zombies deploy tool creative tab listener must be registered by the addon bootstrap");
        requireContains(zombiesBootstrap, "ZombiesItemRegister.ITEMS.register(modEventBus);",
                "Zombies deploy tool item must be registered by the addon bootstrap");
        requireContains(zombiesItemRegister, "\"zombies_deploy_tool\"",
                "zombies deploy tool item id must remain registered");
        String zombiesCreativeTabBody = methodBody(
                zombiesItemRegister,
                "public static void onBuildCreativeModeTabContents");
        String zombiesBlockCreativeTabBody = methodBody(
                zombiesBlockRegister,
                "public static void onBuildCreativeModeTabContents");
        requireContains(zombiesCreativeTabBody, "FPSMCreativeModeTabRegister.CODPATTERN_TOOLS_AND_ITEMS_KEY.equals(event.getTabKey())",
                "Zombies deploy tool must be added to the COD Pattern tools and items tab");
        requireContains(zombiesCreativeTabBody, "event.accept(ZOMBIES_DEPLOY_TOOL);",
                "zombies deploy tool must appear in the creative tab");
        requireContains(zombiesBlockCreativeTabBody, "FPSMCreativeModeTabRegister.CODPATTERN_TOOLS_AND_ITEMS_KEY.equals(event.getTabKey())",
                "Zombies block items must target the COD Pattern tools and items tab");
        requireContains(zombiesBlockCreativeTabBody, "event.accept(ZOMBIES_RED_PLAYER_BARRIER_ITEM);",
                "Zombies red player barrier must appear in the COD Pattern tools and items tab");
        requireAbsent(zombiesCreativeTabBody, "hasPermissions",
                "Zombies deploy tool must not be hidden behind the operator-items permission toggle");

        System.out.println("PASS FPSM tool creative tab static contract compat");
    }

    /** Audits implementation details only when the main mod checkout is explicitly supplied. */
    public static void mainSourceContracts(Path mainSourceRoot) throws IOException {
        String coreBootstrap = Files.readString(mainSourceRoot.resolve(CORE_BOOTSTRAP));
        String creativeTabRegister = Files.readString(mainSourceRoot.resolve(CREATIVE_TAB_REGISTER));
        String itemRegister = Files.readString(mainSourceRoot.resolve(ITEM_REGISTER));

        requireContains(coreBootstrap, "modEventBus.addListener(FPSMItemRegister::onBuildCreativeModeTabContents);",
                "FPSM tools creative tab listener must be registered on the mod event bus");
        requireContains(coreBootstrap, "FPSMItemRegister.ITEMS.register(modEventBus);",
                "FPSM tool items must be registered on the mod event bus");
        requireContains(coreBootstrap, "FPSMCreativeModeTabRegister.CREATIVE_MODE_TABS.register(modEventBus);",
                "COD Pattern creative tab must be registered on the mod event bus");

        requireContains(itemRegister, "\"map_creator_tool\"",
                "map creator tool item id must remain registered");
        requireContains(itemRegister, "\"spawn_point_tool\"",
                "spawn point tool item id must remain registered");
        requireAbsent(itemRegister, "\"zombies_deploy_tool\"",
                "generic FPSM items must not own the Zombies deploy tool");
        requireContains(creativeTabRegister, "Registries.CREATIVE_MODE_TAB",
                "COD Pattern tools and items tab must use the creative-mode-tab registry");
        requireContains(creativeTabRegister, "\"tools_and_items\"",
                "COD Pattern tools and items tab id must remain stable");
        requireContains(creativeTabRegister, "itemGroup.codpattern.tools_and_items",
                "COD Pattern tools and items tab must have a localized title");
        requireContains(creativeTabRegister, "FPSMItemRegister.MAP_CREATOR_TOOL.get()",
                "COD Pattern tools and items tab must use a stable main-owned icon");
        String creativeTabBody = methodBody(itemRegister, "public static void onBuildCreativeModeTabContents");
        requireContains(creativeTabBody, "FPSMCreativeModeTabRegister.CODPATTERN_TOOLS_AND_ITEMS_KEY.equals(event.getTabKey())",
                "FPSM tools must be added to the COD Pattern tools and items tab");
        requireContains(creativeTabBody, "event.accept(MAP_CREATOR_TOOL);",
                "map creator tool must appear in the creative tab");
        requireContains(creativeTabBody, "event.accept(SPAWN_POINT_TOOL);",
                "spawn point tool must appear in the creative tab");
        requireAbsent(creativeTabBody, "hasPermissions",
                "FPSM tools must not be hidden behind the operator-items permission toggle");

        for (String locale : new String[]{"en_us", "zh_cn", "zh_tw", "ja_jp"}) {
            String language = Files.readString(mainSourceRoot.resolve(
                    "src/main/resources/assets/codpattern/lang/" + locale + ".json"));
            requireContains(language, "\"itemGroup.codpattern.tools_and_items\"",
                    "COD Pattern tools and items tab title must be localized in " + locale);
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("missing method `" + signature + "`");
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            throw new AssertionError("missing method body `" + signature + "`");
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unterminated method `" + signature + "`");
    }

    private static void requireContains(String text, String expected, String message) {
        if (!text.contains(expected)) {
            throw new AssertionError(message + ": missing `" + expected + "`");
        }
    }

    private static void requireAbsent(String text, String unexpected, String message) {
        if (text.contains(unexpected)) {
            throw new AssertionError(message + ": found `" + unexpected + "`");
        }
    }
}
