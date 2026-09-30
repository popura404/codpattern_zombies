package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployFieldSchema;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployPreviewKeys;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import com.cdp.codpattern.common.block.ZombiesBoxInteractionBlock;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.phasetranscrystal.fpsmatch.common.client.FPSMClient;
import com.phasetranscrystal.fpsmatch.common.client.data.RenderablePoint;
import com.phasetranscrystal.fpsmatch.common.item.zombies.ZombiesDeployTool;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/** Draws staged purchase points without writing temporary blocks into either world. */
@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesDeployModelWorldRenderer {
    private static final double MAX_RENDER_DISTANCE_SQR = 64.0D * 64.0D;

    private ZombiesDeployModelWorldRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Camera camera = event.getCamera();
        if (level == null || minecraft.player == null || camera == null || !camera.isInitialized()
                || !(minecraft.player.getMainHandItem().getItem() instanceof ZombiesDeployTool)) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Set<BlockPos> rendered = new HashSet<>();
        // The same points are removed by save/discard/undo/map-switch and held-tool cleanup.
        // No additional client cache can outlive that authoritative preview state.
        for (RenderablePoint point : FPSMClient.getGlobalData().getDebugData().getPoints()) {
            String type = ZombiesDeployPreviewKeys.modelObjectType(point.key(), minecraft.player.getUUID()).orElse("");
            Block block = modelBlock(type);
            if (block == null || cameraPos.distanceToSqr(point.position()) > MAX_RENDER_DISTANCE_SQR) {
                continue;
            }
            BlockPos pos = BlockPos.containing(point.position());
            if (!level.hasChunkAt(pos) || !event.getFrustum().isVisible(new AABB(pos).inflate(0.5D))) {
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (current.is(block) || (!current.isAir() && !current.canBeReplaced()) || !rendered.add(pos)) {
                continue;
            }
            poseStack.pushPose();
            try {
                poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                BlockState previewState = block.defaultBlockState();
                if (block instanceof ZombiesBoxInteractionBlock && Float.isFinite(point.yaw())) {
                    previewState = previewState.setValue(ZombiesBoxInteractionBlock.FACING, Direction.fromYRot(point.yaw()));
                }
                minecraft.getBlockRenderer().renderSingleBlock(previewState, poseStack, buffers,
                        LevelRenderer.getLightColor(level, pos), OverlayTexture.NO_OVERLAY);
            } finally {
                poseStack.popPose();
            }
        }
        if (!rendered.isEmpty()) {
            buffers.endBatch();
        }
    }

    private static Block modelBlock(String type) {
        return switch (type) {
            case ZombiesDeployFieldSchema.WEAPON_WALL -> CodPatternBlockRegister.ZOMBIES_WEAPON_WALL_BOX.get();
            case ZombiesDeployFieldSchema.AMMO_BOX -> CodPatternBlockRegister.ZOMBIES_AMMO_BOX.get();
            case ZombiesDeployFieldSchema.ARMOR_STATION -> CodPatternBlockRegister.ZOMBIES_ARMOR_STATION_BOX.get();
            case ZombiesDeployFieldSchema.POWER_SWITCH -> CodPatternBlockRegister.ZOMBIES_POWER_SWITCH.get();
            case ZombiesDeployFieldSchema.SODA_MACHINE -> CodPatternBlockRegister.ZOMBIES_SODA_MACHINE_BOX.get();
            case ZombiesDeployFieldSchema.ULTIMATE_MACHINE -> CodPatternBlockRegister.ZOMBIES_ULTIMATE_MACHINE_BOX.get();
            case ZombiesDeployFieldSchema.MYSTERY_BOX -> CodPatternBlockRegister.ZOMBIES_MYSTERY_BOX.get();
            default -> null;
        };
    }
}
