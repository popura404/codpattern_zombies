package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Explicit notification for successful room-owned mutations that do not emit a placement event. */
public final class NavigationWorldChanges {
    private NavigationWorldChanges() { }

    public static void blockChanged(ServerLevel level, BlockPos position, String reason) {
        NavigationScheduler.forServer(level.getServer()).invalidate(level, new AABB(position), reason);
    }
}
