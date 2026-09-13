package com.cdp.codpattern.config.zombies;

import com.cdp.codpattern.client.zombies.ZombiesRarityDisplay;

/** Compatibility checks for the legendary rarity defaults and presentation. */
public final class ZombiesLegendaryRarityCompatTest {
    private ZombiesLegendaryRarityCompatTest() {
    }

    public static void main(String[] args) {
        ZombiesRulesConfig.Rarity wallLegendary = new ZombiesRulesConfig.WeaponWall()
                .getRarities().stream()
                .filter(rarity -> ZombiesRulesConfig.RARITY_LEGENDARY.equals(rarity.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("weapon wall defaults must include legendary"));
        require(close(wallLegendary.getInitialWeight(), 1.0D), "legendary initial weight");
        require(close(wallLegendary.getWeightDeltaPerRefresh(), 1.0D), "legendary refresh delta");
        require(close(wallLegendary.getMinWeight(), 0.0D), "legendary minimum weight");
        require(close(wallLegendary.getMaxWeight(), 100.0D), "legendary maximum weight");
        require(close(wallLegendary.getDamageMultiplier(), 2.0D), "legendary damage multiplier");
        require(wallLegendary.getGuns().isEmpty(), "legendary default gun list must be empty");

        ZombiesMysteryBoxConfig.Rarity boxLegendary = ZombiesMysteryBoxConfig.defaults().getRarities().stream()
                .filter(rarity -> "legendary".equals(rarity.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mystery box defaults must include legendary"));
        require(close(boxLegendary.getInitialWeight(), 1.0D), "mystery box legendary initial weight");
        require(close(boxLegendary.getWeightDeltaPerRefresh(), 1.0D), "mystery box legendary refresh delta");
        require(close(boxLegendary.getDamageMultiplier(), 2.0D), "mystery box legendary damage multiplier");
        require(boxLegendary.getGuns().isEmpty(), "mystery box legendary default gun list must be empty");
        require(ZombiesRulesValidator.supportedRarityId("LEGENDARY"), "legendary should be a supported rarity");

        ZombiesRarityDisplay.Entry display = ZombiesRarityDisplay.fromRarityId(" legendary ")
                .orElseThrow(() -> new AssertionError("legendary display should resolve"));
        require("传奇".equals(display.label()), "legendary display label");
        require(display.color() == 0xFFFF9800, "legendary display color");
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.000001D;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
