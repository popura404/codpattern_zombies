package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.nbt.AttachmentItemDataAccessor;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.attachment.AttachmentData;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;

/**
 * Zombies owns one reserve pool, stored in the gun's root TaCZ tags.
 * Reads never initialize ammunition. Only a newly granted gun is initialized;
 * refills change the existing root reserve and leave every other tag intact.
 */
public final class ZombiesBaseGunAmmoService {
    private static final ZombiesErrorCode UNAVAILABLE = ZombiesErrorCode.of("ammo.base_reserve_unavailable");
    private static final ZombiesErrorCode NO_CAPACITY = ZombiesErrorCode.of("ammo.no_reserve_capacity");
    private static final ZombiesErrorCode ALREADY_FULL = ZombiesErrorCode.of("ammo.already_full");
    private static final ZombiesErrorCode ITEM_UNAVAILABLE = ZombiesErrorCode.of("weapon.item_unavailable");

    private ZombiesBaseGunAmmoService() {
    }

    public static ReserveAmmo readReserve(ItemStack stack) {
        if (!isGun(stack)) {
            return new ReserveAmmo(ReserveStatus.INVALID, 0, 0);
        }
        CompoundTag root = stack.getTag();
        if (root == null) {
            return new ReserveAmmo(ReserveStatus.MISSING, 0, 0);
        }
        if (!root.contains(GunItemDataAccessor.GUN_ID_TAG)) {
            return new ReserveAmmo(ReserveStatus.MISSING, 0, 0);
        }
        if (!root.contains(GunItemDataAccessor.GUN_ID_TAG, Tag.TAG_STRING)) {
            return new ReserveAmmo(ReserveStatus.INVALID, 0, 0);
        }
        String rawGunId = root.getString(GunItemDataAccessor.GUN_ID_TAG);
        ResourceLocation gunId = rawGunId.isBlank() ? null : ResourceLocation.tryParse(rawGunId);
        if (gunId == null || DefaultAssets.EMPTY_GUN_ID.equals(gunId)) {
            return new ReserveAmmo(ReserveStatus.INVALID, 0, 0);
        }
        boolean hasCurrent = root.contains(GunItemDataAccessor.GUN_DUMMY_AMMO);
        boolean hasMaximum = root.contains(GunItemDataAccessor.GUN_MAX_DUMMY_AMMO);
        if ((hasCurrent && !root.contains(GunItemDataAccessor.GUN_DUMMY_AMMO, Tag.TAG_INT))
                || (hasMaximum && !root.contains(GunItemDataAccessor.GUN_MAX_DUMMY_AMMO, Tag.TAG_INT))) {
            return new ReserveAmmo(ReserveStatus.INVALID, 0, 0);
        }
        if (!hasCurrent || !hasMaximum) {
            return new ReserveAmmo(ReserveStatus.MISSING, 0, 0);
        }
        int current = root.getInt(GunItemDataAccessor.GUN_DUMMY_AMMO);
        int maximum = root.getInt(GunItemDataAccessor.GUN_MAX_DUMMY_AMMO);
        return new ReserveAmmo(validAmounts(current, maximum) ? ReserveStatus.VALID : ReserveStatus.INVALID,
                current, maximum);
    }

