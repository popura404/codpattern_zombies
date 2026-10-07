package com.cdp.codpattern.app.zombies.service.navigation;

import com.cdp.codpattern.app.zombies.service.ZombiesNavigationProgressTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Independent movement, route, combat and finite waiting clocks; no world access. */
public final class RouteProgress {
    private final ZombiesNavigationProgressTracker motion;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayDeque<String> portals = new ArrayDeque<>();
    private final LinkedHashMap<String, Double> furthest = new LinkedHashMap<>();
    private long lastRoute, lastCombat, waitingUntil;
    private String targetContext;
    private double targetX, targetY, targetZ;
    private boolean targetObserved, loop, waitingSpent;

    public RouteProgress(long now, double x, double y, double z) {
        motion = new ZombiesNavigationProgressTracker(now, x, y, z);
        lastRoute = lastCombat = now;
    }

    public void sample(long now, double x, double y, double z) {
        motion.sample(now, x, y, z);
    }

    public void target(String context, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        // Compare with the last meaningful destination, not accumulated per-tick motion.
        // Boundary jitter and jumping in place must not repeatedly forgive old circuits.
        if (!targetObserved || !Objects.equals(context, targetContext)
                || square(x-targetX) + square(y-targetY) + square(z-targetZ) >= 4.0) {
            history.clear(); portals.clear(); furthest.clear(); loop = false;
            targetContext = context;
            targetX = x; targetY = y; targetZ = z;
        }
        targetObserved = true;
    }

    /** Distance is projection along a particular directed edge, independent of plan ID. */
    public boolean advance(long now, String edge, double distance, double x, double y, double z) {
        if (!Double.isFinite(distance)) return false;
        double before = furthest.getOrDefault(edge, 0.0);
        if (loop && !furthest.containsKey(edge)) { loop = false; history.clear(); }
        if (loop || distance < before + 0.2 - 1.0E-8) return false;
        if (!motion.credit(now, x, y, z)) return false;
        furthest.put(edge, distance);
        while (furthest.size() > 256) furthest.remove(furthest.keySet().iterator().next());
        useful(now);
        return true;
    }

    public void completed(long now, String edge) {
        loop |= repeated(history, edge);
        // Completion alone is not progress: the position/projection sampler supplies it.
    }

    public void portal(String transition) { loop |= repeated(portals, transition); }

    private static boolean repeated(ArrayDeque<String> sequence, String edge) {
        if (!sequence.isEmpty() && sequence.peekLast().equals(edge)) return false;
        sequence.addLast(edge);
        while (sequence.size() > 128) sequence.removeFirst();
        var values = new ArrayList<>(sequence);
        for (int length = 2; length * 2 <= values.size(); length++) {
            int start = values.size() - 2 * length;
            boolean same = true;
            for (int i = 0; i < length; i++) {
                if (!values.get(start+i).equals(values.get(start+length+i))) { same = false; break; }
            }
            if (same) return true;
        }
        return false;
    }

    public void engagement(long now) { lastCombat = now; useful(now); }
    private void useful(long now) { lastRoute = now; waitingSpent = false; waitingUntil = 0; }
    public void waiting(long now) {
        if (!waitingSpent) {
            waitingUntil = Math.max(lastRoute, lastCombat) + 640;
            waitingSpent = true;
        }
    }
    /** A conclusive exhausted target round ends its allowance; rechecking alone cannot reopen it. */
    public void retireWaiting() {
        waitingUntil = 0;
        waitingSpent = true;
    }
    public long lastRoute() { return lastRoute; }
    public long lastCombat() { return lastCombat; }
    public long waitingUntil() { return waitingUntil; }
    public boolean loopDetected() { return loop; }
    public int historySize() { return history.size() + portals.size(); }
    private static double square(double n) { return n*n; }
}
