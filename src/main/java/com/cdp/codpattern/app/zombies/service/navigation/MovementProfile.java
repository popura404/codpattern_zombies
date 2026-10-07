package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import java.util.Objects;

/** Immutable capability/cache key. Entity-dependent collision contexts are deliberately isolated. */
public record MovementProfile(double width, double height, double stepHeight,
        boolean canOpenDoors, boolean canPassDoors, boolean allowDrops, String collisionContext) {
    public MovementProfile {
        Objects.requireNonNull(collisionContext);
        if (!Double.isFinite(width + height + stepHeight) || width <= 0 || height <= 0 || stepHeight < 0)
            throw new IllegalArgumentException("Invalid movement dimensions");
    }
    public static MovementProfile from(Mob mob) {
        // In 1.20.1 GroundPathNavigation.canOpenDoors incorrectly delegates to canPassDoors.
        boolean open = mob.getNavigation() instanceof GroundPathNavigation ground && ground.getNodeEvaluator().canOpenDoors();
        boolean pass = mob.getNavigation() instanceof GroundPathNavigation ground && ground.canPassDoors();
        String context = mob.getType() + ":" + mob.getUUID() + ":" + mob.getPose() + ":" + mob.isDescending()
                + ":" + mob.getMainHandItem() + ":" + mob.getOffhandItem()
                + ":" + mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
        return new MovementProfile(mob.getBbWidth(), mob.getBbHeight(), Math.max(1.0, mob.maxUpStep()), open,
                pass, true, context);
    }
}
