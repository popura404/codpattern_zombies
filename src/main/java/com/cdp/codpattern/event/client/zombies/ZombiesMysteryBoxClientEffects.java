package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.app.match.model.ModeObjectState;
import com.cdp.codpattern.app.zombies.sync.ZombiesObjectStateKeys;
import com.cdp.codpattern.client.ClientMatchState;
import com.cdp.codpattern.client.ClientModeObjectState;
import com.cdp.codpattern.client.zombies.ClientZombiesState;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesMysteryBoxClientEffects {
    private static final Map<String, Integer> LAST_FRAME = new HashMap<>();
    private ZombiesMysteryBoxClientEffects() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClientZombiesState.shouldRenderHud()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String room = ClientMatchState.roomContextName();
        if (room == null || room.isBlank()) return;
        for (ModeObjectState state : ClientModeObjectState.roomStates(room).values()) {
            if (state == null) continue;
            var payload = state.payload();
            if (!"mystery_box".equals(payload.getString(ZombiesObjectStateKeys.PAYLOAD_TYPE))) continue;
            if (!"ROLLING".equals(payload.getString("mysteryPhase"))) {
                LAST_FRAME.remove(state.objectKey());
                continue;
            }
            long elapsed = Math.max(0L, mc.level.getGameTime() - payload.getLong("rollStartTick"));
            int frame = Math.min(9, (int) (elapsed / 10L));
            Integer previous = LAST_FRAME.put(state.objectKey(), frame);
            if (previous != null && previous == frame) continue;
            float pitch = 0.75F + frame * 0.06F;
            mc.level.playLocalSound(
                    payload.getInt("boxPosX") + 0.5D,
                    payload.getInt("boxPosY") + 1.0D,
                    payload.getInt("boxPosZ") + 0.5D,
                    frame == 9 ? SoundEvents.NOTE_BLOCK_PLING.get() : SoundEvents.NOTE_BLOCK_CHIME.get(),
                    SoundSource.BLOCKS, 0.8F, pitch, false);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { LAST_FRAME.clear(); }
}
