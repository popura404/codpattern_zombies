package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.entity.Mob;

/** The sole writer of the single field exposed by the addon AccessTransformer. */
public final class NativeNavigationGuard {
    private NativeNavigationGuard() { }

    public static void follow(NavigationContext context, Mob mob) {
        if (context.owns(mob) && LayeredNavigationRuntime.of(mob) != null) {
            mob.getNavigation().hasDelayedRecomputation = false;
        }
    }

    public static void controlledMove(NavigationContext context, Mob mob) {
        if (context.owns(mob) && LayeredNavigationRuntime.of(mob) != null) {
            mob.getNavigation().stop();
            mob.getNavigation().hasDelayedRecomputation = false;
        }
    }

    /** Read-only diagnostics for the complete-tick regression; all native field access stays here. */
    public static boolean pendingRecomputation(Mob mob) { return mob.getNavigation().hasDelayedRecomputation; }
}
