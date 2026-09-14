package com.cdp.codpattern.client.gui.overlay.zombies;

import com.cdp.codpattern.client.zombies.ZombiesDeployClientState;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Compact persistent world hint for the active Zombies deployment session. */
public final class ZombiesDeploySessionOverlay {
    private ZombiesDeploySessionOverlay() {
    }

    public static void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof ZombiesDeployTool)) {
            return;
        }
        ZombiesDeployClientState.State state = ZombiesDeployClientState.current();
        if (!state.active() || state.map().isBlank()) {
            return;
        }
        int width = Math.min(300, Math.max(180, screenWidth - 24));
        int height = 44;
        int left = 12;
        int top = screenHeight - height - 12;
        graphics.fill(left, top, left + width, top + height, 0xB010141A);
        graphics.fill(left, top, left + 3, top + height, 0xFF4BB56C);
        graphics.drawString(font, state.map() + "  " + state.objectType(), left + 10, top + 6, 0xFFFFFFFF, true);
        String progress = state.count() + (state.required() > 0 ? "/" + state.required() : "") + "  " + state.step();
        graphics.drawString(font, progress, left + 10, top + 18, 0xFFC9D1D9, false);
        String controls = Component.translatable(state.dirty()
                ? "gui.codpattern.zombies.deploy.hud.unsaved"
                : "gui.codpattern.zombies.deploy.hud.controls").getString();
        graphics.drawString(font, controls, left + 10, top + 30, state.dirty() ? 0xFFFFD166 : 0xFF86EFAC, false);
    }
}
