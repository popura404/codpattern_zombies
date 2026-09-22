package com.cdp.codpattern.client.gui.screen.zombies.deploy;

import com.phasetranscrystal.fpsmatch.common.packet.zombies.OpenZombiesDeployToolScreenS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Three-way save/discard/cancel prompt used when leaving a dirty deployment editor. */
public final class ZombiesDeployUnsavedChangesScreen extends Screen {
    private final ZombiesDeployToolScreen parent;
    private final Runnable save;
    private final Runnable discard;
    private boolean waiting;
    private Button cancelButton;

    ZombiesDeployUnsavedChangesScreen(ZombiesDeployToolScreen parent, Runnable save, Runnable discard) {
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
            waitForResponse();
            save.run();
        }).bounds(x, y, 66, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.discard_draft"), ignored -> {
            waitForResponse();
            discard.run();
        }).bounds(x + 70, y, 66, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("gui.codpattern.zombies.deploy.cancel"), ignored ->
                onClose()).bounds(x + 140, y, 66, 20).build());
        if (waiting) {
            waitForResponse();
        }
    }

    private void waitForResponse() {
        waiting = true;
        children().forEach(child -> {
            if (child instanceof Button button) {
                button.active = button == cancelButton;
            }
        });
    }

    public void applyData(OpenZombiesDeployToolScreenS2CPacket packet) {
        // Earlier field acknowledgements must not overwrite the edit captured at close.
        if (waiting) {
            parent.applyCloseResponse(packet);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 24, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(waiting
                ? "gui.codpattern.zombies.deploy.waiting_response"
                : "gui.codpattern.zombies.deploy.unsaved_message"), width / 2, height / 2 - 6, 0xFFC9D1D9);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        parent.cancelCloseRequest();
        Minecraft.getInstance().setScreen(parent);
    }
}
