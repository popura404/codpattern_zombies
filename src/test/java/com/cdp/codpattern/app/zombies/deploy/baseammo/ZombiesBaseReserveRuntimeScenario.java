package com.cdp.codpattern.app.zombies.deploy.baseammo;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.model.*;
import com.cdp.codpattern.app.zombies.service.*;
import com.cdp.codpattern.compat.tacz.TaczGatewayProvider;
import com.cdp.codpattern.config.zombies.ZombiesBackpackConfig;
import com.cdp.codpattern.config.zombies.ZombiesRulesConfig;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end service regression using actual TaCZ stacks and reload extraction.
 * The Arcana branch is opt-in and pins the shipped Arcana 1.1.4 loader-hybrid runtime:
 * it needs the official Vanguard pack exposing ts:weapon_gp25 for tacz:ak47.
 * Reflection below calls only the verified original install and mode-setting entry points;
 * it belongs to test fixtures and is never included in the production addon.
 * Both branches exercise initialization, upgrade, snapshot/revive, paid refill and stale commits.
 */
public final class ZombiesBaseReserveRuntimeScenario {
    private static final RoomId ROOM = RoomId.of("zombies", "isolated_base_ammo_acceptance");
    private static final ZombiesEquipmentSlot SLOT = ZombiesEquipmentSlot.PRIMARY;
    public static Map<String,Object> run(ServerPlayer player, boolean arcana) throws Exception {
        Map<String,Object> evidence = new LinkedHashMap<>();
        player.getInventory().clearContent();
        player.getInventory().selected = SLOT.defaultInventorySlot();
        ItemStack gun = GunItemBuilder.create().setId(new ResourceLocation("tacz:ak47"))
                .setAmmoCount(0).setAmmoInBarrel(false).build();
        check(!gun.isEmpty() && IGun.getIGunOrNull(gun) != null, "Real AK47 fixture exists");
        player.getInventory().setItem(SLOT.defaultInventorySlot(), gun);
        IGun api = IGun.getIGunOrNull(gun);
        Object extras = null;
        Class<?> extrasType = null;
        if (arcana) {
            var grip = AttachmentItemBuilder.create().setId(new ResourceLocation("ts:weapon_gp25")).build();
            check(api.allowAttachment(gun, grip), "Actual Vanguard AK47 permits GP25");
            player.getInventory().setItem(9, grip.copy());
            Class<?> managerType = Class.forName("group.taczexpands.dist.jlkX1CVq");
            Object manager = managerType.getField("RtLWCp8E").get(null);
            boolean installed = (boolean) managerType.getMethod("nwLBQEs8", AttachmentType.class,
                    ItemStack.class, Integer.class, ItemStack.class, ServerPlayer.class, boolean.class, Inventory.class)
                    .invoke(manager, AttachmentType.GRIP, grip.copy(), Integer.valueOf(9), gun, player, false, player.getInventory());
            check(installed, "Original Arcana GunManager installs GP25");
            extrasType = Class.forName("group.taczexpands.dist.cSFSPogO");
            extras = extrasType.getField("lYcekq0C").get(null);
            check(extrasType.getMethod("SMuDyDJd", ItemStack.class).invoke(extras, gun) != null,
                    "Actual runtime resolves the GP25 underbarrel definition");
            mode(extrasType, extras, gun, true);
            TaczGatewayProvider.gateway().setReserveAmmo(gun, 19, 19);
            api.setCurrentAmmoCount(gun, 1);
            api.setBulletInBarrel(gun, true);
            gun.getOrCreateTag().getCompound("Extras").putString("BaseAmmoOpaqueProbe", "preserve-this");
            check(!TaczGatewayProvider.gateway().resolveGunId(gun).orElse("").equals("tacz:ak47"), "GP25 actually active");
        }
        gun.getOrCreateTag().putString("BaseAmmoOpaqueRoot", "preserve-root");
        CompoundTag extrasBefore = extrasTag(gun);
        int activeMagazineBefore = api.getCurrentAmmoCount(gun);
        boolean activeChamberBefore = api.hasBulletInBarrel(gun);
        // Initialize a newly granted gun while the real underbarrel is selected.
        success(ZombiesBaseGunAmmoService.initialize(gun,120,120), "new gun base initialization");
        int baseMagazine = ZombiesBaseGunAmmoService.resolveBaseGun(gun).value().orElseThrow().magazineAmmo();
        check(gun.getTag().getInt("GunCurrentAmmoCount") == baseMagazine, "Initialization uses root AK47 magazine capacity");
        check(baseMagazine > 1, "Root magazine is not GP25 capacity");
        assertExtras(gun, extrasBefore, "initialization");
        if (arcana) {
            check(api.getCurrentAmmoCount(gun) == activeMagazineBefore && api.hasBulletInBarrel(gun) == activeChamberBefore,
                    "Initialization leaves active GP25 magazine and chamber unchanged");
            check(TaczGatewayProvider.gateway().resolveReserveAmmo(gun)==19 && TaczGatewayProvider.gateway().resolveMaxReserveAmmo(gun)==19,
                    "Initialization leaves active GP25 reserve unchanged");
            var starter = new ZombiesStarterKitDistributor(ZombiesBackpackConfig::defaults).createStarterWeapon(
                    new ZombiesBackpackConfig.StarterWeapon("tacz:modern_kinetic_gun",1,gun.getTag().toString(),""));
            success(starter,"starter with active GP25 NBT");
            ItemStack issued = starter.value().orElseThrow();
            assertRoot(issued,360,360,"starter resolves root rifle category");
            assertExtras(issued,extrasBefore,"starter initialization");
            check(issued.getTag().getInt("GunCurrentAmmoCount")==baseMagazine,"Starter fills root magazine");
            evidence.put("starterActiveGp25RootReserve","360/360");
        }
        var tags = new ZombiesWeaponItemStackService();
        var initial = ZombiesWeaponInstanceState.wallPrimary("tacz:ak47","common",1,1.0,120);
        success(tags.writeWeaponTags(gun,ROOM,SLOT,initial),"tag owned AK47");
        if (arcana) mode(extrasType,extras,gun,false);
        check(((AbstractGunItem)gun.getItem()).findAndExtractDummyAmmo(gun,47)==47,"Native reload extraction consumes root reserve");
        if (arcana) mode(extrasType,extras,gun,true);
        assertRoot(gun,73,120,"actual root consumption");
        assertExtras(gun,extrasBefore,"mode round trip");
        CompoundTag beforeRead = gun.getTag().copy();
        var read = tags.readWeaponTags(gun).value().orElseThrow();
        check(gun.getTag().equals(beforeRead),"Metadata read is side-effect free");
        var current = ZombiesBaseGunAmmoService.withReserve(read.toWeaponState(),ZombiesBaseGunAmmoService.readReserve(gun));
        check(current.reserveAmmo()==73 && current.maxReserveAmmo()==120,"Live state reads base reserve in either mode");
        var players = new ZombiesPlayerStateService();
        var runtime = players.getOrCreate(player.getUUID());
        runtime.setPrimaryWeapon(initial); // Deliberately stale full reserve cache.
        var economy = new ZombiesEconomyService(players);
        economy.addPoints(player.getUUID(),100000);
        var inventory = new ZombiesWeaponInventoryService();
        var upgraded = new ZombiesUltimateMachineService(economy,new ZombiesPowerService(economy))
                .upgradeHeldWeapon(player.getUUID(),SLOT,current,new ZombiesRulesConfig().getUltimateMachine(),false,
                        (oldState,newState) -> inventory.syncMainHandWeapon(player,ROOM,player.getMainHandItem(),read,newState));
        success(upgraded,"actual held weapon upgrade service chain");
        assertRoot(gun,73,120,"upgrade must not refill");
        assertExtras(gun,extrasBefore,"upgrade");
        check(runtime.primaryWeapon().orElseThrow().reserveAmmo()==73,"Upgrade runtime mirrors consumed base reserve");
        runtime.setPrimaryWeapon(runtime.primaryWeapon().orElseThrow().withReserveAmmo(120));
        var snapshot = new ZombiesEquipmentSnapshotService().captureInventorySnapshot(ROOM,player.getInventory(),runtime);
        var slotSnapshot = snapshot.slot(SLOT).orElseThrow();
        check(slotSnapshot.weaponState().reserveAmmo()==73,"Snapshot overrides stale runtime with physical base reserve");
        assertRoot(slotSnapshot.itemStack(),73,120,"snapshot copy");
        assertExtras(slotSnapshot.itemStack(),extrasBefore,"snapshot");
        player.getInventory().clearContent();
        success(new ZombiesReviveLoadoutService().restoreSnapshot(ROOM,player,runtime,snapshot),"actual revive restore");
        gun = player.getInventory().getItem(SLOT.defaultInventorySlot());
        assertRoot(gun,73,120,"revive must not refill");
        assertExtras(gun,extrasBefore,"revive");
        check(runtime.primaryWeapon().orElseThrow().reserveAmmo()==73,"Revive cache retains consumed root reserve");
        var prepared = inventory.prepareAmmoRefill(ROOM,gun);
        success(prepared,"prepare current base refill");
        var pending = prepared.value().orElseThrow();
        check(pending.reserve().current()==73 && pending.reserve().maximum()==120,"Refill preparation captures root reserve");
        double pointsBeforeRefill = runtime.points();
        var refill = new ZombiesAmmoBoxService(economy).refillHeldWeapon(player.getUUID(),SLOT,pending.weaponState(),Map.of(1,1),
                (oldState,newState) -> inventory.commitAmmoRefill(player,ROOM,pending));
        success(refill,"actual paid refill service chain");
        check(runtime.points()==pointsBeforeRefill-1,"Successful root refill deducts exactly its price");
        assertRoot(gun,120,120,"refill changes root reserve");
        assertExtras(gun,extrasBefore,"refill");
        check(runtime.primaryWeapon().orElseThrow().reserveAmmo()==120,"Refill updates correct slot cache");
        check(tags.readWeaponTags(gun).value().orElseThrow().reserveAmmo()==120,"Refill mirror records root reserve");
        if (arcana) {
            check(TaczGatewayProvider.gateway().resolveReserveAmmo(gun)==19 && TaczGatewayProvider.gateway().resolveMaxReserveAmmo(gun)==19,
                    "Paid refill does not refill active GP25");
            // The opposite case: root full, subweapon empty. It must not buy root ammo.
            TaczGatewayProvider.gateway().setReserveAmmo(gun,0,19);
            extrasBefore = extrasTag(gun);
        }
        double pointsBeforeDenied = runtime.points();
        check(!inventory.prepareAmmoRefill(ROOM,gun).success(),"Full root rejects refill even with empty GP25");
        check(runtime.points()==pointsBeforeDenied,"Full-root refusal does not charge");
        assertRoot(gun,120,120,"full-root refusal");
        assertExtras(gun,extrasBefore,"full-root refusal");
        ItemStack zero = gun.copy();
        success(ZombiesBaseGunAmmoService.initialize(zero,0,0),"zero capacity fixture");
        check(!inventory.prepareAmmoRefill(ROOM,zero).success(),"Explicit root zero capacity rejects refill");
        check(runtime.points()==pointsBeforeDenied,"Zero-capacity refusal does not charge");
        assertExtras(zero,extrasBefore,"zero-capacity refusal");
        if (arcana) mode(extrasType,extras,gun,false);
        check(((AbstractGunItem)gun.getItem()).findAndExtractDummyAmmo(gun,13)==13,"Root native reload extraction consumes refilled ammo");
        assertRoot(gun,107,120,"base reload after refill");
        if (arcana) mode(extrasType,extras,gun,true);
        assertExtras(gun,extrasBefore,"reload mode round trip");
        var stale = inventory.prepareAmmoRefill(ROOM,gun).value().orElseThrow();
        if (arcana) mode(extrasType,extras,gun,false);
        check(((AbstractGunItem)gun.getItem()).findAndExtractDummyAmmo(gun,1)==1,"Concurrent root consumption fixture");
        if (arcana) mode(extrasType,extras,gun,true);
        double pointsBeforeStale = runtime.points();
        var refused = new ZombiesAmmoBoxService(economy).refillHeldWeapon(player.getUUID(),SLOT,stale.weaponState(),Map.of(1,1),
                (oldState,newState) -> inventory.commitAmmoRefill(player,ROOM,stale));
        check(!refused.success(),"Stale prepared reserve is rejected");
        check(runtime.points()==pointsBeforeStale,"Stale commit refusal does not charge");
        assertRoot(gun,106,120,"stale refill does not change root reserve");
        assertExtras(gun,extrasBefore,"stale refill rejection");
        // Leave the real client with synchronized, current root metadata and active GP25 for visual inspection.
        var finalState = ZombiesBaseGunAmmoService.withReserve(tags.readWeaponTags(gun).value().orElseThrow().toWeaponState(),
                ZombiesBaseGunAmmoService.readReserve(gun));
        success(inventory.syncWeaponMetadata(gun,ROOM,SLOT,finalState),"final metadata mirror");
        player.inventoryMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
        evidence.put("arcana",arcana);
        evidence.put("rootGunId",gun.getTag().getString("GunId"));
        evidence.put("activeGunId",TaczGatewayProvider.gateway().resolveGunId(gun).orElse(""));
        evidence.put("rootReserve",gun.getTag().getInt("DummyAmmo"));
        evidence.put("rootMaximum",gun.getTag().getInt("MaxDummyAmmo"));
        evidence.put("activeReserve",TaczGatewayProvider.gateway().resolveReserveAmmo(gun));
        evidence.put("activeMaximum",TaczGatewayProvider.gateway().resolveMaxReserveAmmo(gun));
        evidence.put("baseMagazine",baseMagazine);
        evidence.put("extrasUnchanged",extrasTag(gun).equals(extrasBefore));
        evidence.put("upgradeSnapshotRevivePreservedReserve",73);
        evidence.put("refillRootReserve",120);
        evidence.put("nativeReloadExtracted",13);
        evidence.put("staleRefillRejected",true);
        evidence.put("fullRootEmptySubRejectedWithoutCharge",true);
        evidence.put("zeroCapacityRejectedWithoutCharge",true);
        evidence.put("baseMissingSubFullRefillSucceeded",arcana);
        evidence.put("inventorySlot",SLOT.defaultInventorySlot());
        evidence.put("extrasNbt",extrasTag(gun).toString());
        System.out.println("[ZOMBIES-BASE-AMMO] PASS "+evidence);
        return evidence;
    }
    private static void mode(Class<?> type,Object extras,ItemStack gun,boolean active) throws Exception {
        type.getMethod("sTzrnRrP",ItemStack.class,boolean.class).invoke(extras,gun,active);
    }
    static CompoundTag extrasTag(ItemStack gun) {return gun.getOrCreateTag().getCompound("Extras").copy();}
    private static void assertExtras(ItemStack gun,CompoundTag expected,String stage) {
        check(extrasTag(gun).equals(expected),stage+" changed opaque Extras/subweapon/mode state");
        check(gun.getTag().getString("BaseAmmoOpaqueRoot").equals("preserve-root"),stage+" changed opaque root state");
    }
    private static void assertRoot(ItemStack gun,int ammo,int maximum,String stage) {
        var value=ZombiesBaseGunAmmoService.readReserve(gun);
        check(value.valid()&&value.current()==ammo&&value.maximum()==maximum,stage+": expected "+ammo+"/"+maximum+" got "+value);
    }
    private static void success(ZombiesServiceResult<?> result,String stage) {
        check(result.success(),stage+": "+result.code()+" "+result.logMessage());
    }
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
