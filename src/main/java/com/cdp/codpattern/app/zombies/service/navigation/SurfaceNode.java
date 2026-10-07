package com.cdp.codpattern.app.zombies.service.navigation;

import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/** Exact supported feet, including fractional surface height; stacked floors never alias. */
public record SurfaceNode(Vec3 feet, long version) {
    public SurfaceNode {
        Objects.requireNonNull(feet);
        if (!Double.isFinite(feet.x + feet.y + feet.z)) throw new IllegalArgumentException("Nonfinite feet");
    }
    public NavigationGraphCache.TileKey tile() { return NavigationGraphCache.TileKey.at(feet); }
}
