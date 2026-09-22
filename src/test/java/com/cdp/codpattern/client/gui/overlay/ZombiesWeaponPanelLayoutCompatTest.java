package com.cdp.codpattern.client.gui.overlay.zombies;

/** Geometry checks need no Minecraft bootstrap or graphics context. */
public final class ZombiesWeaponPanelLayoutCompatTest {
    public static void main(String[] args) {
        for (int width : new int[]{800, 1024, 1280, 1920, 2560, 3840}) {
            int height = width * 9 / 16;
            Float physicalWidth = null;
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                int guiWidth = width / guiScale;
                int guiHeight = height / guiScale;
                var layout = ZombiesWeaponPanelLayout.create(guiWidth, guiHeight, 2.0F / guiScale,
                        9, 6, 3, 4).orElseThrow();
                float cardWidth = ZombiesWeaponPanelLayout.WIDTH * layout.scale();
                float cardHeight = ZombiesWeaponPanelLayout.HEIGHT * layout.scale();
                require(cardWidth <= guiWidth * 0.30F + 0.01F, "card exceeds 30% viewport width");
                require(cardHeight <= guiHeight * 0.20F + 0.01F, "card exceeds 20% viewport height");
                require(layout.left() >= 0 && layout.top() >= 0, "card outside viewport");
                require(layout.left() + cardWidth <= guiWidth, "card exceeds right edge");
                require(layout.top() + cardHeight <= guiHeight, "card exceeds bottom edge");
                checkAmmoBounds(layout, 3, 4, 6);
                if (physicalWidth != null) {
                    require(Math.abs(cardWidth * guiScale - physicalWidth) < 1.0F,
                            "GUI scale changes physical card size");
                }
                physicalWidth = cardWidth * guiScale;
            }
        }
        var normal = ZombiesWeaponPanelLayout.create(1920, 1080, 2, 9, 6, 3, 4).orElseThrow();
        near(normal.glyphHeight() * normal.currentScale(), 67.2F * 240 / 586,
                "normal magazine is unnecessarily shrunk");
        for (int digits = 4; digits <= 10; digits++) {
            var overflow = ZombiesWeaponPanelLayout.create(1920, 1080, 2, 9, 6,
                    digits, digits).orElseThrow();
            checkAmmoBounds(overflow, digits, digits, 6);
            require(overflow.currentScale() <= normal.currentScale(), "overflow enlarged digits");
        }
        // Wider resource-pack digits must fit as one group, preserving the hierarchy.
        var wideFont = ZombiesWeaponPanelLayout.create(1920, 1080, 2, 12, 10, 3, 4).orElseThrow();
        checkAmmoBounds(wideFont, 3, 4, 10);
        require(ZombiesWeaponPanelLayout.create(200, 100, 2, 9, 6, 3, 4).isEmpty(),
                "unreadable viewport must leave TaCZ available");
        require(ZombiesWeaponPanelLayout.create(1280, 720, 2, 9, 6, 10, 10).isEmpty(),
                "unreadable long ammo must leave TaCZ available");
        for (float scale : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            require(ZombiesWeaponPanelLayout.create(1920, 1080, scale, 9, 6, 3, 4).isEmpty(),
                    "invalid scale must not render");
        }
        System.out.println("PASS zombies weapon panel layout: compact viewports, GUI scales, 8:3 ratio, overflow and fallback");
    }

    private static void checkAmmoBounds(ZombiesWeaponPanelLayout.Placement layout,
                                         int currentDigits, int reserveDigits, int advance) {
        near(layout.currentScale() / layout.reserveScale(), 8.0F / 3, "ammo height ratio");
        float currentLeft = ZombiesWeaponPanelLayout.AMMO_RIGHT - currentDigits * advance * layout.currentScale();
        float reserveLeft = ZombiesWeaponPanelLayout.AMMO_RIGHT - reserveDigits * advance * layout.reserveScale();
        require(currentLeft >= ZombiesWeaponPanelLayout.AMMO_LEFT - 0.01F, "magazine overlaps gun");
        require(reserveLeft >= ZombiesWeaponPanelLayout.RESERVE_LEFT - 0.01F, "reserve overlaps icon");
        require(ZombiesWeaponPanelLayout.CURRENT_Y + layout.glyphHeight() * layout.currentScale()
                        < layout.reserveY(), "ammo rows overlap");
        near(layout.reserveY() + layout.glyphHeight() * layout.reserveScale() / 2,
                ZombiesWeaponPanelLayout.ICON_Y + ZombiesWeaponPanelLayout.ICON_SIZE / 2,
                "reserve is not centered with mode icon");
        float longLevelScale = layout.levelScale(2000);
        require(ZombiesWeaponPanelLayout.LEVEL_X + 2000 * longLevelScale
                < ZombiesWeaponPanelLayout.IMAGE_WIDTH, "long level overlaps ammo region");
    }

    private static void near(float actual, float expected, String message) {
        require(Math.abs(actual - expected) < 0.001F, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
