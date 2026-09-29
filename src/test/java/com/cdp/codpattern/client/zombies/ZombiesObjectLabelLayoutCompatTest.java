package com.cdp.codpattern.client.zombies;

import java.util.ArrayList;
import java.util.List;

import com.cdp.codpattern.client.zombies.ZombiesObjectLabelLayout.Candidate;
import com.cdp.codpattern.client.zombies.ZombiesObjectLabelLayout.Option;
import com.cdp.codpattern.client.zombies.ZombiesObjectLabelLayout.Placement;
import com.cdp.codpattern.client.zombies.ZombiesObjectLabelLayout.Rect;

/** Behavioral layout checks that need no Minecraft bootstrap or graphics context. */
public final class ZombiesObjectLabelLayoutCompatTest {
    private static final Rect VIEWPORT = new Rect(0, 0, 1000, 800);
    private static final Option BASE = option(0, false, 100, 300, 220, 340);
    private static final Option UP = option(1, false, 100, 250, 220, 290);
    private static final Option COMPACT = option(0, true, 140, 300, 180, 320);

    public static void main(String[] args) {
        separatedLabelsStayAtBase();
        collisionsUseHigherFullThenCompactThenHide();
        focusImmediatelyReclaimsBestPosition();
        focusRespectsWeaponObstacles();
        newConflictsMoveImmediately();
        betterPositionsRequireContinuousAvailability();
        removedAndClearedLabelsForgetTheirHistory();
        priorityIsStableWithinDistanceBuckets();
        viewportAndInvalidBoundsAreRespected();
        currentGeometryReplacesCachedGeometry();
        visibleLimitRespectsPriority();
        System.out.println("PASS zombies label layout: collision packing, compact fallback, focus, "
                + "weapon obstacles, return delay, cache cleanup, viewport and visible limit");
    }

