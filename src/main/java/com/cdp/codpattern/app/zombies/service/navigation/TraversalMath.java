package com.cdp.codpattern.app.zombies.service.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** World-independent parts of movement evidence, also used by the compatibility suite. */
public final class TraversalMath {
    public static final double EPSILON = 1.0E-6;
    private TraversalMath() { }

    public record Interval(double start, double end) { }

    /** Exact interval in which a translating footprint overlaps one supporting rectangle. */
    public static Interval overlap(double x, double z, double dx, double dz,
            double minX, double maxX, double minZ, double maxZ) {
        double[] times = {0, 1};
        if (!clip(x, dx, minX, maxX, times) || !clip(z, dz, minZ, maxZ, times)) return null;
        return new Interval(times[0], times[1]);
    }

    private static boolean clip(double start, double delta, double min, double max, double[] times) {
        if (Math.abs(delta) < EPSILON) return start >= min && start <= max;
        double a = (min - start) / delta, b = (max - start) / delta;
        times[0] = Math.max(times[0], Math.min(a, b));
        times[1] = Math.min(times[1], Math.max(a, b));
        return times[0] <= times[1];
    }

    /** Returns the end of the contiguous supported prefix, never bridging an unsupported gap. */
    public static double supportedPrefix(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparingDouble(Interval::start));
        double end = 0;
        for (Interval interval : sorted) {
            if (interval.start > end + EPSILON) break;
            end = Math.max(end, interval.end);
        }
        return end;
    }

    /** Upper bound on residual displacement under air drag, with no extra horizontal acceleration. */
    public static double residualDrift(double velocity) { return Math.abs(velocity) / (1.0 - 0.91); }

    /** A deep drop has no height cap. Each advance only consumes one vertical slice. */
    public static final class DropScanRange {
        private double top;
        private final double floor;
        public DropScanRange(double top, double floor) {
            if (!Double.isFinite(top + floor) || floor > top) throw new IllegalArgumentException("Invalid scan range");
            this.top = top;
            this.floor = floor;
        }
        public boolean done() { return top <= floor + EPSILON; }
        public double top() { return top; }
        public double bottom() { return Math.max(floor, top - 1.0); }
        public void advance() { top = bottom(); }
    }
}
