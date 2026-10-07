package com.cdp.codpattern.navigationverification;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import com.cdp.codpattern.app.zombies.service.navigation.NativeNavigationGuard;
import com.cdp.codpattern.app.zombies.service.navigation.NavigationContext;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;

import java.util.List;
import java.util.UUID;

/** Standalone verification mod. It is never included in the addon distribution. */
@Mod("navigation_production_probe")
public final class ZombiesNavigationProductionProbe {
    public ZombiesNavigationProductionProbe() {
        MinecraftForge.EVENT_BUS.addListener(this::verify);
    }

    private void verify(ServerStartedEvent event) {
        if (!FMLLoader.isProduction()) {
            throw new AssertionError("The packaged AT probe must use the production Forge launch target");
        }
        var level = event.getServer().overworld();
        var mob = EntityType.ZOMBIE.create(level);
        if (mob == null) throw new AssertionError("Cannot construct the production probe mob");
        var origin = level.getSharedSpawnPos();
        mob.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
        var context = new NavigationContext(RoomId.of("zombies", "production-at-verification"), level,
                new AABB(origin).inflate(16), List::of, candidate -> candidate == mob, UUID.randomUUID());
        var runtime = new LayeredNavigationRuntime(context);
        try {
            runtime.install(mob);
            if (LayeredNavigationRuntime.of(mob) != runtime) {
                throw new AssertionError("The probe must enter the owned Guard write branch");
            }
            // These calls resolve GETFIELD/PUTFIELD inside the final addon JAR. The probe has no AT.
            NativeNavigationGuard.pendingRecomputation(mob);
            NativeNavigationGuard.follow(context, mob);
            NativeNavigationGuard.controlledMove(context, mob);
            if (NativeNavigationGuard.pendingRecomputation(mob)) {
                throw new AssertionError("Owned Guard writes must clear pending recomputation");
            }
        } finally {
            runtime.close();
            mob.discard();
        }
        if (runtime.controllerCount() != 0 || runtime.planningStats().activeRequests() != 0) {
            throw new AssertionError("The production probe must release its navigation runtime");
        }
        System.out.println("NAVIGATION_PRODUCTION_AT_VERIFIED production=true addonGuardRead=true addonGuardWrite=true probeHasAT=false");
        event.getServer().halt(false);
    }
}
