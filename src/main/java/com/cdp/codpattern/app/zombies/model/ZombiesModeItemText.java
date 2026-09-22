package com.cdp.codpattern.app.zombies.model;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ZombiesModeItemText {
    private ZombiesModeItemText() {
    }

    public static Component itemName(String translationKey) {
        return Component.translatable(translationKey, coloredItemPrefix());
    }

    public static Component applicableModeTooltip() {
        return Component.translatable("tooltip.codpattern.applicable_mode", coloredModeName());
    }

    private static MutableComponent coloredItemPrefix() {
        return coloredLabel("mode.codpattern.zombies.item_prefix");
    }

    private static MutableComponent coloredModeName() {
        return coloredLabel("mode.codpattern.zombies");
    }

    private static MutableComponent coloredLabel(String translationKey) {
        return Component.translatable(translationKey)
                .withStyle(style -> style.withColor(ZombiesClientModePresentations.ZOMBIES_ACCENT_COLOR & 0xFFFFFF));
    }
}
