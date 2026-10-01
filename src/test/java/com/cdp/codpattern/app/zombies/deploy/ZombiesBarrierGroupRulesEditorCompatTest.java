package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.zombies.map.ZombiesMapObjects;
import com.cdp.codpattern.app.zombies.map.ZombiesMapSnapshot;
import com.cdp.codpattern.app.zombies.map.object.*;
import com.cdp.codpattern.config.zombies.ZombiesBarrierGroupsConfig;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class ZombiesBarrierGroupRulesEditorCompatTest {
    private static final Path WORKSPACE=Path.of(System.getProperty("codpattern.test.workspace", ".")).toAbsolutePath();
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Object invoke(Object target,String name,Object...args)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name,args.length==0?new Class<?>[0]:new Class<?>[]{ZombiesMapObjects.class});
        method.setAccessible(true);return method.invoke(target,args);
    }
    public static void main(String[] args)throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        String type=ZombiesDeployFieldSchema.BARRIER;
        var keys=ZombiesDeployFieldSchema.objectType(type).orElseThrow().fields().stream().map(ZombiesDeployFieldSchema.FieldDefinition::key).toList();
        check(keys.containsAll(List.of("group","entryId","objectId","blocksPlayersOnly","areaFromX","interactionX")),"binding and geometry inputs");
        for(String key:List.of("cost","requiredItem","enableSpawnGroups","disableSpawnGroups"))check(!keys.contains(key),"policy input exposed: "+key);
        check(ZombiesDeployFieldSchema.defaultFields(type).get("entryId").equals("1"),"new object default entry 1");
        var legacy=new ZombiesBarrierData("a","A",10,123,true,Level.OVERWORLD,BlockPos.ZERO,BlockPos.ZERO.above(),BlockPos.ZERO,"minecraft:diamond",new ZombiesSpawnGroupChanges(Set.of(99),Set.of()));
        var peer=new ZombiesBarrierData("b","B",10,456,true,Level.OVERWORLD,new BlockPos(3,0,0),new BlockPos(3,1,0),new BlockPos(3,0,0),"",ZombiesSpawnGroupChanges.NONE,2);
        var spawns=List.of(new ZombiesZombieSpawnData("s0",0,1,Level.OVERWORLD,BlockPos.ZERO,0,0),new ZombiesZombieSpawnData("s2",2,1,Level.OVERWORLD,BlockPos.ZERO,0,0));
        var objects=new ZombiesMapObjects(List.of(),spawns,List.of(legacy,peer),List.of(),List.of(),List.of(),Optional.empty(),List.of(),List.of(),List.of(),List.of());
        var fields=new LinkedHashMap<>(ZombiesDeployObjectEditor.fieldsForSnapshotSelection(objects,type,0));
        check(fields.get("entryId").equals("0") && !fields.containsKey("requiredItem"),"legacy selection remains unselected");
        for(String blocked:List.of("cost","requiredItem","enableSpawnGroups","disableSpawnGroups","spawnGroupChanges")){
            var request=new LinkedHashMap<>(fields);request.put(blocked,"0");
            var rejected=ZombiesDeployObjectEditor.edit(objects,ZombiesDeployObjectEditor.Operation.UPDATE,type,0,request);
            check(!rejected.success() && rejected.objects().equals(objects),"reject obsolete policy field: "+blocked);
        }
        fields.put("name","Renamed");
        var unselected=ZombiesDeployObjectEditor.edit(objects,ZombiesDeployObjectEditor.Operation.UPDATE,type,0,fields);
        check(unselected.success() && unselected.objects().barriers().get(0).entryId()==0,"unselected geometry editable");
        fields.put("entryId","2");var changed=ZombiesDeployObjectEditor.edit(objects,ZombiesDeployObjectEditor.Operation.UPDATE,type,0,fields);
        check(changed.success() && changed.objects().barriers().get(0).entryId()==2 && changed.objects().barriers().get(0).group()==10,"store selected entry");
        check(changed.objects().barriers().get(0).requiredItem().isEmpty() && changed.objects().barriers().get(0).cost()==-1,"no authoritative object policy");
        check(changed.objects().barriers().get(1).equals(peer),"no peer policy rewrites");
        var copied=ZombiesDeployObjectEditor.edit(changed.objects(),ZombiesDeployObjectEditor.Operation.DUPLICATE,type,0,Map.of());
        check(copied.success() && copied.objects().barriers().get(2).entryId()==2 && copied.objects().barriers().get(2).group()==10 && !copied.objects().barriers().get(2).objectId().equals("a"),"copy binding with unique identity");
        Class<?> historyType=Class.forName(ZombiesDeployToolService.class.getName()+"$DraftSession");
        Constructor<?> constructor=historyType.getDeclaredConstructor(ZombiesMapObjects.class);constructor.setAccessible(true);
        Object history=constructor.newInstance(objects);invoke(history,"stage",changed.objects());invoke(history,"undo");
        var undo=(ZombiesMapObjects)invoke(history,"currentObjects");check(undo.barriers().get(0).entryId()==0,"undo restores 0");
        check(ZombiesDeployObjectEditor.fieldsForSnapshotSelection(undo,type,0).get("entryId").equals("0"),"undo fields reflect binding");
        invoke(history,"redo");check(((ZombiesMapObjects)invoke(history,"currentObjects")).barriers().get(0).entryId()==2,"redo restores 2");
        invoke(history,"stage",copied.objects());invoke(history,"undo");check(((ZombiesMapObjects)invoke(history,"currentObjects")).barriers().size()==2,"undo duplication");
        invoke(history,"redo");var redone=(ZombiesMapObjects)invoke(history,"currentObjects");check(redone.barriers().get(2).entryId()==2,"redo duplicate binding");
        var encoded=ZombiesMapObjects.CODEC.codec().encodeStart(JsonOps.INSTANCE,redone).result().orElseThrow();
        var decoded=ZombiesMapObjects.CODEC.codec().parse(JsonOps.INSTANCE,encoded).result().orElseThrow();
        check(decoded.barriers().stream().allMatch(b->b.group()==10 && b.entryId()==2),"save/reload bindings");
        var rulePath=WORKSPACE.resolve("doc/examples/barrier_groups.example.json");
        var rules=ZombiesBarrierGroupsConfig.parse(Files.readString(rulePath),rulePath);
        var movedFields=new LinkedHashMap<>(fields);movedFields.put("group","20");
        var moved=ZombiesDeployObjectEditor.edit(changed.objects(),ZombiesDeployObjectEditor.Operation.UPDATE,type,0,movedFields);
        check(moved.success() && moved.objects().barriers().get(0).entryId()==2,"changing group never auto-selects another entry");
        check(rules.bindingIssues(moved.objects().barriers(),spawns).stream().anyMatch(i->i.code().key().equals("map.missing_barrier_entry_rules")),"unknown pair blocks startup");
        var mixed=new ZombiesMapObjects(List.of(),spawns,List.of(legacy.withEntryId(1),peer),List.of(),List.of(),List.of(),Optional.empty(),List.of(),List.of(),List.of(),List.of());
        var snapshot=ZombiesMapSnapshot.fromMapObjects(RoomId.of("zombies","entry-snapshot"),"entry-snapshot",false,rules.resolveObjects(mixed));
        check(snapshot.barriers().get(0).cost()==1000 && snapshot.barriers().get(1).cost()==0 && snapshot.barriers().get(1).entryId()==2,"distinct entry prices in snapshot");
        var report=new com.cdp.codpattern.app.zombies.validation.ZombiesMapValidator(com.cdp.codpattern.app.zombies.validation.ZombiesMapValidationProfile.MVP3_FULL_INITIAL).validate(snapshot);
        check(report.issues().stream().noneMatch(i->i.message().contains("cost") && i.message().contains("differs")),"different entry prices not rejected");
        Path base=WORKSPACE.resolve("src/main/java/com/cdp/codpattern");
        String validator=Files.readString(base.resolve("app/zombies/validation/ZombiesMapValidator.java"));check(!validator.contains("addBarrierGroupPriceIssues("),"old equal-price constraint removed");
        String service=Files.readString(base.resolve("app/zombies/deploy/ZombiesDeployToolService.java"));int begin=service.indexOf("    private ZombiesDeployServiceResult<ZombiesDeploySnapshot> editObject(");
        check(service.indexOf("hasLegacyBarrierRuleFields(request)",begin)<service.indexOf("normalizeDraft(player, stack, request)",begin) && service.contains("request.fields().containsKey(\"requiredItem\")"),"reject legacy requests before normalization");
        String ui=Files.readString(base.resolve("client/gui/screen/zombies/deploy/ZombiesDeployToolScreen.java"));
        check(ui.contains("String binding = group + \".\" + entryId;") && ui.contains("barrierRuleMetadata(\"requiredItem.\" + binding)"),"pair-keyed summary");
        String map=Files.readString(base.resolve("compat/fpsmatch/map/zombies/ZombiesMap.java"));
        check(map.contains("this::runtimeBarrierGroupRules") && map.contains("if (objectsFrozen) return frozenBarrierGroupRules;") && map.contains("frozenObjects = frozenBarrierGroupRules.resolveObjects(objects);"),"frozen rules for live checks and previews");
        String interaction=Files.readString(base.resolve("app/zombies/service/ZombiesObjectInteractionService.java"));check(interaction.contains("result.value().orElseThrow().entryId()"),"receipt entry reaches message");
        for(String lang:List.of("zh_cn","zh_tw","en_us","ja_jp")){
            var json=JsonParser.parseString(Files.readString(WORKSPACE.resolve("src/main/resources/assets/codpattern_zombies/lang").resolve(lang+".json"))).getAsJsonObject();
            check(json.has("gui.codpattern.zombies.deploy.field.entryId") && json.has("gui.codpattern.zombies.deploy.barrier_rules.entry_summary") && json.has("hud.codpattern.zombies.barrier.entry_item"),"localization: "+lang);
        }
        System.out.println("PASS V2_EDITOR: bindings, legacy 0, forbidden edits, copy, actual undo/redo, serialization, pair summaries and frozen-state wiring");
    }
}
