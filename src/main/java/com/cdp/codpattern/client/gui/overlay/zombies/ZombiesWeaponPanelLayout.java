package com.cdp.codpattern.client.gui.overlay.zombies;

import java.util.Optional;

/** Reference-space layout for doc/zom.svg, independent of the game renderer. */
final class ZombiesWeaponPanelLayout {
    static final float SVG_SCALE = 240.0F / 586.0F;
    // Compact default; 2.5% extra width accommodates the existing font.
    static final float WIDTH = 246.0F;
    static final float HEIGHT = 156.0F * SVG_SCALE;
    static final float MARGIN = 12.0F;
    static final float IMAGE_X = 0.0F;
    static final float IMAGE_Y = 13.0F * SVG_SCALE;
    static final float IMAGE_WIDTH = 384.0F * SVG_SCALE;
    static final float IMAGE_HEIGHT = 128.0F * SVG_SCALE;
    static final float AMMO_LEFT = 386.88F * SVG_SCALE;
    static final float AMMO_RIGHT = 546.003F * SVG_SCALE + 6.0F;
    static final float CURRENT_Y = 29.8F * SVG_SCALE;
    static final float RESERVE_CENTER_Y = 117.4F * SVG_SCALE;
    static final float ICON_X = AMMO_LEFT;
    static final float ICON_Y = 95.9F * SVG_SCALE;
    static final float ICON_SIZE = 43.0F * SVG_SCALE;
    static final float RESERVE_LEFT = ICON_X + ICON_SIZE + 6.0F;
    static final float LEVEL_X = 17.922F * SVG_SCALE;
    static final float LEVEL_BOTTOM = 147.0F * SVG_SCALE;
    static final float BORDER_WIDTH = 5.0F * SVG_SCALE;
    static final float AMMO_HEIGHT_RATIO = 8.0F / 3.0F;
    static final float LEVEL_HEIGHT_RATIO = 23.148F / 25.2F;

    private ZombiesWeaponPanelLayout() {
    }

    static Optional<Placement> create(int screenWidth, int screenHeight, float referenceScale,
                                      int lineHeight, int digitAdvance, int currentDigits, int reserveDigits) {
        if (!Float.isFinite(referenceScale) || referenceScale <= 0 || digitAdvance <= 0 || lineHeight <= 2) {
            return Optional.empty();
        }
        float availableWidth = Math.min(screenWidth - MARGIN * 2, screenWidth * 0.30F);
        float availableHeight = Math.min(screenHeight - MARGIN * 2, screenHeight * 0.20F);
        float scale = Math.min(referenceScale, Math.min(availableWidth / WIDTH, availableHeight / HEIGHT));
        if (scale < referenceScale * 0.45F) {
            return Optional.empty();
        }
        // The default bitmap font has seven visible rows in a nine-unit line.
        // Keep the existing font; resource-pack fonts may need visual calibration.
        float glyphHeight = lineHeight - 2.0F;
        float reserveScale = 25.2F * SVG_SCALE / glyphHeight;
        float currentScale = reserveScale * AMMO_HEIGHT_RATIO;
        // Bucket by digit count, not the current string's width, to avoid shot-to-shot resizing.
        float currentWidth = Math.max(3, currentDigits) * (float) digitAdvance * currentScale;
        float reserveWidth = Math.max(4, reserveDigits) * (float) digitAdvance * reserveScale;
        float fit = Math.min(1.0F, Math.min((AMMO_RIGHT - AMMO_LEFT) / currentWidth,
                (AMMO_RIGHT - RESERVE_LEFT) / reserveWidth));
        reserveScale *= fit;
        currentScale *= fit;
        // referenceScale = 2 / GUI scale. Do not replace TaCZ with illegible text.
        if (glyphHeight * reserveScale * scale * 2.0F / referenceScale < 6.0F) {
            return Optional.empty();
        }
        return Optional.of(new Placement(screenWidth - MARGIN - WIDTH * scale,
                screenHeight - MARGIN - HEIGHT * scale, scale, glyphHeight, currentScale, reserveScale));
    }

    record Placement(float left, float top, float scale, float glyphHeight,
                     float currentScale, float reserveScale) {
        float reserveY() {
            return RESERVE_CENTER_Y - glyphHeight * reserveScale / 2.0F;
        }

        float levelScale(int textWidth) {
            return Math.min(reserveScale * LEVEL_HEIGHT_RATIO,
                    (IMAGE_WIDTH - LEVEL_X - 6.0F) / Math.max(1, textWidth));
        }
    }
}
