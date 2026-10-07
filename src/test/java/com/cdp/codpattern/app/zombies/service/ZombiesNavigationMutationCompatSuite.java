package com.cdp.codpattern.app.zombies.service;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.List;

/** Existing barrier/purchase assertions, run independently of unrelated migration-codec failures. */
public final class ZombiesNavigationMutationCompatSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        var flag = Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true);
        flag.setBoolean(null, true);
        if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) throw new AssertionError("registries must load");
        List<Throwable> failures = new ArrayList<>();
        run("barrier block runtime", () -> ZombiesBarrierBlockRuntimeServiceCompatTest.main(args), failures);
        run("barrier movement", () -> ZombiesBarrierMovementServiceCompatTest.main(args), failures);
        run("barrier visual", () -> ZombiesBarrierVisualServiceCompatTest.main(args), failures);
        run("purchase state", () -> ZombiesPurchaseStateServicesCompatTest.main(args), failures);
        run("red barrier static contracts", () -> ZombiesRedPlayerBarrierStaticContractCompatTest.main(args), failures);
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError(failures.size() + " existing mutation compatibility groups failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void run(String name, CheckedCheck check, List<Throwable> failures) {
        try {
            check.run();
            System.out.println("PASS existing " + name + " assertions");
        } catch (Exception | AssertionError failure) {
            failures.add(failure);
            System.err.println("FAIL existing " + name + ": " + failure);
        }
    }

    @FunctionalInterface
    private interface CheckedCheck { void run() throws Exception; }
}