    /**
     * Resolves standard TaCZ base capacity, including the installed extended magazine.
     * It deliberately does not use current-mode gun accessors or dynamic ammunition utilities.
     */
    public static ZombiesServiceResult<BaseGunInfo> resolveBaseGun(ItemStack stack) {
        if (!isGun(stack) || stack.getTag() == null) {
            return unavailableGun("missing_base_gun");
        }
        CompoundTag root = stack.getTag();
        if (!root.contains(GunItemDataAccessor.GUN_ID_TAG, Tag.TAG_STRING)) {
            return unavailableGun("missing_base_gun_id");
        }
        String rawGunId = root.getString(GunItemDataAccessor.GUN_ID_TAG);
        ResourceLocation gunId = rawGunId.isBlank() ? null : ResourceLocation.tryParse(rawGunId);
        if (gunId == null || DefaultAssets.EMPTY_GUN_ID.equals(gunId)) {
            return unavailableGun("invalid_base_gun_id");
        }
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null || index.getGunData() == null) {
            return unavailableGun("missing_base_gun_resource");
        }
        GunData data = index.getGunData();
        int capacity = magazineAmmo(root, data);
        if (capacity <= 0 || data.getBolt() == null) {
            return unavailableGun("invalid_base_magazine_resource");
        }
        return ZombiesServiceResult.success(new BaseGunInfo(
                gunId.toString(), Objects.requireNonNullElse(index.getType(), ""),
                capacity, data.getBolt() != Bolt.OPEN_BOLT));
    }

    /** Initialize only newly granted guns, after their attachment preset has been applied. */
    public static ZombiesServiceResult<ReserveAmmo> initialize(ItemStack stack, int current, int maximum) {
        if (!validAmounts(current, maximum)) {
            return ZombiesServiceResult.failure(UNAVAILABLE);
        }
        ZombiesServiceResult<BaseGunInfo> resolved = resolveBaseGun(stack);
        if (!resolved.success() || resolved.value().isEmpty()) {
            return ZombiesServiceResult.failure(resolved.code(), resolved.params(), resolved.logMessage());
        }
        BaseGunInfo info = resolved.value().orElseThrow();
        CompoundTag root = stack.getTag();
        // All validation precedes mutation. A copied root also preserves unrelated opaque state.
        CompoundTag initialized = root.copy();
        initialized.putInt(GunItemDataAccessor.GUN_CURRENT_AMMO_COUNT_TAG, info.magazineAmmo());
        initialized.putBoolean(GunItemDataAccessor.GUN_HAS_BULLET_IN_BARREL, info.hasBulletInBarrel());
        initialized.putInt(GunItemDataAccessor.GUN_DUMMY_AMMO, current);
        initialized.putInt(GunItemDataAccessor.GUN_MAX_DUMMY_AMMO, maximum);
        stack.setTag(initialized);
        return ZombiesServiceResult.success(new ReserveAmmo(ReserveStatus.VALID, current, maximum));
    }

    /** Called in the server's inventory transaction; an outdated observation never writes. */
    public static ZombiesServiceResult<ReserveAmmo> refill(ItemStack stack, ReserveAmmo expected) {
        ReserveAmmo actual = readReserve(stack);
        if (expected == null || !expected.valid() || !actual.valid() || !expected.equals(actual)) {
            return ZombiesServiceResult.failure(UNAVAILABLE);
        }
        if (actual.maximum() == 0) {
            return ZombiesServiceResult.failure(NO_CAPACITY);
        }
        if (actual.current() == actual.maximum()) {
            return ZombiesServiceResult.failure(ALREADY_FULL);
        }
        stack.getTag().putInt(GunItemDataAccessor.GUN_DUMMY_AMMO, actual.maximum());
        return ZombiesServiceResult.success(new ReserveAmmo(
                ReserveStatus.VALID, actual.maximum(), actual.maximum()));
    }

    /** Mirrors live reserve values without substituting a stale runtime cache on failure. */
    public static ZombiesWeaponInstanceState withReserve(ZombiesWeaponInstanceState state, ReserveAmmo reserve) {
        Objects.requireNonNull(state, "state");
        boolean valid = reserve != null && reserve.valid();
        return new ZombiesWeaponInstanceState(
                state.gunId(), state.rarityId(), state.weaponLevel(), state.upgradeLevel(),
                state.levelDamageMultiplier(), state.upgradeDamageMultiplier(),
                valid ? reserve.current() : 0, valid ? reserve.maximum() : 0);
    }

    private static boolean isGun(ItemStack stack) {
        return stack != null && !stack.isEmpty() && IGun.getIGunOrNull(stack) != null;
    }

    private static boolean validAmounts(int current, int maximum) {
        return current >= 0 && maximum >= 0 && current <= maximum;
    }

    static int magazineAmmo(CompoundTag root, GunData data) {
        int base = data.getAmmoAmount();
        int[] extended = data.getExtendedMagAmmoAmount();
        if (base < 0) {
            return -1;
        }
        if (extended == null || data.getAllowAttachments() == null
                || !data.getAllowAttachments().contains(AttachmentType.EXTENDED_MAG)) {
            return base;
        }
        String key = GunItemDataAccessor.GUN_ATTACHMENT_BASE + AttachmentType.EXTENDED_MAG.name();
        if (!root.contains(key, Tag.TAG_COMPOUND)) {
            return base;
        }
        CompoundTag attachment = root.getCompound(key);
        if (!attachment.contains("tag", Tag.TAG_COMPOUND)) {
            return -1;
        }
        CompoundTag attachmentTag = attachment.getCompound("tag");
        ResourceLocation attachmentId = AttachmentItemDataAccessor.getAttachmentIdFromTag(attachmentTag);
        if (DefaultAssets.isEmptyAttachmentId(attachmentId)) {
            return -1;
        }
        AttachmentData attachmentData = data.getExclusiveAttachments() == null ? null
                : data.getExclusiveAttachments().get(attachmentId);
        if (attachmentData == null) {
            attachmentData = TimelessAPI.getCommonAttachmentIndex(attachmentId)
                    .map(index -> index.getData()).orElse(null);
        }
        if (attachmentData == null) {
            return -1;
        }
        int level = Math.min(3, Math.max(0, attachmentData.getExtendedMagLevel()));
        if (level == 0) {
            return base;
        }
        return extended.length < level ? -1 : extended[level - 1];
    }

    private static ZombiesServiceResult<BaseGunInfo> unavailableGun(String reason) {
        return ZombiesServiceResult.failure(ITEM_UNAVAILABLE,
                Map.of("reason", ModePlayerValue.ofString(reason)), "Zombies base gun unavailable: " + reason);
    }

    public enum ReserveStatus {
        VALID, MISSING, INVALID
    }

    public record ReserveAmmo(ReserveStatus status, int current, int maximum) {
        public boolean valid() {
            return status == ReserveStatus.VALID && validAmounts(current, maximum);
        }
    }

    public record BaseGunInfo(String gunId, String gunType, int magazineAmmo, boolean hasBulletInBarrel) {
    }
}
