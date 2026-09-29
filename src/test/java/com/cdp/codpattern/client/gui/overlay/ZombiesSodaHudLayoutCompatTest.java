package com.cdp.codpattern.client.gui.overlay.zombies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.cdp.codpattern.client.gui.overlay.zombies.ZombiesSodaHudLayout.Bounds;
import com.cdp.codpattern.client.gui.overlay.zombies.ZombiesSodaHudLayout.Placement;

/** Geometry checks need no Minecraft bootstrap or graphics context. */
public final class ZombiesSodaHudLayoutCompatTest {
    public static void main(String[] args) {
        checkDefaultRows();
        checkCompactFallbacks();
        checkObstacles();
        checkViewportProperties();
        System.out.println("PASS zombies soda HUD layout: centered rows, compact fallbacks, clearance, "
                + "obstacle ordering, lowest placement and viewport limits");
    }

    private static void checkDefaultRows() {
        require(ZombiesSodaHudLayout.create(800, 450, 0, List.of()).isEmpty(),
                "zero effects must not reserve a row");
        int[] counts = {1, 2, 6};
        int[] widths = {32, 70, 222};
        int[] starts = {384, 365, 289};
        for (int i = 0; i < counts.length; i++) {
            Placement row = create(800, 450, counts[i], List.of());
            require(row.width() == widths[i] && row.left() == starts[i], "default row centering");
            require(row.top() == 406 && row.iconSize() == 32 && row.gap() == 6,
                    "default row dimensions and bottom margin");
            require(row.iconX(0) == row.left(), "first icon must start at the row edge");
            require(row.iconX(counts[i] - 1) + row.iconSize() == row.bounds().right(),
                    "last icon must end at the row edge");
            for (int index = 1; index < counts[i]; index++) {
                require(row.iconX(index) - row.iconX(index - 1) == 38,
                        "icons must have equal spacing without empty slots");
            }
            Placement oddWidth = create(801, 450, counts[i], List.of());
            require(oddWidth.left() == row.left(), "odd-width viewport must round centering down");
        }
        for (int count : new int[]{-1, 7, Integer.MAX_VALUE}) {
            require(ZombiesSodaHudLayout.create(800, 450, count, List.of()).isEmpty(),
                    "unsupported icon counts must not render");
        }
        require(ZombiesSodaHudLayout.create(0, 450, 1, List.of()).isEmpty(), "zero-width viewport");
        require(ZombiesSodaHudLayout.create(800, -1, 1, List.of()).isEmpty(), "negative-height viewport");
    }

    private static void checkCompactFallbacks() {
        Placement full = create(222, 200, 6, List.of());
        require(full.iconSize() == 32 && full.width() == 222, "exact full-size fit");
        Placement compact = create(164, 200, 6, List.of());
        require(compact.iconSize() == 24 && compact.gap() == 4 && compact.width() == 164,
                "24-pixel width fallback");
        Placement smallest = create(116, 200, 6, List.of());
        require(smallest.iconSize() == 16 && smallest.gap() == 4 && smallest.width() == 116,
                "16-pixel width fallback");
        require(ZombiesSodaHudLayout.create(115, 200, 6, List.of()).isEmpty(),
                "too-narrow viewport must hide the row");
        require(create(800, 88, 1, List.of()).iconSize() == 32, "full-size lower-half exact fit");
        require(create(800, 80, 1, List.of()).iconSize() == 24, "24-pixel height fallback");
        require(create(800, 64, 1, List.of()).iconSize() == 16, "16-pixel height fallback");
        require(create(800, 56, 1, List.of()).top() == 28, "lower-half boundary is inclusive");
        require(ZombiesSodaHudLayout.create(800, 55, 1, List.of()).isEmpty(),
                "odd viewport height must not round the lower-half boundary down");

        // A large row can clear this edge panel by moving up. The smaller row could
        // stay lower, but keeping the artwork at its preferred size takes priority.
        Placement preferredSize = create(222, 400, 6, List.of(new Bounds(0, 340, 20, 400)));
        require(preferredSize.iconSize() == 32 && preferredSize.top() == 302,
                "prefer a fitting larger size over a lower compact row");
        Placement sidePanelFallback = create(222, 160, 6, List.of(new Bounds(0, 100, 20, 160)));
        require(sidePanelFallback.iconSize() == 24 && sidePanelFallback.top() == 124,
                "try compact horizontal clearance when the larger row cannot move above a panel");
    }

