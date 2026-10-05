package com.cdp.codpattern.event.client.zombies;

import com.cdp.codpattern.client.zombies.ZombiesRarityDisplay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Fixed, box-centered beacon geometry. Only the texture coordinates depend on time. */
final class ZombiesMysteryBoxBeam {
    private static final int WHITE = 0xFFFFFFFF;
    private static final float HEIGHT = 24.0F;
    private static final float CORE_RADIUS = 0.12F;
    private static final float CORE_HALF_WIDTH = CORE_RADIUS / (float) Math.sqrt(2.0D);
    private static final float GLOW_RADIUS = 0.35F;

    private ZombiesMysteryBoxBeam() { }

    /** Zero means hidden; reward colors only become visible when the reward is claimable. */
    static int color(String phase, String rarityId) {
        if ("ROLLING".equals(phase)) return 0;
        if ("CLAIMABLE".equals(phase)) {
            return ZombiesRarityDisplay.fromRarityId(rarityId)
                    .map(ZombiesRarityDisplay.Entry::color).orElse(WHITE);
        }
        return WHITE;
    }

    static void emitLayer(PoseStack.Pose pose, VertexConsumer vertices, boolean glow,
            int color, long gameTime, float partialTick) {
        // Match the vanilla beacon's upward UV scroll without its time-dependent rotation.
        float time = Math.floorMod(gameTime, 40) + partialTick;
        float scroll = -time * 0.2F;
        float bottomV = -1.0F + scroll - (float) Math.floor(scroll);
        float topV = bottomV + HEIGHT * (glow ? 1.0F : 0.5F / CORE_RADIUS);
        float halfWidth = glow ? GLOW_RADIUS : CORE_HALF_WIDTH;
        // The vanilla inner diamond at -45 degrees is an axis-aligned square.
        // Keep the glow's inward winding so the far shell produces the original halo.
        float frontZ = glow ? -halfWidth : halfWidth;
        float red = ((color >> 16) & 255) / 255.0F;
        float green = ((color >> 8) & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        float alpha = glow ? 0.125F : 1.0F;
        quad(pose, vertices, -halfWidth, frontZ, halfWidth, frontZ, topV, bottomV, red, green, blue, alpha);
        quad(pose, vertices, halfWidth, frontZ, halfWidth, -frontZ, topV, bottomV, red, green, blue, alpha);
        quad(pose, vertices, halfWidth, -frontZ, -halfWidth, -frontZ, topV, bottomV, red, green, blue, alpha);
        quad(pose, vertices, -halfWidth, -frontZ, -halfWidth, frontZ, topV, bottomV, red, green, blue, alpha);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vertices,
            float x1, float z1, float x2, float z2, float topV, float bottomV,
            float red, float green, float blue, float alpha) {
        vertex(pose, vertices, x1, HEIGHT, z1, 1.0F, topV, red, green, blue, alpha);
        vertex(pose, vertices, x1, 0.0F, z1, 1.0F, bottomV, red, green, blue, alpha);
        vertex(pose, vertices, x2, 0.0F, z2, 0.0F, bottomV, red, green, blue, alpha);
        vertex(pose, vertices, x2, HEIGHT, z2, 0.0F, topV, red, green, blue, alpha);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices,
            float x, float y, float z, float u, float v, float red, float green, float blue, float alpha) {
        vertices.vertex(pose.pose(), x, y, z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F)
                .endVertex();
    }
}
