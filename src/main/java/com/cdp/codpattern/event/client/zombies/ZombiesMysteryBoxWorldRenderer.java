package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.app.match.model.ModeObjectState;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInventoryService;
import com.cdp.codpattern.app.zombies.sync.ZombiesObjectStateKeys;
import com.cdp.codpattern.client.ClientMatchState;
import com.cdp.codpattern.client.ClientModeObjectState;
import com.cdp.codpattern.client.zombies.ClientZombiesState;
import com.cdp.codpattern.client.zombies.ZombiesRarityDisplay;
import com.cdp.codpattern.client.zombies.ZombiesMysteryBoxVisualLayout;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.blaze3d.systems.RenderSystem;

@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesMysteryBoxWorldRenderer {
    private ZombiesMysteryBoxWorldRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || !ClientZombiesState.shouldRenderHud()) return;
        Minecraft mc = Minecraft.getInstance(); ClientLevel level = mc.level; Camera camera = event.getCamera();
        String room = ClientMatchState.roomContextName();
        if (level == null || camera == null || room == null || room.isBlank()) return;
        Vec3 cameraPos = camera.getPosition(); PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        try {
            for (ModeObjectState state : ClientModeObjectState.roomStates(room).values()) {
                if (state == null) continue; CompoundTag p = state.payload();
                if (!"mystery_box".equals(p.getString(ZombiesObjectStateKeys.PAYLOAD_TYPE))) continue;
                String phase = p.getString("mysteryPhase"); if ("IDLE".equals(phase) || phase.isBlank()) continue;
                BlockPos boxPos = ZombiesMysteryBoxVisualLayout.boxPosition(p, state.position());
                if (boxPos == null) continue;
                Vec3 anchor = Vec3.atBottomCenterOf(boxPos).add(0.0D, ZombiesMysteryBoxVisualLayout.EFFECT_BASE_HEIGHT, 0.0D);
                if (cameraPos.distanceToSqr(anchor) > 24.0D * 24.0D) continue;
                Vec3 rel = anchor.subtract(cameraPos); pose.pushPose(); pose.translate(rel.x, rel.y, rel.z);
                int frame = 9;
                if ("ROLLING".equals(phase)) {
                    long start = p.getLong("rollStartTick"); long tick = level.getGameTime() - start;
                    frame = Math.max(0, Math.min(9, (int)(tick / 10L)));
                }
                net.minecraft.nbt.ListTag list = p.getList("previewGunIds", 8);
                if (list != null && frame < list.size()) {
                    String gun = list.getString(frame); ItemStack stack = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules(gun);
                    if (!stack.isEmpty()) {
                        pose.translate(0, ZombiesMysteryBoxVisualLayout.WEAPON_FLOAT_OFFSET
                                + Mth.sin((level.getGameTime() + event.getPartialTick()) * 0.12F)
                                * ZombiesMysteryBoxVisualLayout.WEAPON_BOB_AMPLITUDE, 0);
                        pose.mulPose(Axis.YP.rotationDegrees((level.getGameTime() + event.getPartialTick()) * 4.0F));
                        pose.pushPose(); pose.translate(0, ZombiesMysteryBoxVisualLayout.WEAPON_DISPLAY_OFFSET, 0);
                        float weaponScale = ZombiesMysteryBoxVisualLayout.WEAPON_SCALE;
                        pose.scale(weaponScale, weaponScale, weaponScale);
                        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, 0, pose, buffers, level, 0);
                        pose.popPose();
                    }
                }
                int color = ZombiesRarityDisplay.fromRarityId(p.getString("rarityId")).map(ZombiesRarityDisplay.Entry::color).orElse(0xFFFFC107);
                float[] rgb = new float[] {((color >> 16) & 255) / 255.0F, ((color >> 8) & 255) / 255.0F, (color & 255) / 255.0F};
                BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, 0.0F, 1.0F, level.getGameTime(), 0, 24, rgb, 0.12F, 0.35F);
                pose.popPose();
            }
            buffers.endBatch();
        } finally { RenderSystem.enableCull(); RenderSystem.disableBlend(); }
    }
}
