package com.cdp.codpattern.app.zombies.service;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.object.*;
import com.cdp.codpattern.app.zombies.model.ZombiesGamePhase;
import com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real player/inventory entrypoint in the isolated Forge GameTest runtime. */
public final class ZombiesBarrierGroupRulesPurchaseCompatTest {
    private static final String KEY="minecraft:tripwire_hook{BarrierKey:\"group10\"}";
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JsonObject policy(){
        JsonObject json=JsonParser.parseString("""
                {"schemaVersion":2,"groups":{
                "10":{"spawnGroupChanges":{"enable":[2],"disable":[0]},"playerSpawnGroupChanges":{"enable":[7],"disable":[0]},"entries":{"1":{"cost":1000},"2":{"cost":0},"3":{"cost":500}}},
                "20":{"entries":{"1":{"cost":0}}},
                "21":{"spawnGroupChanges":{"enable":[0],"disable":[2]},"playerSpawnGroupChanges":{"enable":[0],"disable":[7]},"entries":{"1":{"cost":200}}},
                "30":{"entries":{"1":{"cost":9999}}}
                }}
                """).getAsJsonObject();
        var entries=json.getAsJsonObject("groups").getAsJsonObject("10").getAsJsonObject("entries");
        entries.getAsJsonObject("2").addProperty("requiredItem",KEY);
        entries.getAsJsonObject("3").addProperty("requiredItem",KEY);
        return json;
    }
    private static ItemStack key(String value){
        ItemStack stack=new ItemStack(Items.TRIPWIRE_HOOK,3);
        stack.getOrCreateTag().putString("BarrierKey",value);return stack;
    }
    private static List<ItemStack> bag(ServerPlayer player){
        List<ItemStack> result=new ArrayList<>();
        for(int i=0;i<player.getInventory().getContainerSize();i++)result.add(player.getInventory().getItem(i).copy());
        return result;
    }
    private static void sameBag(ServerPlayer player,List<ItemStack> before){
        for(int i=0;i<before.size();i++){
            ItemStack a=before.get(i),b=player.getInventory().getItem(i);
            check(a.getCount()==b.getCount() && ((a.isEmpty()&&b.isEmpty())||ItemStack.isSameItemSameTags(a,b)),"inventory changed in slot "+i);
        }
    }
    public static void run(ServerLevel level){
        try(Fixture f=new Fixture(level)){
            check(f.groups.snapshot().equals(Set.of(0)),"initial group zero");
            var before=bag(f.player);var result=f.buy(f.a);
            check(result.success() && result.value().orElseThrow().cost()==1000 && result.value().orElseThrow().entryId()==1,"entry 1 price and receipt");
            sameBag(f.player,before);check(f.points(f.player)==2000,"entry 1 charges once");
            check(f.barriers.stream().filter(b->b.group()==10).allMatch(f.store::isBarrierCleared),"all same-group entries open together");
            check(f.groups.snapshot().equals(Set.of(2)),"explicit actions; no implicit zombie group 10");
            check(f.playerGroups.snapshot().equals(Set.of(7)),"player groups are independent of zombie groups and barrier IDs");
            check(f.notifications==1 && f.pointsAtNotification==2000,"notification after charge");
            ServerPlayer other=f.actor(0);var otherBag=bag(other);var repeat=f.service.purchase(other,f.b);
            check(!repeat.success() && repeat.code().key().equals("barrier.already_cleared"),"already-open wins over another player's missing key or points");
            sameBag(other,otherBag);
            check(f.notifications==1 && f.points(other)==0 && f.points(f.player)==2000,"repeat never charges or replays actions");
            check(f.playerGroups.snapshot().equals(Set.of(7)),"repeat never changes player groups");
            check(f.buy(f.plain).success() && f.groups.snapshot().equals(Set.of(2)) && f.points(f.player)==2000,"group 20 entry 1 is independent; empty actions no-op");
            check(f.buy(f.restore).success() && f.groups.snapshot().equals(Set.of(0)) && f.points(f.player)==1800,"cross-group actions follow actual order");
            check(f.playerGroups.snapshot().equals(Set.of(0)),"later barrier restores player group zero");
            rejectUnchanged(f,f.expensive,ZombiesErrorCode.ECONOMY_NOT_ENOUGH_POINTS);
        }
        try(Fixture f=new Fixture(level)){
            f.players.getOrCreate(f.player.getUUID()).spendPoints(3000);
            rejectUnchanged(f,f.b,ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING);
            ItemStack wrong=new ItemStack(Items.STICK,3);wrong.getOrCreateTag().putString("BarrierKey","group10");
            f.player.getInventory().setItem(0,wrong);rejectUnchanged(f,f.b,ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING);
            f.player.getInventory().setItem(0,key("wrong"));rejectUnchanged(f,f.b,ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING);
            f.player.getInventory().setItem(0,key("group10"));var before=bag(f.player);var result=f.buy(f.b);
            check(result.success() && result.value().orElseThrow().cost()==0 && result.value().orElseThrow().entryId()==2,"entry 2 key-only unlock");
            sameBag(f.player,before);check(f.points(f.player)==0 && f.player.getInventory().getItem(0).getCount()==3,"key not consumed");
        }
        try(Fixture f=new Fixture(level)){
            rejectUnchanged(f,f.c,ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING);
            f.player.getInventory().setItem(0,key("group10"));f.players.getOrCreate(f.player.getUUID()).spendPoints(2501);
            rejectUnchanged(f,f.c,ZombiesErrorCode.ECONOMY_NOT_ENOUGH_POINTS);
            f.economy.addPoints(f.player.getUUID(),1);var before=bag(f.player);var result=f.buy(f.c);
            check(result.success() && result.value().orElseThrow().cost()==500 && result.value().orElseThrow().entryId()==3 && f.points(f.player)==0,"entry 3 requires BOTH key and points");
            sameBag(f.player,before);
        }
        for(int entryId:new int[]{0,999})try(Fixture f=new Fixture(level)){
            rejectUnchanged(f,f.add("missing-entry-"+entryId,10,entryId),null);
            check(f.points(f.player)==3000,"missing entry never falls back to entry 1");
        }
        try(Fixture f=new Fixture(level)){rejectUnchanged(f,f.add("missing-group",40,1),null);}
        try(Fixture f=new Fixture(level)){
            var invalid=policy();invalid.getAsJsonObject("groups").getAsJsonObject("10").getAsJsonObject("entries").getAsJsonObject("2").addProperty("requiredItem","missing_mod:missing_key");
            f.rules.set(ZombiesBarrierGroupsConfig.parse(invalid.toString(),Path.of("invalid-item/barrier_groups.json")));
            rejectUnchanged(f,f.b,ZombiesErrorCode.BARRIER_REQUIRED_ITEM_MISSING);
        }
        try(Fixture f=new Fixture(level)){
            var invalid=policy();invalid.getAsJsonObject("groups").getAsJsonObject("10").getAsJsonObject("spawnGroupChanges").add("enable",JsonParser.parseString("[99]"));
            f.rules.set(ZombiesBarrierGroupsConfig.parse(invalid.toString(),Path.of("invalid-spawn/barrier_groups.json")));rejectUnchanged(f,f.a,null);
            invalid=policy();invalid.getAsJsonObject("groups").getAsJsonObject("10").getAsJsonObject("spawnGroupChanges").add("disable",JsonParser.parseString("[2]"));
            f.rules.set(ZombiesBarrierGroupsConfig.parse(invalid.toString(),Path.of("conflict/barrier_groups.json")));rejectUnchanged(f,f.a,null);
        }
        for (String actions : List.of("{\"enable\":[99]}", "{\"disable\":[0]}", "{\"enable\":[7],\"disable\":[7]}")) {
            try (Fixture f = new Fixture(level)) {
                var invalid = policy();
                invalid.getAsJsonObject("groups").getAsJsonObject("10").add("playerSpawnGroupChanges", JsonParser.parseString(actions));
                f.rules.set(ZombiesBarrierGroupsConfig.parse(invalid.toString(), Path.of("invalid-player-spawn/barrier_groups.json")));
                rejectUnchanged(f, f.a, actions.equals("{\"disable\":[0]}")
                        ? ZombiesErrorCode.of("barrier.no_active_player_spawns") : null);
            }
        }
        try (Fixture f = new Fixture(level)) {
            var policy = policy();
            policy.getAsJsonObject("groups").getAsJsonObject("20").add("playerSpawnGroupChanges",
                    JsonParser.parseString("{\"disable\":[7]}"));
            f.rules.set(ZombiesBarrierGroupsConfig.parse(policy.toString(), Path.of("last-active-group/barrier_groups.json")));
            check(f.buy(f.a).success(), "unlock player group seven before testing the last active group");
            rejectUnchanged(f, f.plain, ZombiesErrorCode.of("barrier.no_active_player_spawns"));
        }
        try (Fixture f = new Fixture(level)) {
            var legacy = policy();
            legacy.getAsJsonObject("groups").getAsJsonObject("10").remove("playerSpawnGroupChanges");
            f.rules.set(ZombiesBarrierGroupsConfig.parse(legacy.toString(), Path.of("legacy/barrier_groups.json")));
            check(f.buy(f.a).success() && f.groups.snapshot().equals(Set.of(2)) && f.playerGroups.snapshot().equals(Set.of(0)),
                    "old zombie-only rules leave player group zero active");
        }
        playerSpawnSelection(level);
        System.out.println("PASS V2_PURCHASE: inventory, atomic points, independent player groups, invalid/all-disabled rejection and filtered respawn assignment");
    }
    private static void playerSpawnSelection(ServerLevel level) {
        var zero = new ZombiesInitialSpawnData(level.dimension(), new BlockPos(0, 64, 0), 0, 0);
        var sevenA = new ZombiesInitialSpawnData(level.dimension(), new BlockPos(7, 64, 0), 90, 0, 7);
        var sevenB = new ZombiesInitialSpawnData(level.dimension(), new BlockPos(8, 64, 0), 180, 0, 7);
        var points = List.of(sevenA, zero, sevenB);
        var groups = new ZombiesActiveSpawnGroupService();
        var start = ZombiesSpawnAssignmentService.activePlayerSpawnPoints(points, groups.snapshot());
        check(start.size() == 1 && start.get(0).getPosition().equals(zero.pos()), "startup only uses group zero even when a locked point comes first");
        groups.apply(new ZombiesSpawnGroupChanges(Set.of(7), Set.of(0)));
        var respawn = ZombiesSpawnAssignmentService.activePlayerSpawnPoints(points, groups.snapshot());
        var first = UUID.randomUUID(); var second = UUID.randomUUID(); var third = UUID.randomUUID();
        var plan = new ZombiesSpawnAssignmentService().assignFromInitialSpawns(respawn, List.of(first, second, third)).value().orElseThrow();
        check(plan.assignments().get(1).spawnPoint().orElseThrow().getPosition().equals(sevenB.pos()), "dead second member receives second enabled point");
        check(plan.assignments().get(2).spawnPoint().orElseThrow().getPosition().equals(sevenA.pos()), "respawn allocation wraps only enabled points");
        check(ZombiesSpawnAssignmentService.activePlayerSpawnPoints(points, Set.of()).isEmpty(), "no fallback into disabled player groups");
        groups.resetToInitial();
        check(ZombiesSpawnAssignmentService.activePlayerSpawnPoints(points, groups.snapshot()).get(0).getPosition().equals(zero.pos()), "new round resets to initial player group");
    }
    private static void rejectUnchanged(Fixture f,ZombiesBarrierData barrier,ZombiesErrorCode code){
        double points=f.points(f.player);var before=bag(f.player);var groups=f.groups.snapshot();var playerGroups=f.playerGroups.snapshot();int notifications=f.notifications;
        var result=f.buy(barrier);check(!result.success(),"expected failure for "+barrier.objectId());
        if(code!=null)check(result.code().equals(code),"unexpected error: "+result.code());
        sameBag(f.player,before);
        check(f.points(f.player)==points && f.groups.snapshot().equals(groups) && f.playerGroups.snapshot().equals(playerGroups) && f.notifications==notifications && !f.store.isBarrierCleared(barrier),"failure mutated state");
    }
    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final ZombiesPlayerStateService players=new ZombiesPlayerStateService();
        final ZombiesEconomyService economy=new ZombiesEconomyService(players);
        final ZombiesObjectStateStore store=new ZombiesObjectStateStore();
        final ZombiesActiveSpawnGroupService groups=new ZombiesActiveSpawnGroupService();
        final ZombiesActiveSpawnGroupService playerGroups=new ZombiesActiveSpawnGroupService();
        final Set<UUID> members=new HashSet<>();final List<ServerPlayer> actors=new ArrayList<>();
        final List<ZombiesBarrierData> barriers=new ArrayList<>();
        final AtomicReference<ZombiesBarrierGroupsConfig> rules=new AtomicReference<>(ZombiesBarrierGroupsConfig.parse(policy().toString(),Path.of("barrier_groups.json")));
        final ServerPlayer player;final ZombiesBarrierData a,b,c,plain,restore,expensive;
        final ZombiesBarrierService service;int notifications;double pointsAtNotification;
        Fixture(ServerLevel level){
            this.level=level;player=actor(3000);
            a=add("points",10,1);b=add("key",10,2);c=add("both",10,3);add("key-peer",10,2);
            plain=add("plain",20,1);restore=add("restore",21,1);expensive=add("expensive",30,1);
            var spawns=List.of(new ZombiesZombieSpawnData("s0",0,1,level.dimension(),BlockPos.ZERO,0,0),new ZombiesZombieSpawnData("s2",2,1,level.dimension(),BlockPos.ZERO,0,0));
            service=new ZombiesBarrierService(RoomId.of("zombies","entry-check-"+UUID.randomUUID()),()->barriers,economy,store,groups,members::contains,
                    ()->ZombiesGamePhase.WAVE_ACTIVE,result->{notifications++;pointsAtNotification=points(player);},()->spawns,rules::get,
                    playerGroups, () -> List.of(new ZombiesInitialSpawnData(level.dimension(), BlockPos.ZERO, 0, 0),
                            new ZombiesInitialSpawnData(level.dimension(), new BlockPos(7,64,0), 0, 0, 7)));
        }
        ServerPlayer actor(int points){
            ServerPlayer actor=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"barrier-v2"));
            actor.getInventory().clearContent();members.add(actor.getUUID());actors.add(actor);economy.addPoints(actor.getUUID(),points);return actor;
        }
        ZombiesBarrierData add(String id,int group,int entryId){
            BlockPos pos=new BlockPos(barriers.size()*3,64,0);
            var barrier=new ZombiesBarrierData(id,id,group,99999,true,level.dimension(),pos,pos.above(),pos,"minecraft:diamond",new ZombiesSpawnGroupChanges(Set.of(99),Set.of()),entryId);
            barriers.add(barrier);store.resetBarriers(barriers);return barrier;
        }
        ZombiesServiceResult<ZombiesBarrierService.BarrierPurchaseResult> buy(ZombiesBarrierData barrier){return service.purchase(player,barrier);}
        double points(ServerPlayer actor){return players.getOrCreate(actor.getUUID()).points();}
        public void close(){for(ServerPlayer actor:actors)actor.getInventory().clearContent();}
    }
}
