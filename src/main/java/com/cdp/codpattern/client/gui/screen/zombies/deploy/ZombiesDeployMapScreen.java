package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;

/** Map selection and registration, sharing the active editing session with the object page. */
public final class ZombiesDeployMapScreen extends ZombiesDeployToolScreen {
    public ZombiesDeployMapScreen(OpenZombiesDeployToolScreenS2CPacket packet) { super(packet); }
    ZombiesDeployMapScreen(ZombiesDeployEditorSession session) { super(session); }
    @Override protected boolean mapPage() { return true; }
}
