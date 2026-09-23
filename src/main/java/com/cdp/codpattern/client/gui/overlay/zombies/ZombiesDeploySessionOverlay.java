package com.cdp.codpattern.client.gui.overlay.zombies;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployDraft;
import com.cdp.codpattern.client.zombies.ZombiesDeployClientState;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Persistent deployment controls plus a short-lived result, without chat/action-bar duplication. */
public final class ZombiesDeploySessionOverlay {
    private ZombiesDeploySessionOverlay() { }

    public static void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != null || player == null || !(player.getMainHandItem().getItem() instanceof ZombiesDeployTool)) { return; }
        var snapshot = ZombiesDeployClientState.snapshot();
        if (snapshot == null) { return; }
        boolean registration = ZombiesDeployDraft.STAGE_MAP_REGISTRATION.equals(snapshot.workspaceStage());
        int width = Math.min(360, screenWidth - 24);
        int height = 70, left = 12, top = screenHeight - height - 12;
        graphics.fill(left, top, left + width, top + height, 0xB010181B);
        graphics.fill(left, top, left + 3, top + height, 0xFF7FD6A0);
        String type = snapshot.objectTypes().stream().filter(option -> option.key().equals(snapshot.selectedObjectType()))
                .map(option -> Component.translatable(option.labelKey()).getString()).findFirst().orElse("");
        String map = snapshot.selectedMap().isBlank() ? tr("editor.registration") : snapshot.selectedMap();
        draw(graphics, font, map + "  ·  " + (registration ? tr("editor.registration") : type), left, top + 6, width, 0xFFFFFFFF);
        draw(graphics, font, tr(registration ? "editor.capture_hint" : "editor.world_controls"), left, top + 20, width, 0xFFC9D1D9);
        draw(graphics, font, tr(snapshot.dirty() ? "editor.world_unsaved" : "editor.world_shortcuts"), left, top + 34, width, 0xFFFFCD78);
        if (System.currentTimeMillis() - ZombiesDeployClientState.noticeAt() < 4500 && !snapshot.statusKey().isBlank()) {
            String message = Component.translatable(snapshot.statusKey(), snapshot.statusDetail()).getString();
            draw(graphics, font, message, left, top + 50, width, 0xFFAED7FF);
        }
    }
    private static String tr(String key) { return Component.translatable("gui.codpattern.zombies.deploy." + key).getString(); }
    private static void draw(GuiGraphics graphics, Font font, String value, int x, int y, int width, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value, width - 20), x + 10, y, color, false);
    }
}
