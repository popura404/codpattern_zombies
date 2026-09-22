package com.cdp.codpattern.app.zombies.model;

import com.cdp.codpattern.app.match.model.ClientModePresentation;
import net.minecraft.resources.ResourceLocation;

public final class ZombiesClientModePresentations {
    public static final int ZOMBIES_ACCENT_COLOR = 0xFF9B2F2F;

    private ZombiesClientModePresentations() {
    }

    public static ClientModePresentation zombiesPresentation() {
        return new ClientModePresentation(
                new ResourceLocation("codpattern", "textures/gui/modes/zombies_preview.png"),
                1920,
                1080,
                ZOMBIES_ACCENT_COLOR,
                "screen.codpattern.mode_select.hover_zombies",
                "zombies");
    }
}
