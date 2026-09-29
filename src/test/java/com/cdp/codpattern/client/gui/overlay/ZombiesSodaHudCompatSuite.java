package com.cdp.codpattern.client.gui.overlay.zombies;

import com.cdp.codpattern.client.zombies.ClientZombiesSodaStateCompatTest;

/** Focused behavioral checks for the soda HUD. */
public final class ZombiesSodaHudCompatSuite {
    private ZombiesSodaHudCompatSuite() {
    }

    public static void main(String[] args) {
        ClientZombiesSodaStateCompatTest.main(args);
        ZombiesSodaHudLayoutCompatTest.main(args);
    }
}
