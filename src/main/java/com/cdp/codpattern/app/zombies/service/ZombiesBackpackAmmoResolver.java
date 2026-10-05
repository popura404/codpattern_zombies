package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.config.zombies.ZombiesBackpackConfig;

import java.util.Locale;
import java.util.Map;

/** Resolves per-weapon reserve capacity without loading or validating game resources. */
public final class ZombiesBackpackAmmoResolver {
    private ZombiesBackpackAmmoResolver() { }

    public static int resolve(String gunId, String gunType, ZombiesBackpackConfig.Ammunition ammunition) {
        if (ammunition == null) {
            ammunition = ZombiesBackpackConfig.defaults().getAmmunition();
        }
        Integer byGun = gunId == null ? null : ammunition.getMaxReserveAmmoByGunId().get(gunId);
        if (byGun != null) {
            return byGun;
        }
        Integer byType = null;
        if (gunType != null) {
            String normalizedType = gunType.trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Integer> entry : ammunition.getMaxReserveAmmoByType().entrySet()) {
                if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalizedType)) {
                    // Preserve JSON order so the last normalized category wins.
                    byType = entry.getValue();
                }
            }
        }
        return byType == null ? ammunition.getDefaultMaxReserveAmmo() : byType;
    }
}
