package com.cdp.codpattern.client.gui.overlay.zombies;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Integer GUI-coordinate layout, independent of Minecraft and texture rendering. */
final class ZombiesSodaHudLayout {
    private static final int MAX_ICONS = 6;
    private static final int BOTTOM_MARGIN = 12;
    private static final int CLEARANCE = 6;
    private static final int[] ICON_SIZES = {32, 24, 16};

    private ZombiesSodaHudLayout() {
    }

    static Optional<Placement> create(int screenWidth, int screenHeight, int iconCount,
                                      List<Bounds> occupied) {
        if (screenWidth <= 0 || screenHeight <= 0 || iconCount <= 0 || iconCount > MAX_ICONS) {
            return Optional.empty();
        }
        int minimumTop = screenHeight / 2 + screenHeight % 2;
        for (int iconSize : ICON_SIZES) {
            int gap = iconSize == 32 ? 6 : 4;
            int width = iconCount * iconSize + (iconCount - 1) * gap;
            if (width > screenWidth) {
                continue;
            }
            int left = (screenWidth - width) / 2;
            long top = (long) screenHeight - BOTTOM_MARGIN - iconSize;
            while (top >= minimumTop) {
                Bounds row = new Bounds(left, (int) top, left + width, (int) top + iconSize);
                long nextTop = top;
                for (Bounds other : occupied) {
                    if (overlapsWithClearance(row, other)) {
                        // Every conflicting rectangle must be cleared above. Taking the
                        // minimum keeps the result independent of occupied-list order.
                        nextTop = Math.min(nextTop, (long) other.top() - CLEARANCE - iconSize);
                    }
                }
                if (nextTop == top) {
                    return Optional.of(new Placement(left, (int) top, iconSize, gap, iconCount));
                }
                top = nextTop;
            }
        }
        return Optional.empty();
    }

    private static boolean overlapsWithClearance(Bounds row, Bounds other) {
        return other.left() < other.right() && other.top() < other.bottom()
                && (long) row.left() < (long) other.right() + CLEARANCE
                && (long) row.right() > (long) other.left() - CLEARANCE
                && (long) row.top() < (long) other.bottom() + CLEARANCE
                && (long) row.bottom() > (long) other.top() - CLEARANCE;
    }

    /** Half-open bounds: right and bottom are the first coordinates outside the rectangle. */
    record Bounds(int left, int top, int right, int bottom) {
    }

    record Placement(int left, int top, int iconSize, int gap, int count) {
        int width() {
            return count * iconSize + (count - 1) * gap;
        }

        int iconX(int index) {
            Objects.checkIndex(index, count);
            return left + index * (iconSize + gap);
        }

        Bounds bounds() {
            return new Bounds(left, top, left + width(), top + iconSize);
        }
    }
}
