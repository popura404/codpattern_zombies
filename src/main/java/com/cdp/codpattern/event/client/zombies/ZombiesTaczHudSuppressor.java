package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.client.gui.overlay.zombies.ZombiesHudOverlay;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Replaces TaCZ's small gun HUD only while the Zombies weapon card is drawable. */
@Mod.EventBusSubscriber(
        modid = ZombiesAddonConstants.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesTaczHudSuppressor {
    private static final ResourceLocation TACZ_GUN_HUD = ResourceLocation.fromNamespaceAndPath(
            "tacz", "tac_gun_hud_overlay");

    private ZombiesTaczHudSuppressor() {
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        if (TACZ_GUN_HUD.equals(event.getOverlay().id()) && ZombiesHudOverlay.shouldReplaceTaczGunHud()) {
            event.setCanceled(true);
        }
    }
}
