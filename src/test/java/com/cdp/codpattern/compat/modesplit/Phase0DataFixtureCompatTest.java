package com.cdp.codpattern.compat.modesplit;

/**
 * Historical Phase 0 compatibility entry point.
 *
 * <p>Round 4 keeps this class executable while delegating physical ownership to
 * the main-only and Zombies-addon fixture runners.</p>
 */
public final class Phase0DataFixtureCompatTest {
    private Phase0DataFixtureCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        Phase0MainDataFixtureCompatTest.runAll();
        Phase0ZombiesDataFixtureCompatTest.runAll();
        System.out.println("PASS phase0 combined data fixture compat");
    }
}
