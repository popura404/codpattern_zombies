package com.cdp.codpattern.app.zombies.service.navigation;

import com.cdp.codpattern.app.match.model.RoomId;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One room incarnation. Suppliers and predicates must only be evaluated on the server thread. */
public record NavigationContext(RoomId roomId, ServerLevel level, AABB bounds,
        Supplier<List<ServerPlayer>> validPlayers, Predicate<Mob> ownership, UUID lifecycleId) {
    public NavigationContext {
        Objects.requireNonNull(roomId); Objects.requireNonNull(level); Objects.requireNonNull(bounds);
        Objects.requireNonNull(validPlayers); Objects.requireNonNull(ownership); Objects.requireNonNull(lifecycleId);
        if (!Double.isFinite(bounds.minX + bounds.minY + bounds.minZ + bounds.maxX + bounds.maxY + bounds.maxZ)
                || bounds.getXsize() <= 0 || bounds.getYsize() <= 0 || bounds.getZsize() <= 0) {
            throw new IllegalArgumentException("Navigation requires finite, nonempty map bounds");
        }
    }
    public boolean contains(Vec3 feet) {
        return feet != null && bounds.contains(feet) && feet.y >= level.getMinBuildHeight()
                && feet.y < level.getMaxBuildHeight();
    }
    public boolean owns(Mob mob) {
        return mob != null && mob.level() == level && ownership.test(mob);
    }
    public boolean eligible(Mob mob, ServerPlayer player) {
        if (!owns(mob) || !contains(mob.position()) || player == null || player.level() != level
                || !player.isAlive() || player.isRemoved() || player.isSpectator() || !contains(player.position())) return false;
        try {
            List<ServerPlayer> players = validPlayers.get();
            return players != null && players.contains(player);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
