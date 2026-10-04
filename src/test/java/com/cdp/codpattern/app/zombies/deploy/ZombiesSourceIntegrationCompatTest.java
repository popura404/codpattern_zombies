package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.object.ZombiesPowerSwitchData;
import com.cdp.codpattern.app.zombies.model.*;
import com.cdp.codpattern.app.zombies.service.*;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;

/** Focused regression runner; SOURCE_ROOT points at the source tree being checked. */
public final class ZombiesSourceIntegrationCompatTest {
    static void require(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    static String facing(ZombiesPowerSwitchData data) throws Exception {
        try { return String.valueOf(data.getClass().getMethod("facing").invoke(data)); }
        catch (NoSuchMethodException missing) { return "<absent>"; }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int failures = 0;
        for (var slot : ZombiesEquipmentSlot.values()) {
            var id = UUID.randomUUID();
            var players = new ZombiesPlayerStateService();
            var economy = new ZombiesEconomyService(players);
            var service = new ZombiesUltimateMachineService(economy, new ZombiesPowerService(economy));
            var state = players.getOrCreate(id);
            var w = ZombiesWeaponInstanceState.wallPrimary("tacz:m4a1", "epic", 1, 1.6D, 180);
            switch (slot) {
                case STARTER -> state.setStarterWeapon(w);
                case PRIMARY -> state.setPrimaryWeapon(w);
                case MYSTERY_BOX -> state.setMysteryBoxWeapon(w);
            }
            economy.addPoints(id, 5000);
            var rules = new com.cdp.codpattern.config.zombies.ZombiesRulesConfig.UltimateMachine();
            rules.setMaxUpgradeLevel(2);
            rules.setLevels(Map.of("1", new com.cdp.codpattern.config.zombies.ZombiesRulesConfig.UpgradeLevel(500,2.3D),
                "2", new com.cdp.codpattern.config.zombies.ZombiesRulesConfig.UpgradeLevel(900,3.7D)));
            var wrong = ZombiesWeaponInstanceState.wallPrimary("tacz:ak47", "epic", 1,1.6D,180);
            require(!service.upgradeHeldWeapon(id,slot,wrong,rules,false,null).success() && state.points()==5000,"wrong gun must not charge");
            var first = service.upgradeHeldWeapon(id,slot,roundTrip(w),rules,false,null);
            System.out.println("EPIC "+slot+" first="+first.code()+" points="+state.points());
            if (!first.success()) { failures++; continue; }
            var second = service.upgradeHeldWeapon(id,slot,roundTrip(first.value().orElseThrow().weapon()),rules,false,null);
            require(second.success() && state.points()==3600,"second level and cost");
            require(second.value().orElseThrow().weapon().reserveAmmo()==7,"keep live ammo");
            var max = service.upgradeHeldWeapon(id,slot,roundTrip(second.value().orElseThrow().weapon()),rules,false,null);
            require(max.code().equals(ZombiesErrorCode.WEAPON_MAX_UPGRADE) && state.points()==3600,"max rejects without charge");
            System.out.println("PASS EPIC "+slot+": 1.6/2.3 float round trips, ammo, costs and maximum");
        }
        // Static contracts complement compilation; these are not a live HUD render test.
        String hud = Files.readString(root.resolve("src/main/java/com/cdp/codpattern/client/gui/overlay/zombies/ZombiesHudOverlay.java"));
        boolean starter = hud.contains("(rarityId.isBlank() && !starterWeapon)")
                && hud.contains("boolean starterWeapon = ZombiesEquipmentSlot.STARTER.key()")
                && hud.contains(".equals(tag.getString(ZombiesWeaponItemStackService.TAG_SLOT).trim())");
        System.out.println("STARTER HUD source gate="+starter);
        if(!starter) failures++;
        require(hud.contains("data.rarity().map(ZombiesRarityDisplay.Entry::color).orElse(0xFF6B7280)"),"neutral gray fallback");
        require(hud.contains("TaczClientApi.resolveReserveAmmo(stack)") && hud.contains("upgradeRoman(upgradeLevel)"),"live ammo and upgrade display");

        net.minecraft.SharedConstants.tryDetectVersion();
        try { net.minecraft.server.Bootstrap.bootStrap(); }
        catch(Throwable bootstrap) {
            Throwable cause=bootstrap; while(cause.getCause()!=null) cause=cause.getCause();
            if(!(cause instanceof NoSuchMethodException) || !cause.getMessage().contains("net.minecraftforge.network.NetworkEvent.<init>")) throw bootstrap;
            System.out.println("FIXTURE: vanilla registries initialized; Forge networking unavailable outside transformed launcher");
        }
        var json=JsonParser.parseString("{\"objectId\":\"power\",\"dimension\":\"minecraft:overworld\",\"pos\":[1,64,1]}").getAsJsonObject();
        boolean directional = false;
        for(String d:List.of("north","east","south","west")) {
            var input=json.deepCopy(); input.addProperty("facing",d);
            var data=ZombiesPowerSwitchData.CODEC.parse(JsonOps.INSTANCE,input).result().orElseThrow();
            directional=d.equals(facing(data));
            System.out.println("POWER requested="+d+" actual="+facing(data));
            if(!directional) {failures++;continue;}
            var encoded=ZombiesPowerSwitchData.CODEC.encodeStart(JsonOps.INSTANCE,data).result().orElseThrow();
            require(ZombiesPowerSwitchData.CODEC.parse(JsonOps.INSTANCE,encoded).result().orElseThrow().equals(data),"codec roundtrip");
        }
        if(directional) {
            require(facing(ZombiesPowerSwitchData.CODEC.parse(JsonOps.INSTANCE,json).result().orElseThrow()).equals("north"),"legacy north");
            for(var invalid:List.of(new JsonPrimitive("up"),new JsonPrimitive("down"),new JsonPrimitive("bad"),new JsonPrimitive(90))) {
                var bad=json.deepCopy();bad.add("facing",invalid);
                require(ZombiesPowerSwitchData.CODEC.parse(JsonOps.INSTANCE,bad).error().isPresent(),"invalid facing rejected");
            }
            var objects=ZombiesMapObjects.EMPTY;
            for(String d:List.of("north","east","south","west")) {
                var fields=new HashMap<>(ZombiesDeployFieldSchema.defaultFields("power_switch"));fields.put("facing",d);
                var edit=ZombiesDeployObjectEditor.edit(objects,objects.powerSwitch().isEmpty()?ZombiesDeployObjectEditor.Operation.ADD:ZombiesDeployObjectEditor.Operation.UPDATE,"power_switch",0,fields);
                require(edit.success() && facing(edit.objects().powerSwitch().orElseThrow()).equals(d),"editor direction");
                require(d.equals(edit.fields().get("facing")),"editor export");objects=edit.objects();
            }
            // Exercise the actual Minecraft state implementation, not state/world doubles.
            // This disposable JVM has no mod registration phase; reopen only its local block registry.
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            var unfreeze = registry.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(registry);
            var block=new com.cdp.codpattern.common.block.ZombiesPowerSwitchBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of());
            var f=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
            var powered=com.cdp.codpattern.common.block.ZombiesPowerSwitchBlock.POWERED;
            require(block.getStateDefinition().getPossibleStates().size()==8,"eight states");
            for(var state:block.getStateDefinition().getPossibleStates()) {
                for(var r:net.minecraft.world.level.block.Rotation.values()) {
                    var changed=state.rotate(r);
                    require(changed.getValue(f)==r.rotate(state.getValue(f)) && changed.getValue(powered)==state.getValue(powered),"rotation keeps power");
                }
                for(var m:net.minecraft.world.level.block.Mirror.values()) {
                    var changed=state.mirror(m);
                    require(changed.getValue(f)==m.getRotation(state.getValue(f)).rotate(state.getValue(f)) && changed.getValue(powered)==state.getValue(powered),"mirror keeps power");
                }
                require(block.getSignal(state,null,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.NORTH)==(state.getValue(powered)?15:0),"redstone unchanged");
            }
            System.out.println("PASS POWER: four codecs, legacy default, invalid inputs, editor, eight real block states, rotation/mirror/redstone");
        }
        var variants=JsonParser.parseString(Files.readString(root.resolve("src/main/resources/assets/codpattern/blockstates/zombies_power_switch.json"))).getAsJsonObject().getAsJsonObject("variants");
        System.out.println("POWER model variants="+variants.size());
        if(variants.size()!=8) failures++;
        else {
            int y=0;
            for(String d:List.of("north","east","south","west")) {
                for(String p:List.of("false","true")) {
                    var v=variants.getAsJsonObject("facing="+d+",powered="+p);
                    require(v.get("y").getAsInt()==y,"model rotation");
                    require(v.get("model").getAsString().equals("codpattern:block/zombies_power_switch"+(p.equals("true")?"_powered":"")),"power model");
                }
                y+=90;
            }
        }
        System.out.println("TOTAL_FEATURE_FAILURES="+failures);
        if(failures!=0) System.exit(1);
    }
    static ZombiesWeaponInstanceState roundTrip(ZombiesWeaponInstanceState w) {
        return new ZombiesWeaponInstanceState(w.gunId(),w.rarityId(),w.weaponLevel(),w.upgradeLevel(),
            (double)(float)w.levelDamageMultiplier(),(double)(float)w.upgradeDamageMultiplier(),7,w.maxReserveAmmo());
    }
}
