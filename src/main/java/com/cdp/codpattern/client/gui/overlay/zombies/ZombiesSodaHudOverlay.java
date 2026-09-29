package com.cdp.codpattern.client.gui.overlay.zombies;

import com.cdp.codpattern.client.zombies.ClientZombiesState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Draws the local survivor's synchronized soda effects using the v10 artwork. */
final class ZombiesSodaHudOverlay {
    private static final int SOURCE_SIZE = 32;
    private static final Map<String, ResourceLocation> ICONS = Map.of(
            "double_health", icon("double_health"),
            "speed_boost", icon("speed_boost"),
            "reactive_explosion", icon("reactive_explosion"),
            "double_ammo", icon("double_ammo"),
            "score_multiplier", icon("score_multiplier"),
            "headshot_damage", icon("headshot_damage"));

    private ZombiesSodaHudOverlay() {
    }

    static void render(GuiGraphics graphics, int screenWidth, int screenHeight,
                       List<ZombiesSodaHudLayout.Bounds> occupied) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui
                || !minecraft.player.isAlive() || minecraft.player.isSpectator()) {
            return;
        }
        List<ResourceLocation> textures = ClientZombiesState.visibleSodaBuffIds().stream()
                .map(ICONS::get).filter(Objects::nonNull).toList();
        Optional<ZombiesSodaHudLayout.Placement> placement =
                ZombiesSodaHudLayout.create(screenWidth, screenHeight, textures.size(), occupied);
        if (placement.isEmpty()) {
            return;
        }

        ZombiesSodaHudLayout.Placement layout = placement.get();
        graphics.flush();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        ShaderInstance previousShader = RenderSystem.getShader();
        int previousShaderTexture = RenderSystem.getShaderTexture(0);
        int previousBoundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int previousSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int previousDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int previousSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int previousDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            for (int i = 0; i < textures.size(); i++) {
                ResourceLocation location = textures.get(i);
                AbstractTexture texture = minecraft.getTextureManager().getTexture(location);
                texture.setBlurMipmap(false, false);
                try {
                    graphics.blit(location, layout.iconX(i), layout.top(), layout.iconSize(), layout.iconSize(),
                            0.0F, 0.0F, SOURCE_SIZE, SOURCE_SIZE, SOURCE_SIZE, SOURCE_SIZE);
                } finally {
                    texture.restoreLastBlurMipmap();
                }
            }
        } finally {
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderTexture(0, previousShaderTexture);
            RenderSystem.bindTexture(previousBoundTexture);
            RenderSystem.blendFuncSeparate(previousSourceRgb, previousDestinationRgb,
                    previousSourceAlpha, previousDestinationAlpha);
            if (previousBlend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
        }
    }

    private static ResourceLocation icon(String buffId) {
        return ResourceLocation.fromNamespaceAndPath("codpattern", "textures/gui/zombies/soda/" + buffId + ".png");
    }
}
