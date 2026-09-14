package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.client.zombies.ZombiesDeployClientState;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import com.phasetranscrystal.fpsmatch.common.packet.zombies.ZombiesDeployToolActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** World-only shortcut for undoing the last staged deploy operation. */
@Mod.EventBusSubscriber(
        modid = ZombiesAddonConstants.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesDeployWorldInputHandler {
    private ZombiesDeployWorldInputHandler() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_PRESS
                || event.getKey() != GLFW.GLFW_KEY_R
                || minecraft.screen != null
                || minecraft.player == null
                || !ZombiesDeployClientState.current().dirty()) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        if (!(stack.getItem() instanceof ZombiesDeployTool)) {
            return;
        }
        FPSMatch.sendToServer(new ZombiesDeployToolActionC2SPacket(
                ZombiesDeployToolActionC2SPacket.Action.UNDO_LAST,
                ZombiesDeployTool.getDraft(stack),
                ZombiesDeployClientState.current().revision()));
    }
}
