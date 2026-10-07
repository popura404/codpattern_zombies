package com.cdp.codpattern.compat.modesplit;

/**
 * Historical Phase 0 compatibility entry point.
 *
 * <p>The addon owns these fixture runners and exercises the published main dependency
 * together with Zombies data, without a main-mod source checkout.</p>
 */
public final class Phase0DataFixtureCompatTest {
    private Phase0DataFixtureCompatTest() {
    }

    public static void main(String[] args) throws Exception {
        Phase0PublishedMainDataFixtureCompatTest.runAll();
        Phase0ZombiesDataFixtureCompatTest.runAll();
        System.out.println("PASS phase0 combined data fixture compat");
    }
}
