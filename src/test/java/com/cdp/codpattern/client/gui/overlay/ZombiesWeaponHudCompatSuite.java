package com.cdp.codpattern.client.gui.overlay.zombies;

import com.cdp.codpattern.client.gui.overlay.ZombiesHudOverlayStaticContractCompatTest;

/** Focused checks for changes to the held-weapon HUD. */
public final class ZombiesWeaponHudCompatSuite {
    public static void main(String[] args) throws Exception {
        ZombiesWeaponPanelLayoutCompatTest.main(args);
        ZombiesWeaponPanelFormattingCompatTest.main(args);
        ZombiesHudOverlayStaticContractCompatTest.main(args);
    }
}
