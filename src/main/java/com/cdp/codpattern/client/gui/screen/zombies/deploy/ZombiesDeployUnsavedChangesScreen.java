package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Confirmation is used only to end an editing session, never to collapse it. */
public final class ZombiesDeployUnsavedChangesScreen extends Screen {
    private final ZombiesDeployToolScreen parent;
    private final Runnable confirm;
    private boolean waiting;

    ZombiesDeployUnsavedChangesScreen(ZombiesDeployToolScreen parent, Runnable confirm) {
        super(Component.translatable("gui.codpattern.zombies.deploy.unsaved_title"));
        this.parent = parent;
        this.confirm = confirm;
    }

    @Override protected void init() {
        int buttonWidth = Math.min(120, (width - 40) / 2);
        int x = width / 2 - buttonWidth - 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.editor.confirm_close"), ignored -> {
            waiting = true;
            children().forEach(child -> { if (child instanceof Button button) { button.active = false; } });
            confirm.run();
        }).bounds(x, height / 2 + 20, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.editor.return_editing"), ignored -> onClose())
                .bounds(x + buttonWidth + 8, height / 2 + 20, buttonWidth, 20).build());
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xCF12191D);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 34, 0xFFFFFFFF);
        Component message = Component.translatable(waiting ? "gui.codpattern.zombies.deploy.waiting_response" : "gui.codpattern.zombies.deploy.editor.close_warning");
        graphics.drawWordWrap(font, message, Math.max(12, width / 2 - 150), height / 2 - 12, Math.min(300, width - 24), 0xFFFFCD78);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() {
        if (!waiting) { minecraft.setScreen(parent); }
    }
    @Override public boolean isPauseScreen() { return false; }
}
