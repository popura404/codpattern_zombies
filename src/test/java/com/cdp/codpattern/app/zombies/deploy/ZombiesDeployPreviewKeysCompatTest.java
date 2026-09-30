package com.cdp.codpattern.app.zombies.deploy;

import java.util.UUID;

public final class ZombiesDeployPreviewKeysCompatTest {
    private static final UUID PLAYER = UUID.fromString("8c56a7e6-5b9f-4d68-8f0c-6d93c48c69b1");

    private ZombiesDeployPreviewKeysCompatTest() {
    }

    public static void main(String[] args) {
        for (String type : new String[]{"weapon_wall", "ammo_box", "armor_station", "power_switch",
                "soda_machine", "ultimate_machine", "mystery_box"}) {
            String object = ZombiesDeployPreviewKeys.objectKey(PLAYER, type, 2);
            String draft = ZombiesDeployPreviewKeys.draftKey(PLAYER, type);
            require(ZombiesDeployPreviewKeys.modelObjectType(object, PLAYER).orElseThrow().equals(type),
                    "saved and staged objects must retain their model type");
            require(ZombiesDeployPreviewKeys.modelObjectType(draft, PLAYER).orElseThrow().equals(type),
                    "active draft anchors must retain their model type");
            require(object.startsWith(ZombiesDeployPreviewKeys.prefix(PLAYER))
                            && draft.startsWith(ZombiesDeployPreviewKeys.prefix(PLAYER)),
                    "prefix cleanup must remove object and active-draft models together");
            require(ZombiesDeployPreviewKeys.modelObjectType(object + ":slotA:pos", PLAYER).isEmpty(),
                    "capture markers must not draw duplicate models");
            require(ZombiesDeployPreviewKeys.modelObjectType(draft + ":slotB:interaction", PLAYER).isEmpty(),
                    "interaction positions must not draw extra models");
            require(ZombiesDeployPreviewKeys.modelObjectType(object, new UUID(0, 0)).isEmpty(),
                    "another player's debug markers must never become our deployment models");
        }
        for (String suffix : new String[]{"map", "map:draft", "nearest:ammo_box", "singleton:power_switch",
                "object:ammo_box:-1", "object:ammo_box:not_an_index", "object::0", "draft", "draft:"}) {
            require(ZombiesDeployPreviewKeys.modelObjectType(ZombiesDeployPreviewKeys.prefix(PLAYER) + suffix, PLAYER).isEmpty(),
                    "non-anchor point must not render a block: " + suffix);
        }
        require(ZombiesDeployPreviewKeys.modelObjectType(null, PLAYER).isEmpty(), "null key must be ignored");
        require(ZombiesDeployPreviewKeys.modelObjectType("anything", null).isEmpty(), "missing player must be ignored");
        System.out.println("PASS zombies deployment preview identities and cleanup isolation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
