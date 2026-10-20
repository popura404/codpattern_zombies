package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.model.ZombiesConnectionState;
import com.cdp.codpattern.app.zombies.model.ZombiesEquipmentSlot;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.app.zombies.model.ZombiesPlayerRuntimeState;
import com.cdp.codpattern.app.zombies.model.ZombiesTeamNames;
import com.cdp.codpattern.app.zombies.model.ZombiesWeaponInstanceState;
import com.cdp.codpattern.app.zombies.runtime.ZombiesRoomRuntimeState;
import com.cdp.codpattern.app.zombies.service.ZombiesBaseGunAmmoService;
import com.cdp.codpattern.app.zombies.service.ZombiesLoginRecoveryContributor;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerRuntimeMarkerService;
import com.cdp.codpattern.app.zombies.service.ZombiesPlayerStateService;
import com.cdp.codpattern.app.zombies.service.ZombiesReconnectRecoveryService;
import com.cdp.codpattern.app.zombies.service.ZombiesReviveLoadoutService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponInventoryService;
import com.cdp.codpattern.app.zombies.service.ZombiesWeaponItemStackService;
import com.cdp.codpattern.compat.fpsmatch.map.zombies.ZombiesMap;
import com.mojang.authlib.GameProfile;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.tacz.guns.item.ModernKineticGunItem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.EnumMap;
import java.util.UUID;

