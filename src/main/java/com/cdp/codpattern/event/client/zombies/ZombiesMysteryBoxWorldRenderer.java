package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.app.match.model.ModeObjectState;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInventoryService;
import com.cdp.codpattern.app.zombies.sync.ZombiesObjectStateKeys;
import com.cdp.codpattern.client.ClientMatchState;
import com.cdp.codpattern.client.ClientModeObjectState;
import com.cdp.codpattern.client.zombies.ClientZombiesState;
import com.cdp.codpattern.client.zombies.ZombiesMysteryBoxVisualLayout;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesMysteryBoxWorldRenderer {
    private ZombiesMysteryBoxWorldRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || !ClientZombiesState.shouldRenderHud()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Camera camera = event.getCamera();
        String room = ClientMatchState.roomContextName();
        if (level == null || camera == null || room == null || room.isBlank()) return;
        Vec3 cameraPos = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        try {
            for (ModeObjectState state : ClientModeObjectState.roomStates(room).values()) {
                if (state == null) continue;
                CompoundTag payload = state.payload();
                if (!"mystery_box".equals(payload.getString(ZombiesObjectStateKeys.PAYLOAD_TYPE))) continue;
                String phase = payload.getString("mysteryPhase");
                BlockPos boxPos = ZombiesMysteryBoxVisualLayout.boxPosition(payload, state.position());
                if (boxPos == null) continue;
                Vec3 anchor = Vec3.atBottomCenterOf(boxPos).add(0.0D, ZombiesMysteryBoxVisualLayout.EFFECT_BASE_HEIGHT, 0.0D);
                if (cameraPos.distanceToSqr(anchor) > 24.0D * 24.0D) continue;
                Vec3 relative = anchor.subtract(cameraPos);
                pose.pushPose();
                try {
                    pose.translate(relative.x, relative.y, relative.z);
                    int beamColor = ZombiesMysteryBoxBeam.color(phase, payload.getString("rarityId"));
                    if (beamColor != 0) {
                        renderBeam(pose, buffers, beamColor, level.getGameTime(), event.getPartialTick());
                    }
                    // Preserve the existing weapon phases, including cooldown, while
                    // preventing a completed roll's preview from appearing over an idle box.
                    if (!"IDLE".equals(phase) && !phase.isBlank()) {
                        renderWeapon(mc, level, pose, buffers, payload, phase, event.getPartialTick());
                    }
                } finally {
                    pose.popPose();
                }
            }
            buffers.endBatch();
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void renderBeam(PoseStack pose, MultiBufferSource buffers, int color,
            long gameTime, float partialTick) {
        // The vertices are centered on the box already; no vanilla half-block
        // translation or time-dependent model rotation is applied here.
        ZombiesMysteryBoxBeam.emitLayer(pose.last(),
                buffers.getBuffer(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false)),
                false, color, gameTime, partialTick);
        ZombiesMysteryBoxBeam.emitLayer(pose.last(),
                buffers.getBuffer(RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, true)),
                true, color, gameTime, partialTick);
    }

    private static void renderWeapon(Minecraft mc, ClientLevel level, PoseStack pose,
            MultiBufferSource buffers, CompoundTag payload, String phase, float partialTick) {
        int frame = 9;
        if ("ROLLING".equals(phase)) {
            long tick = level.getGameTime() - payload.getLong("rollStartTick");
            frame = Math.max(0, Math.min(9, (int) (tick / 10L)));
        }
        ListTag previews = payload.getList("previewGunIds", Tag.TAG_STRING);
        if (frame >= previews.size()) return;
        ItemStack stack = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules(previews.getString(frame));
        if (stack.isEmpty()) return;

        // Isolate every weapon transform from the beam and other boxes.
        pose.pushPose();
        try {
            pose.translate(0, ZombiesMysteryBoxVisualLayout.WEAPON_FLOAT_OFFSET
                    + Mth.sin((level.getGameTime() + partialTick) * 0.12F)
                    * ZombiesMysteryBoxVisualLayout.WEAPON_BOB_AMPLITUDE, 0);
            pose.mulPose(Axis.YP.rotationDegrees((level.getGameTime() + partialTick) * 4.0F));
            pose.translate(0, ZombiesMysteryBoxVisualLayout.WEAPON_DISPLAY_OFFSET, 0);
            float scale = ZombiesMysteryBoxVisualLayout.WEAPON_SCALE;
            pose.scale(scale, scale, scale);
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT,
                    0, pose, buffers, level, 0);
        } finally {
            pose.popPose();
        }
    }
}
