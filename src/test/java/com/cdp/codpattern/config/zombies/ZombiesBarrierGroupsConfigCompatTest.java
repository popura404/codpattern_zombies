package com.cdp.codpattern.config.zombies;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.*;
import com.cdp.codpattern.config.storage.MapStoragePaths;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.nio.file.*;
import java.util.*;

public final class ZombiesBarrierGroupsConfigCompatTest {
    private static final Path WORKSPACE=Path.of(System.getProperty("codpattern.test.workspace", ".")).toAbsolutePath();
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static JsonObject tree(int cost){
        JsonObject json=JsonParser.parseString("""
                {"schemaVersion":2,"groups":{
                  "10":{"spawnGroupChanges":{"enable":[2],"disable":[0]},"entries":{
                    "1":{"cost":1000},"2":{"cost":0,"requiredItem":"minecraft:tripwire_hook"},
                    "3":{"cost":500,"requiredItem":"minecraft:tripwire_hook"}}},
                  "20":{"entries":{"1":{"cost":0}}}
                }}
                """).getAsJsonObject();
        entry(json,"10","1").addProperty("cost",cost);return json;
    }
    private static JsonObject group(JsonObject json,String id){return json.getAsJsonObject("groups").getAsJsonObject(id);}
    private static JsonObject entry(JsonObject json,String group,String id){return group(json,group).getAsJsonObject("entries").getAsJsonObject(id);}
    public static void main(String[] args)throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        Path parent=WORKSPACE.resolve("build/verification/barrier-group-rules/v2/fixtures");Files.createDirectories(parent);
        Path dir=Files.createTempDirectory(parent,"config-");Path path=dir.resolve("barrier_groups.json");
        var empty=ZombiesBarrierGroupsConfig.load(path);var template=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        check(empty.templateCreated() && empty.errors().isEmpty() && empty.groups().isEmpty(),"empty template only");
        check(template.get("schemaVersion").getAsInt()==2 && template.getAsJsonObject("groups").size()==0,"v2 template");
        String v1="{\"schemaVersion\":1,\"groups\":{\"10\":{\"cost\":123}}}";
        Files.writeString(path,v1);check(!ZombiesBarrierGroupsConfig.load(path).errors().isEmpty(),"v1 requires manual conversion");
        check(Files.readString(path).equals(v1),"v1 never overwritten");
        List<String> invalid=new ArrayList<>(List.of("", "[]", "{", "{\"schemaVersion\":2,\"groups\":[]}",
                "{\"schemaVersion\":2,\"groups\":{\"10\":{\"entries\":{\"1\":{\"cost\":1},\"1\":{\"cost\":0}}}}}",
                "{\"schemaVersion\":2,\"groups\":{\"10\":{\"entries\":{}},\"10\":{\"entries\":{}}}}",
                "{\"schemaVersion\":2,\"groups\":{\"10\":{\"entries\":{\"1\":{\"cost\":1,\"cost\":2}}}}}"));
        for(String cost:List.of("-1","1.5","2147483648","\"0\"","null","false")){
            var j=tree(1000);entry(j,"10","1").add("cost",JsonParser.parseString(cost));invalid.add(j.toString());
        }
        for(String id:List.of("0","-1","01","2147483648")){
            var j=tree(1000);var entries=group(j,"10").getAsJsonObject("entries");entries.add(id,entries.remove("1"));invalid.add(j.toString());
            j=tree(1000);var groups=j.getAsJsonObject("groups");groups.add(id,groups.remove("10"));invalid.add(j.toString());
        }
        var j=tree(1000);entry(j,"10","1").remove("cost");invalid.add(j.toString());
        j=tree(1000);group(j,"10").addProperty("cost",1000);invalid.add(j.toString());
        j=tree(1000);group(j,"10").addProperty("requiredItem","minecraft:stone");invalid.add(j.toString());
        j=tree(1000);entry(j,"10","1").add("spawnGroupChanges",new JsonObject());invalid.add(j.toString());
        j=tree(1000);entry(j,"10","1").addProperty("requiredItem",42);invalid.add(j.toString());
        j=tree(1000);group(j,"10").getAsJsonObject("spawnGroupChanges").add("disable",JsonParser.parseString("[2]"));invalid.add(j.toString());
        j=tree(1000);group(j,"10").getAsJsonObject("spawnGroupChanges").add("enable",JsonParser.parseString("[-1]"));invalid.add(j.toString());
        invalid.add(tree(1000)+" {}");
        for(String bad:invalid){Files.writeString(path,bad);check(!ZombiesBarrierGroupsConfig.load(path).errors().isEmpty(),"invalid file accepted: "+bad);check(Files.readString(path).equals(bad),"invalid file overwritten");}
        Files.writeString(path,tree(1000).toString());var first=ZombiesBarrierGroupsConfig.load(path);check(first.errors().isEmpty(),"valid v2 rules");
        var legacy=new ZombiesBarrierData("a","A",10,17,true,Level.OVERWORLD,BlockPos.ZERO,BlockPos.ZERO.above(),BlockPos.ZERO,"invalid_legacy:item",new ZombiesSpawnGroupChanges(Set.of(99),Set.of()));
        var a=legacy.withEntryId(1);
        var b=new ZombiesBarrierData("b","B",10,9999,true,Level.OVERWORLD,new BlockPos(3,0,0),new BlockPos(3,1,0),new BlockPos(3,0,0),"minecraft:diamond",ZombiesSpawnGroupChanges.NONE,2);
        var spawns=List.of(new ZombiesZombieSpawnData("s0",0,1,Level.OVERWORLD,BlockPos.ZERO,0,0),new ZombiesZombieSpawnData("s2",2,1,Level.OVERWORLD,BlockPos.ZERO,0,0));
        check(legacy.entryId()==0 && first.resolve(legacy).cost()==-1 && first.resolve(legacy).requiredItem().isEmpty(),"legacy entry stays unselected without fallback");
        check(first.bindingIssues(List.of(legacy),spawns).stream().anyMatch(x->x.code().key().equals("map.missing_barrier_entry_rules")),"unselected entry blocks startup");
        check(first.bindingIssues(List.of(a,b),spawns).isEmpty(),"distinct conditions in same group allowed");
        check(first.rule(10,1).orElseThrow().cost()==1000 && first.rule(20,1).orElseThrow().cost()==0,"group-scoped entries");
        check(first.rule(10,999).isEmpty() && first.rule(999,1).isEmpty(),"no cross-entry fallback");
        check(first.resolve(a).requiredItem().isEmpty() && first.resolve(b).requiredItem().equals("minecraft:tripwire_hook"),"file overrides legacy item");
        var objects=new ZombiesMapObjects(List.of(),spawns,List.of(a,b),List.of(),List.of(),List.of(),Optional.empty(),List.of(),List.of(),List.of(),List.of());
        var frozen=first.resolveObjects(objects);check(frozen.barriers().get(0).cost()==1000 && frozen.barriers().get(1).cost()==0,"entry prices");
        var snapshot=ZombiesMapSnapshot.fromMapObjects(RoomId.of("zombies","v2-config"),"v2-config",false,frozen);
        check(snapshot.barriers().get(0).entryId()==1 && snapshot.barriers().get(1).entryId()==2,"snapshot preserves entry ids");
        Files.writeString(path,tree(2000).toString());var next=ZombiesBarrierGroupsConfig.load(path);
        check(first.rule(10,1).orElseThrow().cost()==1000 && next.rule(10,1).orElseThrow().cost()==2000 && frozen.barriers().get(0).cost()==1000,"frozen policy unchanged");
        try{first.groups().clear();throw new AssertionError("mutable groups");}catch(UnsupportedOperationException expected){}
        try{first.rule(10).orElseThrow().entries().clear();throw new AssertionError("mutable entries");}catch(UnsupportedOperationException expected){}
        try{first.rule(10).orElseThrow().spawnGroupChanges().enable().add(99);throw new AssertionError("mutable actions");}catch(UnsupportedOperationException expected){}
        Path other=dir.resolve("other/barrier_groups.json");Files.createDirectories(other.getParent());Files.writeString(other,tree(333).toString());
        check(ZombiesBarrierGroupsConfig.load(other).rule(10,1).orElseThrow().cost()==333 && first.rule(10,1).orElseThrow().cost()==1000,"map isolation");
        check(!first.bindingIssues(List.of(a),List.of(spawns.get(0))).isEmpty(),"unknown spawn rejected");
        for(String item:List.of("missing_mod:missing_key","minecraft:tripwire_hook{broken")){
            j=tree(1000);entry(j,"10","2").addProperty("requiredItem",item);var deferred=ZombiesBarrierGroupsConfig.parse(j.toString(),path);
            check(deferred.errors().isEmpty(),"defer registry validation");check(deferred.bindingIssues(List.of(a),spawns).stream().anyMatch(x->x.code().key().equals("map.invalid_barrier_entry_item")),"invalid unused item detected in preflight");
        }
        var encoded=ZombiesBarrierData.CODEC.encodeStart(JsonOps.INSTANCE,b).result().orElseThrow().getAsJsonObject();
        check(encoded.get("entryId").getAsInt()==2 && !encoded.has("cost") && !encoded.has("requiredItem") && !encoded.has("spawnGroupChanges"),"save binding, omit legacy policy");
        encoded.remove("entryId");encoded.addProperty("cost",123);encoded.addProperty("requiredItem","minecraft:diamond");encoded.add("spawnGroupChanges",JsonParser.parseString("{\"enable\":[99]}"));
        var decoded=ZombiesBarrierData.CODEC.parse(JsonOps.INSTANCE,encoded).result().orElseThrow();check(decoded.entryId()==0 && decoded.cost()==123 && first.resolve(decoded).cost()==-1,"legacy decode remains unselected");
        var storage=new MapStoragePaths(dir.resolve("world"),dir.resolve("game"));check(!storage.rules("zombies","A").equals(storage.rules("zombies","a")),"map path isolation");
        Path migration=dir.resolve("migration");ZombiesConfigRepository.loadResult(migration,"migration");Path migrated=migration.resolve("barrier_groups.json");
        Files.delete(migrated);ZombiesStorageMigration.validate(migration);check(!Files.exists(migrated),"relocation never generates rules");
        Files.writeString(migrated,v1);try{ZombiesStorageMigration.validate(migration);throw new AssertionError("v1 accepted");}catch(IllegalStateException expected){}
        check(Files.readString(migrated).equals(v1),"preserve v1 migration input");
        for(String sample:List.of("barrier_groups.empty.json","barrier_groups.example.json")){Path file=WORKSPACE.resolve("doc/examples").resolve(sample);check(ZombiesBarrierGroupsConfig.parse(Files.readString(file),file).errors().isEmpty(),"example parses");}
        System.out.println("PASS V2_CONFIG: nested rules, manual migration, item preflight, immutable snapshots, isolation and codec compatibility");
    }
}