/** Real login recovery after the vanilla player NBT save/load boundary; no network login is simulated. */
@GameTestHolder("codpattern_backpack")
@PrefixGameTestTemplate(false)
public final class ZombiesReconnectAmmoGameTests {
    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void activeRoundLoginPreservesPersistedBaseAmmoAndOpaqueExtras(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var core = FPSMCore.getInstance();
        // The isolated GameTest bootstrap creates an empty FPSM registry, unlike ServerStartedEvent.
        if (!core.checkGameType("zombies")) core.registerGameType("zombies", ZombiesMap::new);
        String name = "ammo-reconnect-" + UUID.randomUUID().toString().substring(0, 8);
        RoomId room = RoomId.of("zombies", name);
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        var map = new ZombiesMap(level, name, new AreaData(pos, pos.offset(8, 8, 8)));
        core.registerMap("zombies", map);
        require(core.getMapByTypeWithName("zombies", name).orElseThrow() == map,
                "Actual login resolver can find the fixture's room");
        var profile = new GameProfile(UUID.randomUUID(), "AmmoReconnect");
        var original = new FakePlayer(level, profile);
        var returned = new FakePlayer(level, profile);
        var markers = ZombiesPlayerRuntimeMarkerService.instance();
        Throwable primaryFailure = null;
        try {
            var roomState = fixtureField(map, "runtimeState", ZombiesRoomRuntimeState.class);
            var players = fixtureField(map, "playerStateService", ZombiesPlayerStateService.class);
            roomState.transitionTo(ZombiesGamePhase.WAVE_ACTIVE);
            map.isStart = true;
            var runtime = players.getOrCreate(profile.getId());
            var staleFullState = new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 1,
                    1.25D, 1.5D, 120, 120);
            runtime.setPrimaryWeapon(staleFullState);
            runtime.addPoints(321);
            var team = map.getMapTeams().getTeamByName(ZombiesTeamNames.SURVIVORS).orElseThrow();
            team.join(original);
            ItemStack gun = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
            require(ZombiesBaseGunAmmoService.initialize(gun, 120, 120).success(), "Actual new gun initializes");
            require(new ZombiesWeaponItemStackService().writeWeaponTags(gun, room, "persisted-primary",
                    ZombiesEquipmentSlot.PRIMARY, staleFullState).success(), "Actual management tags initialize");
            require(((ModernKineticGunItem) gun.getItem()).findAndExtractDummyAmmo(gun, 37) == 37,
                    "Actual TaCZ consumption leaves a partially spent root reserve");
            CompoundTag child = new CompoundTag();
            child.putInt("DummyAmmo", 5);
            child.putInt("MaxDummyAmmo", 23);
            child.putInt("GunCurrentAmmoCount", 1);
            CompoundTag extras = new CompoundTag();
            extras.put("UnderBarrelRoot", child);
            extras.putString("OpaqueReconnectFixture", "preserve-this-entire-compound");
            gun.getOrCreateTag().put("Extras", extras);
            original.getInventory().selected = ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot();
            original.getInventory().setItem(original.getInventory().selected, gun);
            var expectedReserves = new EnumMap<ZombiesEquipmentSlot, Integer>(ZombiesEquipmentSlot.class);
            expectedReserves.put(ZombiesEquipmentSlot.PRIMARY, 83);
            for (var slot : ZombiesEquipmentSlot.values()) {
                if (slot == ZombiesEquipmentSlot.PRIMARY) continue;
                ItemStack other = gun.copy();
                int reserve = 11 + slot.ordinal() * 7;
                require(ZombiesBaseGunAmmoService.initialize(other, 120, 120).success(), "Other actual slot initializes");
                require(new ZombiesWeaponItemStackService().writeWeaponTags(other, room, "persisted-" + slot.key(),
                        slot, staleFullState).success(), "Other slot gets its own managed identity");
                require(((ModernKineticGunItem) other.getItem()).findAndExtractDummyAmmo(other, 120 - reserve) == 120 - reserve,
                        "Each same-id gun consumes a different amount");
                original.getInventory().setItem(slot.defaultInventorySlot(), other);
                setWeapon(runtime, slot, staleFullState);
                expectedReserves.put(slot, reserve);
            }
            markers.writeActiveRoundMarker(original, room, Optional.empty());

            map.onPlayerLoggedOut(original);
            require(runtime.connectionState() == ZombiesConnectionState.OFFLINE, "Real logout marks existing state offline");
            CompoundTag expectedItem = gun.save(new CompoundTag());
            ListTag expectedInventory = original.getInventory().save(new ListTag());
            CompoundTag persistedPlayer = original.saveWithoutId(new CompoundTag());
            require(!persistedPlayer.getList("Inventory", 10).isEmpty(), "Vanilla player serialization includes the weapon");
            returned.load(persistedPlayer);
            require(markers.readMarker(returned).filter(marker -> marker.isActiveRound()
                    && room.equals(marker.roomId())).isPresent(), "Forge persistent marker survives player NBT loading");
            require(returned.getMainHandItem() != gun
                            && returned.getMainHandItem().save(new CompoundTag()).equals(expectedItem),
                    "New player loads an independent, complete persisted item");
            // Force the recovery method to exercise survivor reattachment as well as markOnline.
            team.delPlayer(profile.getId());
            var result = ZombiesLoginRecoveryContributor.recover(returned);
            require(result.outcome() == ZombiesReconnectRecoveryService.RecoveryOutcome.ACTIVE_ROUND_REJOIN,
                    "Real contributor/resolver reaches the active room reconnect method: " + result.outcome());
            require(team.hasPlayer(profile.getId()) && runtime.connectionState() == ZombiesConnectionState.ONLINE,
                    "Returning player rejoins the actual survivor roster and existing runtime state");
            require(players.get(profile.getId()).orElseThrow() == runtime && runtime.points() == 321,
                    "Login keeps the existing player state and points");
            ItemStack restored = returned.getMainHandItem();
            require(restored.save(new CompoundTag()).equals(expectedItem),
                    "Login recovery preserves complete inventory item NBT, including opaque Arcana Extras");
            require(returned.getInventory().save(new ListTag()).equals(expectedInventory),
                    "Refreshing three runtime mirrors never rewrites any persisted inventory NBT");
            var reserve = ZombiesBaseGunAmmoService.readReserve(restored);
            require(reserve.valid() && reserve.current() == 83 && reserve.maximum() == 120,
                    "Stale full runtime reserve cannot refill a persisted partially spent gun");
            for (var slot : ZombiesEquipmentSlot.values()) {
                var expected = ZombiesBaseGunAmmoService.withReserve(staleFullState,
                        new ZombiesBaseGunAmmoService.ReserveAmmo(ZombiesBaseGunAmmoService.ReserveStatus.VALID,
                                expectedReserves.get(slot), 120));
                require(weapon(runtime, slot).equals(expected),
                        "Login rebuilds each same-id gun's own runtime reserve without crossing slots: " + slot);
            }
            var prepared = new ZombiesWeaponInventoryService().prepareAmmoRefill(room, restored).value().orElseThrow();
            require(prepared.reserve().current() == 83 && prepared.weaponState().reserveAmmo() == 83,
                    "The next real refill preparation reads persisted root reserve instead of the old cache");
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                markers.clearMarker(original);
                markers.clearMarker(returned);
                original.getInventory().clearContent();
                returned.getInventory().clearContent();
                map.getMapTeams().removePlayer(profile.getId());
                // The fixture may fail after delPlayer and before recovery rejoins the roster.
                level.getScoreboard().removePlayerFromTeam(original.getScoreboardName());
                core.unregisterMap(map);
                map.getMapTeams().retireCreatedScoreboardTeams();
            } catch (RuntimeException | Error cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
        System.out.println("[ZOMBIES-RECONNECT] PASS actual login recovery refreshed three slot mirrors; root reserve=83/120 and complete item NBT preserved");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "codpattern_backpack", batch = "backpack", timeoutTicks = 100)
    public static void reconnectMirrorsRejectAmbiguousIdentityAndNeverRepairNativeTags(GameTestHelper helper) {
        RoomId room = RoomId.of("zombies", "reconnect-identity");
        var tags = new ZombiesWeaponItemStackService();
        for (int variant = 0; variant < 7; variant++) {
            var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MirrorIdentity"));
            var runtime = new ZombiesPlayerRuntimeState(player.getUUID());
            var stale = new ZombiesWeaponInstanceState("tacz:m4a1", "rare", 9, 3, 2.0D, 1.5D, 120, 120);
            var committed = new ZombiesWeaponInstanceState("tacz:ak47", "rare", 2, 1, 1.25D, 1.5D, 120, 120);
            try {
                for (var slot : ZombiesEquipmentSlot.values()) {
                    ItemStack stack = ZombiesWeaponInventoryService.createDefaultTaczGunStackForRules("tacz:ak47");
                    require(ZombiesBaseGunAmmoService.initialize(stack, 10 + slot.ordinal(), 120).success(),
                            "Identity fixture initializes a real gun");
                    require(tags.writeWeaponTags(stack, room, "identity-" + slot.key(), slot, committed).success(),
                            "Identity fixture writes committed item metadata");
                    player.getInventory().setItem(slot.defaultInventorySlot(), stack);
                    setWeapon(runtime, slot, stale);
                }
                ItemStack primary = player.getInventory().getItem(ZombiesEquipmentSlot.PRIMARY.defaultInventorySlot());
                switch (variant) {
                    case 0 -> primary.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_ROOM_ID, "zombies:other");
                    case 1 -> primary.getOrCreateTag().putString("GunId", "tacz:glock_17");
                    case 2, 6 -> {
                        ItemStack duplicate = primary.copy();
                        duplicate.getOrCreateTag().putString(ZombiesWeaponItemStackService.TAG_INSTANCE_ID, "duplicate-instance");
                        if (variant == 6) duplicate.getOrCreateTag().putString("GunId", "tacz:glock_17");
                        player.getInventory().setItem(8, duplicate);
                    }
                    case 3 -> primary.getOrCreateTag().remove(ZombiesWeaponItemStackService.TAG_INSTANCE_ID);
                    case 4 -> primary.getOrCreateTag().remove("DummyAmmo");
                    case 5 -> primary.getOrCreateTag().putInt("DummyAmmo", 121);
                    default -> throw new AssertionError("Unexpected fixture variant");
                }
                ListTag before = player.getInventory().save(new ListTag());
                new ZombiesReviveLoadoutService().syncRuntimeStateFromInventory(room, player.getInventory(), runtime);
                require(player.getInventory().save(new ListTag()).equals(before),
                        "Mirror rebuild never repairs or changes actual item NBT: " + variant);
                for (var slot : ZombiesEquipmentSlot.values()) {
                    boolean rejectedPrimary = slot == ZombiesEquipmentSlot.PRIMARY && variant != 4 && variant != 5;
                    var expected = rejectedPrimary ? stale : ZombiesBaseGunAmmoService.withReserve(committed,
                            ZombiesBaseGunAmmoService.readReserve(player.getInventory().getItem(slot.defaultInventorySlot())));
                    require(weapon(runtime, slot).equals(expected),
                            "Only an unambiguous matching identity may replace its own cache: " + variant + "/" + slot);
                }
            } finally {
                player.getInventory().clearContent();
            }
        }
        helper.succeed();
    }

    private static ZombiesWeaponInstanceState weapon(ZombiesPlayerRuntimeState runtime, ZombiesEquipmentSlot slot) {
        return switch (slot) {
            case STARTER -> runtime.starterWeapon().orElseThrow();
            case PRIMARY -> runtime.primaryWeapon().orElseThrow();
            case MYSTERY_BOX -> runtime.mysteryBoxWeapon().orElseThrow();
        };
    }

    private static void setWeapon(ZombiesPlayerRuntimeState runtime, ZombiesEquipmentSlot slot, ZombiesWeaponInstanceState weapon) {
        switch (slot) {
            case STARTER -> runtime.setStarterWeapon(weapon);
            case PRIMARY -> runtime.setPrimaryWeapon(weapon);
            case MYSTERY_BOX -> runtime.setMysteryBoxWeapon(weapon);
        }
    }

    private static <T> T fixtureField(ZombiesMap map, String name, Class<T> type) throws Exception {
        var field = ZombiesMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(map));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ZombiesReconnectAmmoGameTests() { }
}