    private static void separatedLabelsStayAtBase() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        Option right = option(0, false, 220, 300, 340, 340);
        List<Placement> result = arrange(layout, List.of(candidate("left", BASE, UP),
                candidate("right", right)), List.of(), 0);
        require(result.size() == 2, "touching label edges must remain visible");
        require(!BASE.bounds().overlaps(right.bounds()), "touching edges do not overlap");
        chosen(result, "left", 0, false);
        chosen(result, "right", 0, false);
        assertNoCollisions(result, List.of());
    }

    private static void collisionsUseHigherFullThenCompactThenHide() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Placement> result = arrange(layout, List.of(candidate("a", BASE, UP, COMPACT),
                candidate("b", BASE, UP, COMPACT), candidate("c", BASE, UP, COMPACT)), List.of(), 0);
        chosen(result, "a", 0, false);
        chosen(result, "b", 1, false);
        require(result.size() == 2, "labels with no free option must hide");
        assertNoCollisions(result, List.of());

        layout.clear();
        List<Rect> obstacles = List.of(new Rect(100, 300, 130, 340), UP.bounds());
        result = arrange(layout, List.of(candidate("a", BASE, UP, COMPACT)), obstacles, 0);
        chosen(result, "a", 0, true);
        assertNoCollisions(result, obstacles);
    }

    private static void focusImmediatelyReclaimsBestPosition() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        arrange(layout, List.of(candidate("a", BASE, UP), candidate("b", BASE, UP)), List.of(), 0);
        List<Placement> result = arrange(layout, List.of(candidate("a", BASE, UP),
                new Candidate("b", true, 10, List.of(BASE, UP))), List.of(), 10);
        chosen(result, "b", 0, false);
        chosen(result, "a", 1, false);
        require(result.get(0).id().equals("b"), "focused label must be packed first");

        result = arrange(layout, List.of(new Candidate("a", true, 10, List.of(BASE, UP)),
                candidate("b", BASE, UP)), List.of(), 20);
        chosen(result, "a", 0, false);
        chosen(result, "b", 1, false);
        assertNoCollisions(result, List.of());
    }

    private static void focusRespectsWeaponObstacles() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        Candidate focused = new Candidate("box", true, 1, List.of(BASE, UP));
        List<Placement> result = arrange(layout, List.of(focused), List.of(BASE.bounds()), 0);
        chosen(result, "box", 1, false);
        assertNoCollisions(result, List.of(BASE.bounds()));
        require(arrange(layout, List.of(focused), List.of(BASE.bounds(), UP.bounds()), 1).isEmpty(),
                "focus must not override all blocked positions");
    }

    private static void newConflictsMoveImmediately() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Candidate> candidates = List.of(candidate("box", BASE, UP));
        chosen(arrange(layout, candidates, List.of(), 0), "box", 0, false);
        chosen(arrange(layout, candidates, List.of(BASE.bounds()), 1), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(UP.bounds()), 2), "box", 0, false);
    }

    private static void betterPositionsRequireContinuousAvailability() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Candidate> candidates = List.of(candidate("box", BASE, UP));
        chosen(arrange(layout, candidates, List.of(BASE.bounds()), 1000), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1010), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1309), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1310), "box", 0, false);

        chosen(arrange(layout, candidates, List.of(BASE.bounds()), 1400), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1500), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(BASE.bounds()), 1600), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1700), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 1999), "box", 1, false);
        chosen(arrange(layout, candidates, List.of(), 2000), "box", 0, false);

        layout.clear();
        candidates = List.of(candidate("box", BASE, COMPACT));
        chosen(arrange(layout, candidates, List.of(new Rect(100, 300, 130, 340)), 0), "box", 0, true);
        chosen(arrange(layout, candidates, List.of(), 10), "box", 0, true);
        chosen(arrange(layout, candidates, List.of(), 310), "box", 0, false);
    }

    private static void removedAndClearedLabelsForgetTheirHistory() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Candidate> candidates = List.of(candidate("box", BASE, UP));
        arrange(layout, candidates, List.of(BASE.bounds()), 0);
        arrange(layout, List.of(), List.of(), 1);
        chosen(arrange(layout, candidates, List.of(), 2), "box", 0, false);
        arrange(layout, candidates, List.of(BASE.bounds()), 3);
        layout.clear();
        chosen(arrange(layout, candidates, List.of(), 4), "box", 0, false);

        arrange(layout, candidates, List.of(BASE.bounds()), 5);
        arrange(layout, candidates, List.of(BASE.bounds(), UP.bounds()), 6);
        chosen(arrange(layout, candidates, List.of(), 7), "box", 0, false);
    }

    private static void priorityIsStableWithinDistanceBuckets() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Placement> result = arrange(layout, List.of(new Candidate("b", false, 4.01, List.of(BASE)),
                new Candidate("a", false, 5.99, List.of(BASE))), List.of(), 0);
        require(result.size() == 1 && result.get(0).id().equals("a"), "same bucket must sort by stable id");
        result = arrange(layout, List.of(new Candidate("a", false, 4.02, List.of(BASE)),
                new Candidate("b", false, 5.98, List.of(BASE))), List.of(), 1);
        require(result.get(0).id().equals("a"), "distance jitter within a bucket must not change priority");
        result = arrange(layout, List.of(new Candidate("a", false, 4.02, List.of(BASE)),
                new Candidate("b", false, 3.99, List.of(BASE))), List.of(), 2);
        require(result.get(0).id().equals("b"), "nearer distance bucket must take priority");
    }

    private static void viewportAndInvalidBoundsAreRespected() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        Option outside = option(0, false, 100, -5, 220, 35);
        Option invalid = option(1, false, Double.NaN, 0, 220, 35);
        List<Placement> result = arrange(layout, List.of(candidate("outside", outside),
                candidate("invalid", invalid), candidate("valid", BASE)), List.of(), 0);
        require(result.size() == 1 && result.get(0).id().equals("valid"),
                "partly outside and invalid projected options must be rejected");
        require(layout.arrange(List.of(candidate("valid", BASE)), List.of(),
                new Rect(0, 0, 0, 0), 1).isEmpty(), "empty viewport must not place labels");
    }

    private static void currentGeometryReplacesCachedGeometry() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        arrange(layout, List.of(candidate("box", BASE, UP)), List.of(), 0);
        Option movedBase = option(0, false, 300, 300, 420, 340);
        List<Placement> result = arrange(layout, List.of(candidate("box", movedBase, UP)),
                List.of(movedBase.bounds()), 1);
        chosen(result, "box", 1, false);
        assertNoCollisions(result, List.of(movedBase.bounds()));
    }

    private static void visibleLimitRespectsPriority() {
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int column = i % 10;
            int row = i / 10;
            candidates.add(new Candidate(String.format("box-%02d", i), i == 39, 10,
                    List.of(option(0, false, column * 90, row * 90, column * 90 + 50, row * 90 + 30))));
        }
        List<Placement> result = arrange(layout, candidates, List.of(), 0);
        require(result.size() == 32, "visible labels must be capped at 32");
        require(result.get(0).id().equals("box-39"), "focus must survive the visible cap");
        assertNoCollisions(result, List.of());
    }

    private static Candidate candidate(String id, Option... options) {
        return new Candidate(id, false, 5, List.of(options));
    }

    private static Option option(int slot, boolean compact, double left, double top, double right, double bottom) {
        return new Option(slot, compact, new Rect(left, top, right, bottom));
    }

    private static List<Placement> arrange(ZombiesObjectLabelLayout layout, List<Candidate> candidates,
                                           List<Rect> obstacles, long time) {
        return layout.arrange(candidates, obstacles, VIEWPORT, time);
    }

    private static void chosen(List<Placement> placements, String id, int slot, boolean compact) {
        Placement placement = placements.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing label: " + id));
        require(placement.option().slot() == slot && placement.option().compact() == compact,
                "unexpected option for " + id + ": " + placement.option());
    }

    private static void assertNoCollisions(List<Placement> placements, List<Rect> obstacles) {
        for (int i = 0; i < placements.size(); i++) {
            Rect bounds = placements.get(i).option().bounds();
            for (int j = i + 1; j < placements.size(); j++) {
                require(!bounds.overlaps(placements.get(j).option().bounds()), "visible labels overlap");
            }
            for (Rect obstacle : obstacles) require(!bounds.overlaps(obstacle), "label overlaps weapon obstacle");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
