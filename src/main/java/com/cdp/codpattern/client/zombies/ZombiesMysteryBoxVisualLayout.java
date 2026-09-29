package com.cdp.codpattern.client.zombies;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Shared box-relative geometry for the floating weapon and its overhead label. */
public final class ZombiesMysteryBoxVisualLayout {
    public static final double EFFECT_BASE_HEIGHT = 1.15D;
    public static final double WEAPON_FLOAT_OFFSET = 0.08D;
    public static final double WEAPON_BOB_AMPLITUDE = 0.05D;
    public static final double WEAPON_DISPLAY_OFFSET = 0.35D;
    public static final float WEAPON_SCALE = 0.65F;
    // The weapon center reaches 1.63 blocks. Keep the label's lower line above the
    // scaled model, with extra clearance for bobbing; never move it between phases.
    public static final double LABEL_HEIGHT = 3.0D;

    private ZombiesMysteryBoxVisualLayout() { }

    public static BlockPos boxPosition(CompoundTag payload, BlockPos fallback) {
        // The synced state's position is an interaction point, not necessarily the box.
        if (payload.contains("boxPosX", Tag.TAG_ANY_NUMERIC)
                && payload.contains("boxPosY", Tag.TAG_ANY_NUMERIC)
                && payload.contains("boxPosZ", Tag.TAG_ANY_NUMERIC)) {
            return new BlockPos(payload.getInt("boxPosX"), payload.getInt("boxPosY"), payload.getInt("boxPosZ"));
        }
        return fallback;
    }
}
