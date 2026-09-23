package com.cdp.codpattern.client.zombies;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraft;
import com.cdp.codpattern.client.gui.screen.zombies.deploy.ZombiesDeployMapScreen;
import com.cdp.codpattern.client.gui.screen.zombies.deploy.ZombiesDeployToolScreen;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;
import net.minecraft.client.Minecraft;

/** Holds the collapsed editor as well as routing responses to both pages. */
public final class ZombiesDeployClientActionHandler {
    private static ZombiesDeployToolScreen editor;
    private static Object connection;
    private ZombiesDeployClientActionHandler() { }

    public static void setEditor(ZombiesDeployToolScreen screen) { editor = screen; }
    public static void clearEditor(ZombiesDeployToolScreen screen) {
        if (editor == screen) { editor = null; }
    }

    public static void handle(Object payload) {
        if (!(payload instanceof OpenZombiesDeployToolScreenS2CPacket packet)) {
            throw new IllegalArgumentException("Unexpected Zombies deploy screen payload: " + payload);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (connection != minecraft.getConnection()) {
            editor = null;
            connection = minecraft.getConnection();
            ZombiesDeployClientState.reset();
        }
        if (packet.openScreen()) {
            if (editor == null) {
                editor = ZombiesDeployDraft.STAGE_MAP_REGISTRATION.equals(packet.snapshot().workspaceStage())
                        ? new ZombiesDeployMapScreen(packet) : new ZombiesDeployToolScreen(packet);
                ZombiesDeployClientState.update(packet.snapshot());
                minecraft.setScreen(editor);
            } else { editor.reopen(packet); }
        } else if (editor != null) {
            editor.applyData(packet);
        } else {
            ZombiesDeployClientState.update(packet.snapshot());
        }
    }
}