    private static void checkObstacles() {
        Placement exactSideGap = create(800, 450, 6, List.of(new Bounds(0, 360, 283, 438)));
        require(exactSideGap.top() == 406, "exact six-pixel horizontal clearance must fit");
        Placement shortSideGap = create(800, 450, 6, List.of(new Bounds(0, 360, 284, 438)));
        require(shortSideGap.top() == 322, "five-pixel horizontal clearance must move the row");
        Placement exactVerticalGap = create(800, 450, 6, List.of(new Bounds(289, 350, 511, 400)));
        require(exactVerticalGap.top() == 406, "exact six-pixel vertical clearance must fit");
        Placement shortVerticalGap = create(800, 450, 6, List.of(new Bounds(289, 350, 511, 401)));
        require(shortVerticalGap.top() == 312, "five-pixel vertical clearance must move the row");

        List<Bounds> stacked = new ArrayList<>(List.of(
                new Bounds(200, 390, 600, 440),
                new Bounds(200, 340, 600, 382),
                new Bounds(200, 286, 600, 330)));
        List<Bounds> original = List.copyOf(stacked);
        Placement aboveStack = create(800, 450, 6, stacked);
        require(aboveStack.top() == 248 && aboveStack.iconSize() == 32,
                "moving above one obstacle must recheck obstacles higher up");
        require(stacked.equals(original), "layout must not mutate the occupied list");
        Collections.reverse(stacked);
        require(create(800, 450, 6, stacked).equals(aboveStack),
                "obstacle order must not change the placement");
        Collections.rotate(stacked, 1);
        require(create(800, 450, 6, stacked).equals(aboveStack),
                "obstacle permutations must not change the placement");
        require(ZombiesSodaHudLayout.create(800, 450, 6,
                        List.of(new Bounds(0, 225, 800, 450))).isEmpty(),
                "a fully occupied lower half must hide every candidate size");
        require(create(800, 450, 6, List.of(new Bounds(400, 225, 400, 450))).top() == 406,
                "an empty rectangle must not consume HUD space");
    }

    private static void checkViewportProperties() {
        for (int width = 1; width <= 340; width += 17) {
            for (int height = 1; height <= 300; height += 19) {
                List<List<Bounds>> obstacleSets = List.of(
                        List.of(),
                        List.of(new Bounds(0, height - 60, width / 3, height)),
                        List.of(new Bounds(width / 3, height * 3 / 4, width * 2 / 3, height * 3 / 4 + 16)),
                        List.of(new Bounds(0, height - 50, width / 2, height),
                                new Bounds(width / 4, height - 90, width * 3 / 4, height - 65)));
                for (int count = 1; count <= 6; count++) {
                    for (List<Bounds> obstacles : obstacleSets) {
                        Optional<Placement> actual = ZombiesSodaHudLayout.create(width, height, count, obstacles);
                        Optional<Placement> expected = enumerateValidPlacements(width, height, count, obstacles);
                        require(actual.equals(expected), "layout differs from exhaustive legal placements at "
                                + width + "x" + height + ", " + count + " icons, " + obstacles);
                        if (actual.isPresent()) {
                            checkBounds(actual.get(), width, height, obstacles);
                        }
                    }
                }
            }
        }
    }

    // Deliberately enumerate every integer y instead of reproducing the production
    // obstacle-jumping algorithm; this catches skipped spaces and premature fallback.
    private static Optional<Placement> enumerateValidPlacements(int width, int height, int count,
                                                               List<Bounds> obstacles) {
        for (int size : new int[]{32, 24, 16}) {
            int gap = size == 32 ? 6 : 4;
            int rowWidth = count * size + (count - 1) * gap;
            if (rowWidth > width) continue;
            int left = (width - rowWidth) / 2;
            for (int top = height - 12 - size; top * 2 >= height; top--) {
                Placement candidate = new Placement(left, top, size, gap, count);
                if (obstacles.stream().allMatch(other -> clears(candidate.bounds(), other))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static void checkBounds(Placement row, int width, int height, List<Bounds> obstacles) {
        require(row.left() >= 0 && row.bounds().right() <= width, "row exceeds horizontal viewport");
        require(row.top() * 2 >= height && row.bounds().bottom() <= height - 12,
                "row must stay in the lower half and preserve its bottom margin");
        int rightMargin = width - row.bounds().right();
        require(rightMargin == row.left() || rightMargin == row.left() + 1,
                "row must stay centered within integer rounding");
        for (Bounds other : obstacles) {
            require(clears(row.bounds(), other), "row must clear all HUD rectangles");
        }
    }

    private static boolean clears(Bounds row, Bounds other) {
        if (other.left() >= other.right() || other.top() >= other.bottom()) return true;
        int horizontalGap = Math.max(other.left() - row.right(), row.left() - other.right());
        int verticalGap = Math.max(other.top() - row.bottom(), row.top() - other.bottom());
        return horizontalGap >= 6 || verticalGap >= 6;
    }

    private static Placement create(int width, int height, int count, List<Bounds> occupied) {
        return ZombiesSodaHudLayout.create(width, height, count, occupied).orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
