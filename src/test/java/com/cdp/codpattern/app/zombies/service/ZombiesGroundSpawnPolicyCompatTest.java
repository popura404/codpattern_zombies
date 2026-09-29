package com.cdp.codpattern.app.zombies.service;

public final class ZombiesGroundSpawnPolicyCompatTest {
    private ZombiesGroundSpawnPolicyCompatTest() {
    }

    public static void main(String[] args) {
        repeatedFailuresReduceWeightWithoutBlacklisting();
        expiryAndResetRestoreOriginalWeights();
        separateProfilesAndPointsDoNotShareFailures();
        failureHistoryIsBounded();
        System.out.println("PASS zombies ground spawn policy compat");
    }

    private static void repeatedFailuresReduceWeightWithoutBlacklisting() {
        var history = new ZombiesGroundSpawnPolicy.FailureHistory();
        require(history.multiplier("spawn-a|zombie", 100L) == 1.0D, "unused points retain map weights");
        history.record("spawn-a|zombie", 100L);
        double first = history.multiplier("spawn-a|zombie", 100L);
        history.record("spawn-a|zombie", 200L);
        double second = history.multiplier("spawn-a|zombie", 200L);
        require(first < 1.0D && second < first, "repeated navigation failures reduce the point's weight");
        for (int failure = 0; failure < 100; failure++) {
            history.record("spawn-a|zombie", 300L + failure);
        }
        require(history.multiplier("spawn-a|zombie", 400L) > 0.0D,
                "even a single repeatedly failing spawn must remain eligible for revalidation");
    }

    private static void expiryAndResetRestoreOriginalWeights() {
        var history = new ZombiesGroundSpawnPolicy.FailureHistory();
        history.record("spawn-a|zombie", 100L);
        require(history.multiplier("spawn-a|zombie", 1_299L) < 1.0D, "penalty remains within its window");
        require(history.multiplier("spawn-a|zombie", 1_300L) == 1.0D,
                "a temporary obstruction must not penalize a point after expiry");
        history.record("spawn-a|zombie", 1_400L);
        require(history.multiplier("spawn-a|zombie", 1_400L) == 0.5D,
                "a failure after expiry starts a new sequence rather than retaining its old severity");
        history.clear();
        require(history.multiplier("spawn-a|zombie", 1_401L) == 1.0D,
                "room cleanup must not leak penalties into the next match");
    }

    private static void separateProfilesAndPointsDoNotShareFailures() {
        var history = new ZombiesGroundSpawnPolicy.FailureHistory();
        history.record("low-door|wither-skeleton|tall", 100L);
        require(history.multiplier("low-door|silverfish|small", 101L) == 1.0D,
                "a tall mob's failed route must not penalize a small mob");
        require(history.multiplier("other-door|wither-skeleton|tall", 101L) == 1.0D,
                "a failed point must not penalize the other available points");
    }

    private static void failureHistoryIsBounded() {
        var history = new ZombiesGroundSpawnPolicy.FailureHistory();
        for (int index = 0; index <= ZombiesGroundSpawnPolicy.MAX_FAILURE_KEYS; index++) {
            history.record("spawn-" + index, 100L);
        }
        require(history.multiplier("spawn-0", 101L) == 1.0D,
                "bounded history must discard its oldest entry rather than grow with map edits");
        require(history.multiplier("spawn-" + ZombiesGroundSpawnPolicy.MAX_FAILURE_KEYS, 101L) < 1.0D,
                "newer failed points should remain in the bounded history");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
