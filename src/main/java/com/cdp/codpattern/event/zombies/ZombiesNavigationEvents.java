package com.cdp.codpattern.event.zombies;

import com.cdp.codpattern.app.zombies.service.navigation.NavigationScheduler;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraft.world.entity.Mob;
import com.cdp.codpattern.app.zombies.service.navigation.LayeredNavigationRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One global budget slice, after all dimensions have registered their requests. */
@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesNavigationEvents {
    private ZombiesNavigationEvents() { }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END)
            NavigationScheduler.forServer(event.getServer()).advance(event.getServer().getTickCount());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        NavigationScheduler.stopServer(event.getServer());
    }
    @SubscribeEvent public static void block(BlockEvent event) {
        // This event only reports that neighbors were notified, including periodic
        // notifications with no collision change. Fresh execution proofs handle those.
        if (event instanceof BlockEvent.NeighborNotifyEvent) return;
        if (event.getLevel() instanceof ServerLevel level)
            NavigationScheduler.forServer(level.getServer()).invalidate(level, new AABB(event.getPos()),
                    "block-event:" + event.getClass().getSimpleName());
    }
    @SubscribeEvent public static void chunkUnload(ChunkEvent.Unload event) {
        invalidateChunk(event, "chunk-unload");
    }
    @SubscribeEvent public static void chunkLoad(ChunkEvent.Load event) {
        invalidateChunk(event, "chunk-load");
    }
    private static void invalidateChunk(ChunkEvent event, String reason) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            NavigationScheduler.forServer(level.getServer()).invalidate(level,
                    new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
                            pos.getMaxBlockX() + 1, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1), reason);
        }
    }
    @SubscribeEvent public static void entityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            LayeredNavigationRuntime runtime = LayeredNavigationRuntime.of(mob);
            if (runtime != null) runtime.cancelMob(mob.getUUID());
        }
    }
}
