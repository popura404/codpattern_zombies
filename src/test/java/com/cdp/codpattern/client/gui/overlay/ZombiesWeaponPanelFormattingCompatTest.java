package com.cdp.codpattern.client.gui.overlay.zombies;

/** Pure formatting checks for the reference card's level and ammo fields. */
public final class ZombiesWeaponPanelFormattingCompatTest {
    private ZombiesWeaponPanelFormattingCompatTest() {
    }

    public static void main(String[] args) {
        require("".equals(ZombiesHudOverlay.upgradeRoman(0)), "level zero should not render");
        require("I".equals(ZombiesHudOverlay.upgradeRoman(1)), "level one should render as I");
        require("II".equals(ZombiesHudOverlay.upgradeRoman(2)), "level two should render as II");
        require("IV".equals(ZombiesHudOverlay.upgradeRoman(4)), "level four should use subtractive notation");
        require("IX".equals(ZombiesHudOverlay.upgradeRoman(9)), "level nine should use subtractive notation");
        require("MMXXVI".equals(ZombiesHudOverlay.upgradeRoman(2026)), "large normal levels should remain Roman");
        require("000".equals(ZombiesHudOverlay.formatAmmo(0, 3)), "current ammo should be three digits");
        require("030".equals(ZombiesHudOverlay.formatAmmo(30, 3)), "current ammo should be zero padded");
        require("0120".equals(ZombiesHudOverlay.formatAmmo(120, 4)), "reserve ammo should be four digits");
        require("0000".equals(ZombiesHudOverlay.formatAmmo(-1, 4)), "negative ammo should clamp to zero");
        System.out.println("PASS zombies weapon panel formatting compat");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
