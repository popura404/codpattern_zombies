package com.cdp.codpattern.app.zombies.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModeScopedToolPresentationStaticContractCompatTest {
    private ModeScopedToolPresentationStaticContractCompatTest() {
    }

    public static void main(String[] args) throws IOException {
        String tdmPresentations = read("src/main/java/com/cdp/codpattern/app/tdm/model/TdmClientModePresentations.java");
        String tdmToolText = read("src/main/java/com/phasetranscrystal/fpsmatch/common/item/TdmToolText.java");
        String mapTool = read("src/main/java/com/phasetranscrystal/fpsmatch/common/item/MapCreatorTool.java");
        String spawnTool = read("src/main/java/com/phasetranscrystal/fpsmatch/common/item/SpawnPointTool.java");
        String zombiesPresentations = read("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/model/ZombiesClientModePresentations.java");
        String zombiesItemText = read("../zombies-addon/src/main/java/com/cdp/codpattern/app/zombies/model/ZombiesModeItemText.java");
        String zombiesTool = read("../zombies-addon/src/main/java/com/phasetranscrystal/fpsmatch/common/item/zombies/ZombiesDeployTool.java");
        String zombiesBarrierItem = read("../zombies-addon/src/main/java/com/cdp/codpattern/common/block/ZombiesRedPlayerBarrierItem.java");

        requireContains(tdmPresentations, "FRONTLINE_ACCENT_COLOR = 0xFF62F08A",
                "Frontline item labels must reuse the canonical Frontline accent color");
        requireContains(tdmPresentations, "TEAM_DEATHMATCH_ACCENT_COLOR = 0xFF5FC7C3",
                "Tactical Team Deathmatch item labels must reuse the canonical mode accent color");
        requireContains(tdmToolText, "TdmClientModePresentations.TEAM_DEATHMATCH_ACCENT_COLOR",
                "TDM tool labels must use the Tactical Team Deathmatch color");
        requireContains(tdmToolText, "TdmClientModePresentations.FRONTLINE_ACCENT_COLOR",
                "TDM tool labels must use the Frontline color");
        requireContains(tdmToolText, "tooltip.codpattern.applicable_modes",
                "TDM tools must expose their applicable modes in the tooltip");
        requireContains(mapTool, "TdmToolText.itemName(\"item.codpattern.map_creator_tool\")",
                "map creator tool must use the two-mode display name");
        requireContains(mapTool, "TdmToolText.applicableModesTooltip()",
                "map creator tool must show applicable modes");
        requireContains(spawnTool, "TdmToolText.itemName(\"item.codpattern.spawn_point_tool\")",
                "spawn point tool must use the two-mode display name");
        requireContains(spawnTool, "TdmToolText.applicableModesTooltip()",
                "spawn point tool must show applicable modes");

        requireContains(zombiesPresentations, "ZOMBIES_ACCENT_COLOR = 0xFF9B2F2F",
                "Zombies item labels must reuse the canonical Zombies accent color");
        requireContains(zombiesItemText, "ZombiesClientModePresentations.ZOMBIES_ACCENT_COLOR",
                "Zombies item labels must use the Zombies color");
        requireContains(zombiesItemText, "tooltip.codpattern.applicable_mode",
                "Zombies items must expose their applicable mode in the tooltip");
        requireContains(zombiesTool, "ZombiesModeItemText.itemName(\"item.codpattern.zombies_deploy_tool\")",
                "Zombies deploy tool must use the mode-colored display name");
        requireContains(zombiesTool, "ZombiesModeItemText.applicableModeTooltip()",
                "Zombies deploy tool must show its applicable mode");
        requireContains(zombiesBarrierItem, "ZombiesModeItemText.itemName(\"item.codpattern.zombies_red_player_barrier\")",
                "Zombies red barrier must use the mode-colored display name");
        requireContains(zombiesBarrierItem, "ZombiesModeItemText.applicableModeTooltip()",
                "Zombies red barrier must show its applicable mode");

        for (String locale : new String[]{"en_us", "zh_cn", "zh_tw", "ja_jp"}) {
            String mainLanguage = read("src/main/resources/assets/codpattern/lang/" + locale + ".json");
            requireContains(mainLanguage, "\"mode.codpattern.teamdeathmatch.tool_name\"",
                    "Tactical Team Deathmatch tool name must be localized in " + locale);
            requireContains(mainLanguage, "\"mode.codpattern.frontline.tool_name\"",
                    "Frontline tool name must be localized in " + locale);
            requireContains(mainLanguage, "\"tooltip.codpattern.applicable_modes\"",
                    "TDM applicable-modes tooltip must be localized in " + locale);

            String zombiesLanguage = read("../zombies-addon/src/main/resources/assets/codpattern_zombies/lang/" + locale + ".json");
            requireContains(zombiesLanguage, "\"item.codpattern.zombies_red_player_barrier\"",
                    "Zombies red barrier item name must be localized in " + locale);
            requireContains(zombiesLanguage, "\"tooltip.codpattern.applicable_mode\"",
                    "Zombies applicable-mode tooltip must be localized in " + locale);
        }

        System.out.println("PASS mode-scoped tool presentation static contract compat");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static void requireContains(String text, String expected, String message) {
        if (!text.contains(expected)) {
            throw new AssertionError(message + ": missing `" + expected + "`");
        }
    }
}
