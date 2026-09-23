package com.cdp.codpattern.client.gui.overlay.zombies;

import java.util.Optional;

/** Reference-space layout for doc/zom.svg, independent of the game renderer. */
final class ZombiesWeaponPanelLayout {
    static final float SVG_SCALE = 240.0F / 586.0F;
    // Compact default; 2.5% extra width accommodates the existing font.
    static final float WIDTH = 246.0F;
    static final float HEIGHT = 156.0F * SVG_SCALE;
    static final float MARGIN = 12.0F;
    // Anchor the full texture canvas, including transparent padding, not the gun silhouette.
    static final float IMAGE_RIGHT = 384.0F * SVG_SCALE;
    static final float IMAGE_CENTER_Y = 77.0F * SVG_SCALE;
    static final float IMAGE_SCALE = 0.94F;
    static final float IMAGE_WIDTH = 384.0F * SVG_SCALE * IMAGE_SCALE;
    static final float IMAGE_HEIGHT = 128.0F * SVG_SCALE * IMAGE_SCALE;
    static final float IMAGE_X = IMAGE_RIGHT - IMAGE_WIDTH;
    static final float IMAGE_Y = IMAGE_CENTER_Y - IMAGE_HEIGHT / 2.0F;
    static final float AMMO_LEFT = 386.88F * SVG_SCALE;
    static final float AMMO_RIGHT = 546.003F * SVG_SCALE + 6.0F;
    static final float CURRENT_Y = 29.8F * SVG_SCALE;
    static final float RESERVE_CENTER_Y = 117.4F * SVG_SCALE;
    static final float ICON_Y = 95.9F * SVG_SCALE;
    static final float ICON_SIZE = 43.0F * SVG_SCALE;
    // One nominal small-digit advance; keep the four-digit field and mode icon stable.
    static final float RESERVE_ICON_GAP = 6.0F * (25.2F * SVG_SCALE / 7.0F);
    static final float RESERVE_LEFT = AMMO_RIGHT - 4.0F * RESERVE_ICON_GAP;
    static final float ICON_X = RESERVE_LEFT - RESERVE_ICON_GAP - ICON_SIZE;
    static final float RARITY_FADE_END = WIDTH * 0.80F;
    static final float LEVEL_X = 17.922F * SVG_SCALE;
    static final float LEVEL_BOTTOM = 147.0F * SVG_SCALE;
    static final float BORDER_WIDTH = 5.0F * SVG_SCALE;
    static final float AMMO_HEIGHT_RATIO = 8.0F / 3.0F;
    static final float LEVEL_HEIGHT_RATIO = 23.148F / 25.2F;
    // Default ASCII digits have five visible columns and a six-unit advance.
    // Only remove the final spacing column; internal character spacing stays intact.
    private static final float DIGIT_TRAILING_SPACE = 1.0F;

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
        float currentX(int textAdvance) {
            return ammoX(textAdvance, currentScale);
        }

        float reserveX(int textAdvance) {
            return ammoX(textAdvance, reserveScale);
        }

        private static float ammoX(int textAdvance, float textScale) {
            // Calibrated for the existing bitmap font; custom fonts need their own ink bounds.
            return AMMO_RIGHT - Math.max(0, textAdvance - DIGIT_TRAILING_SPACE) * textScale;
        }

        float reserveY() {
            return RESERVE_CENTER_Y - glyphHeight * reserveScale / 2.0F;
        }

        float levelScale(int textWidth) {
            return Math.min(reserveScale * LEVEL_HEIGHT_RATIO,
                    (IMAGE_RIGHT - LEVEL_X - 6.0F) / Math.max(1, textWidth));
        }
    }
}
