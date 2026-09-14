package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Three-way save/discard/cancel prompt used when leaving a dirty deployment editor. */
final class ZombiesDeployUnsavedChangesScreen extends Screen {
    private final Screen parent;
    private final Runnable save;
    private final Runnable discard;

    ZombiesDeployUnsavedChangesScreen(Screen parent, Runnable save, Runnable discard) {
        super(Component.translatable("gui.codpattern.zombies.deploy.unsaved_title"));
        this.parent = parent;
        this.save = save;
        this.discard = discard;
    }

    @Override
    protected void init() {
        int x = width / 2 - 104;
        int y = height / 2 + 18;
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.save_draft"), ignored -> {
            save.run();
            Minecraft.getInstance().setScreen(null);
        }).bounds(x, y, 66, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.discard_draft"), ignored -> {
            discard.run();
            Minecraft.getInstance().setScreen(null);
        }).bounds(x + 70, y, 66, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.cancel"), ignored ->
                Minecraft.getInstance().setScreen(parent)).bounds(x + 140, y, 66, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 24, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("gui.codpattern.zombies.deploy.unsaved_message"), width / 2, height / 2 - 6, 0xFFC9D1D9);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
