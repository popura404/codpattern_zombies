package com.cdp.codpattern.common.block;

import com.cdp.codpattern.app.zombies.model.ZombiesModeItemText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

public final class ZombiesRedPlayerBarrierItem extends BlockItem {
    public ZombiesRedPlayerBarrierItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return ZombiesModeItemText.itemName("item.codpattern.zombies_red_player_barrier");
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(ZombiesModeItemText.applicableModeTooltip());
    }
}
