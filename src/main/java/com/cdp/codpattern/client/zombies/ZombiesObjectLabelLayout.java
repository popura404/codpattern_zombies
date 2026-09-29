package com.cdp.codpattern.client.zombies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Screen-space label packing without Minecraft or graphics dependencies. */
public final class ZombiesObjectLabelLayout {
    private static final int MAX_VISIBLE = 32;
    private static final long RETURN_DELAY_MILLIS = 300L;
    private static final Comparator<Candidate> PRIORITY = Comparator
            .comparing(Candidate::focused).reversed()
            .thenComparingLong(candidate -> distanceBucket(candidate.distance()))
            .thenComparing(Candidate::id);

    private final Map<String, History> history = new HashMap<>();

    public record Rect(double left, double top, double right, double bottom) {
        public boolean overlaps(Rect other) {
            return left < other.right && right > other.left
                    && top < other.bottom && bottom > other.top;
        }

        private boolean valid() {
            return Double.isFinite(left) && Double.isFinite(top)
                    && Double.isFinite(right) && Double.isFinite(bottom)
                    && left < right && top < bottom;
        }

        private boolean contains(Rect other) {
            return other.left >= left && other.top >= top
                    && other.right <= right && other.bottom <= bottom;
        }
    }

    public record Candidate(String id, boolean focused, double distance, List<Option> options) {
        public Candidate {
            options = List.copyOf(options);
        }
    }

    public record Option(int slot, boolean compact, Rect bounds) { }

    public record Placement(String id, Option option) { }

    private record Choice(int slot, boolean compact) {
        private static Choice of(Option option) {
            return new Choice(option.slot(), option.compact());
        }
    }

    private record History(Choice choice, boolean focused, Choice pending, long pendingSince) { }

    /**
     * Options must be ordered from most to least desirable: full labels first,
     * then compact labels, with smaller displacements first within each group.
     * Bounds already include desired spacing and must fit fully in the viewport;
     * callers may clip projected bounds to the visible part before submitting them.
     */
    public List<Placement> arrange(List<Candidate> candidates, List<Rect> obstacles,
                                   Rect viewport, long nowMillis) {
        if (!viewport.valid()) {
            clear();
            return List.of();
        }

        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(PRIORITY);
        List<Rect> occupied = new ArrayList<>();
        for (Rect obstacle : obstacles) {
            if (obstacle.valid()) occupied.add(obstacle);
        }
        List<Placement> placements = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        for (Candidate candidate : ordered) {
            if (!currentIds.add(candidate.id())) continue;
            History previous = history.get(candidate.id());
            int bestIndex = placements.size() < MAX_VISIBLE
                    ? bestAvailable(candidate.options(), viewport, occupied) : -1;
            if (bestIndex < 0) {
                // A hidden label should reappear at its best position immediately.
                history.remove(candidate.id());
                continue;
            }

            List<Option> options = candidate.options();
            Option selected = options.get(bestIndex);
            Choice pending = null;
            long pendingSince = 0L;
            // Acquiring focus takes effect immediately, including restoring full
            // text at the lowest available position before other labels are packed.
            if (previous != null && !(candidate.focused() && !previous.focused())) {
                int previousIndex = indexOf(options, previous.choice());
                if (previousIndex > bestIndex
                        && available(options.get(previousIndex), viewport, occupied)) {
                    pending = Choice.of(selected);
                    pendingSince = pending.equals(previous.pending())
                            && nowMillis >= previous.pendingSince()
                            ? previous.pendingSince() : nowMillis;
                    if (nowMillis - pendingSince < RETURN_DELAY_MILLIS) {
                        selected = options.get(previousIndex);
                    } else {
                        pending = null;
                        pendingSince = 0L;
                    }
                }
            }

            placements.add(new Placement(candidate.id(), selected));
            occupied.add(selected.bounds());
            history.put(candidate.id(), new History(Choice.of(selected), candidate.focused(),
                    pending, pendingSince));
        }
        history.keySet().retainAll(currentIds);
        return List.copyOf(placements);
    }

    public void clear() {
        history.clear();
    }

    private static long distanceBucket(double distance) {
        return Double.isFinite(distance) ? (long) Math.floor(Math.max(0.0D, distance) / 2.0D)
                : Long.MAX_VALUE;
    }

    private static int bestAvailable(List<Option> options, Rect viewport, List<Rect> occupied) {
        for (int i = 0; i < options.size(); i++) {
            if (available(options.get(i), viewport, occupied)) return i;
        }
        return -1;
    }

    private static boolean available(Option option, Rect viewport, List<Rect> occupied) {
        Rect bounds = option.bounds();
        if (!bounds.valid() || !viewport.contains(bounds)) return false;
        for (Rect other : occupied) {
            if (bounds.overlaps(other)) return false;
        }
        return true;
    }

    private static int indexOf(List<Option> options, Choice choice) {
        for (int i = 0; i < options.size(); i++) {
            if (choice.equals(Choice.of(options.get(i)))) return i;
        }
        return -1;
    }
}
