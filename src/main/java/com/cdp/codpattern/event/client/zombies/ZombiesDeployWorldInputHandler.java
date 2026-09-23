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

/** World-only shortcuts for undo and redo in the deployment session. */
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
                || ZombiesDeployClientState.snapshot() == null) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        if (!(stack.getItem() instanceof ZombiesDeployTool)) {
            return;
        }
        boolean redo = (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (redo ? ZombiesDeployClientState.snapshot().redoCount() == 0 : ZombiesDeployClientState.snapshot().undoCount() == 0) {
            return;
        }
        FPSMatch.sendToServer(new ZombiesDeployToolActionC2SPacket(
                redo ? ZombiesDeployToolActionC2SPacket.Action.REDO_LAST : ZombiesDeployToolActionC2SPacket.Action.UNDO_LAST,
                ZombiesDeployTool.getDraft(stack),
                ZombiesDeployClientState.current().revision()));
    }
}
