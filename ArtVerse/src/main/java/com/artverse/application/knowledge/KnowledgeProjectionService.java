package com.artverse.application.knowledge;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.artverse.application.knowledge.KnowledgeSchema.*;
import static com.artverse.application.knowledge.KnowledgeFactService.structured;

@Service
@RequiredArgsConstructor
public class KnowledgeProjectionService {
    private final KnowledgeFactService facts;
    private final KnowledgeSchema schema;
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    private final ArtVerseProperties properties;
    private final Map<Long,Long> imageScans=new ConcurrentHashMap<>();
    public record ImageMetadata(String objectKey,boolean cover,int order,String caption,String appearanceVersion,
                                Long revealChapterId,Long fromChapterId,Long toChapterId) {}

    @Transactional
    public List<Map<String,Object>> characters(long storyId,Long asOf) {
        syncImages(storyId);
        List<Map<String,Object>> roots=facts.project(storyId,asOf,null);
        List<KnowledgeFactService.Fact> all=facts.all(storyId,asOf,null);
        List<Map<String,Object>> images=jdbc.queryForList("SELECT * FROM character_reference_images WHERE story_id=? ORDER BY is_cover DESC,sort_order,id",storyId);
        List<Map<String,Object>> events=events(storyId,asOf,null,"NARRATIVE");
        Integer number=asOf==null?null:facts.chapterNumber(storyId,asOf);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var root:roots) {
            if(!root.get("type").equals("CHARACTER_CARD")) continue;
            Map<String,Object> data=map(root.get("structuredData"));Long cid=id(data.get("character_id"));if(cid==null) continue;
            Map<String,Object> card=new LinkedHashMap<>(root);card.put("characterId",cid);
            var linked=all.stream().filter(f->cid.equals(id(f.payload().get("character_id")))).toList();
            card.put("levels",linked.stream().filter(f->f.kind().equals("LEVEL_STATE")&&!f.certainty().equals("PLAN")&&(asOf!=null||f.toChapterId()==null)).toList());
            card.put("identities",linked.stream().filter(f->f.kind().equals("IDENTITY_STATE")&&!f.certainty().equals("PLAN")&&(asOf!=null||f.toChapterId()==null)).toList());
            card.put("holdings",holdings(storyId,cid,all));
            card.put("assetChanges",all.stream().filter(f->f.kind().equals("ASSET_CHANGE")&&(cid.equals(id(f.payload().get("character_id")))||cid.equals(id(f.payload().get("target_character_id"))))).toList());
            card.put("experiences",events.stream().filter(e->list(map(e.get("structuredData")).get("participant_character_ids")).stream().anyMatch(v->cid.equals(id(v)))).toList());
            card.put("relations",roots.stream().filter(r->r.get("type").equals("CHARACTER_RELATION")).filter(r->{var d=map(r.get("structuredData"));return cid.equals(id(d.get("source_character_id")))||cid.equals(id(d.get("target_character_id")));}).toList());
            var visibleImages=images.stream().filter(i->cid.equals(id(i.get("character_profile_id")))&&imageVisible(storyId,i,number)).toList();
            card.put("images",visibleImages);card.put("cover",visibleImages.stream().findFirst().orElse(null));
            result.add(card);
        }
        return result;
    }
    private boolean imageVisible(long storyId,Map<String,Object> image,Integer number) {
        if(number==null) return true;
        Long reveal=id(image.get("reveal_chapter_id")),start=id(image.get("from_chapter_id")),end=id(image.get("to_chapter_id"));
        return reveal!=null&&facts.chapterNumber(storyId,reveal)<=number&&(start==null||facts.chapterNumber(storyId,start)<=number)&&(end==null||facts.chapterNumber(storyId,end)>number);
    }
    public List<Map<String,Object>> holdings(long storyId,long character,List<KnowledgeFactService.Fact> all) {
        Map<String,Map<String,Object>> accounts=new LinkedHashMap<>();
        List<KnowledgeFactService.Fact> ledger=all.stream().filter(f->Set.of("HOLDING_STATE","ASSET_CHANGE").contains(f.kind())&&f.certainty().equals("FACT"))
            .sorted(Comparator.comparingInt((KnowledgeFactService.Fact f)->f.fromChapterId()!=null?facts.chapterNumber(storyId,f.fromChapterId()):f.revealChapterId()==null?Integer.MAX_VALUE:facts.chapterNumber(storyId,f.revealChapterId())).thenComparingLong(KnowledgeFactService.Fact::id)).toList();
        for(var fact:ledger) {
            Map<String,Object> p=fact.payload();Long owner=id(p.get("character_id")),target=id(p.get("target_character_id"));
            boolean outbound=Objects.equals(owner,character),inbound=Objects.equals(target,character)&&"TRANSFER".equals(p.get("action"));
            if(!outbound&&!inbound) continue;
            Long asset=id(p.get("asset_entity_id"));String custody=Objects.toString(p.get("custody"),"OWNED");String instance=text(p,"instance_id");
            String key=asset+":"+custody+":"+instance;
            Map<String,Object> account=accounts.computeIfAbsent(key,k->{Map<String,Object> a=new LinkedHashMap<>();a.put("assetEntityId",asset);a.put("custody",custody);a.put("instanceId",instance);a.put("balance",null);a.put("knownDelta","0");a.put("quantityUnknown",false);a.put("equippedSlot",null);return a;});
            String action=Objects.toString(p.get("action"),"OBSERVED_BALANCE");
            BigDecimal qty=p.get("quantity")==null?null:new BigDecimal(p.get("quantity").toString());
            if(fact.kind().equals("HOLDING_STATE")||action.equals("OBSERVED_BALANCE")) {account.put("balance",qty==null?null:qty.toPlainString());account.put("knownDelta","0");account.put("quantityUnknown",qty==null);}
            else if(action.equals("EQUIP")) account.put("equippedSlot",p.get("equipped_slot"));
            else if(action.equals("UNEQUIP")) account.put("equippedSlot",null);
            else if(qty==null) {account.put("balance",null);account.put("quantityUnknown",true);}
            else {
                boolean positive=inbound||action.equals("GAIN");BigDecimal delta=positive?qty:qty.negate();
                account.put("knownDelta",new BigDecimal(account.get("knownDelta").toString()).add(delta).toPlainString());
                if(account.get("balance")!=null) account.put("balance",new BigDecimal(account.get("balance").toString()).add(delta).toPlainString());
            }
            account.put("unit",Objects.toString(p.get("unit"),""));account.put("condition",Objects.toString(p.get("condition"),""));
        }
        Map<Long,String> names=new HashMap<>();
        all.stream().filter(f->f.kind().equals("WORLD_DEFINITION")).forEach(f->names.put(id(structured(f.payload()).get("entity_id")),text(f.payload(),"title")));
        accounts.values().forEach(a->a.put("name",names.getOrDefault(id(a.get("assetEntityId")),"待整理外物 #"+a.get("assetEntityId"))));
        return new ArrayList<>(accounts.values());
    }
    public List<Map<String,Object>> events(long storyId,Long asOf,Long character,String order) {
        var events=facts.project(storyId,asOf,null).stream().filter(r->r.get("type").equals("TIMELINE"))
            .filter(r->character==null||list(map(r.get("structuredData")).get("participant_character_ids")).stream().anyMatch(v->character.equals(id(v)))).toList();
        Comparator<Map<String,Object>> sort=Comparator.comparingInt(r->r.get("revealChapterId")==null?Integer.MAX_VALUE:facts.chapterNumber(storyId,id(r.get("revealChapterId"))));
        if("STORY".equals(order)) sort=Comparator.comparing(r->{Object v=map(r.get("structuredData")).get("story_order");return v instanceof Number?new BigDecimal(v.toString()):null;},Comparator.nullsLast(Comparator.naturalOrder()));
        return events.stream().sorted(sort.thenComparingLong(r->id(r.get("factId")))).toList();
    }
    public List<Map<String,Object>> foreshadowing(long storyId,Long asOf) {
        List<KnowledgeFactService.Fact> all=facts.all(storyId,asOf,null);
        int horizon=asOf==null?jdbc.queryForObject("SELECT coalesce(max(chapter_number),0) FROM chapters WHERE story_id=?",Integer.class,storyId):facts.chapterNumber(storyId,asOf);
        return facts.project(storyId,asOf,null).stream().filter(r->r.get("type").equals("FORESHADOWING")).map(r->{
            Map<String,Object> p=new LinkedHashMap<>(r),data=schema.normalize(com.artverse.domain.KnowledgeUnitType.FORESHADOWING,map(r.get("structuredData")));
            p.put("structuredData",data);p.put("clues",all.stream().filter(f->f.unitId()==id(r.get("id"))&&f.kind().equals("FORESHADOWING_CLUE")).toList());
            boolean outstanding=!Set.of("RESOLVED","ABANDONED","DRAFT_PLAN").contains(text(data,"status"));p.put("outstanding",outstanding);
            List<String> reminders=new ArrayList<>();Long expected=id(data.get("expected_resolution_chapter_id"));
            if(outstanding && expected!=null && facts.chapterNumber(storyId,expected)<=horizon) reminders.add("已到作者设定的回收窗口");
            Object gap=data.get("reminder_after_chapters");int last=all.stream().filter(f->f.unitId()==id(r.get("id"))&&f.revealChapterId()!=null).mapToInt(f->facts.chapterNumber(storyId,f.revealChapterId())).max().orElse(horizon);
            if(outstanding && gap instanceof Number n && n.intValue()>0 && horizon-last>=n.intValue()) reminders.add("已连续 "+(horizon-last)+" 章未推进");
            p.put("reminders",reminders);return p;
        }).toList();
    }
    public synchronized void syncImages(long storyId) {
        long now=System.currentTimeMillis();if(now-imageScans.getOrDefault(storyId,0L)<60_000) return;
        String prefix="stories/"+storyId+"/characters/";
        try {
            Set<String> keys=new HashSet<>();
            for(var object:storage.list(properties.getMinio().getBucket(),prefix,20_000)) {
                String key=object.objectKey();if(!key.matches("stories/"+storyId+"/characters/\\d+/ref_images/[^/]+\\.(png|jpe?g|webp)")) continue;
                long cid=Long.parseLong(key.substring(prefix.length()).split("/")[0]);
                if(jdbc.queryForObject("SELECT count(*) FROM character_profiles WHERE id=? AND story_id=?",Integer.class,cid,storyId)!=1) continue;
                keys.add(key);jdbc.update("INSERT INTO character_reference_images(story_id,character_profile_id,object_key) VALUES (?,?,?) ON CONFLICT(object_key) DO NOTHING",storyId,cid,key);
            }
            // Deletion is handled by the image command; incomplete object listings never delete metadata.
            imageScans.put(storyId,now);
        } catch(RuntimeException unavailable) { /* Authoring data stays browsable when object storage is unavailable. */ }
    }
    @Transactional
    public List<Map<String,Object>> saveImages(long storyId,long userId,long character,List<ImageMetadata> images) {
        facts.requireStory(storyId,userId);facts.characterEntity(storyId,character);
        if(images.size()>5||images.stream().filter(ImageMetadata::cover).count()>1) throw new BusinessException(400,"最多 5 张参考图且只能有一张默认封面");
        String prefix="stories/"+storyId+"/characters/"+character+"/ref_images/";
        Set<String> available=new HashSet<>();storage.list(properties.getMinio().getBucket(),prefix,100).forEach(o->available.add(o.objectKey()));
        jdbc.queryForList("SELECT id FROM character_profiles WHERE id=? FOR UPDATE",character);
        jdbc.update("UPDATE character_reference_images SET is_cover=false WHERE character_profile_id=?",character);
        for(var image:images) {
            if(!available.contains(image.objectKey())||!image.objectKey().startsWith(prefix)) throw new BusinessException(400,"图片对象不存在或不属于此人物");
            if(image.revealChapterId()!=null) facts.chapterNumber(storyId,image.revealChapterId());
            if(image.fromChapterId()!=null) facts.chapterNumber(storyId,image.fromChapterId());
            if(image.toChapterId()!=null) facts.chapterNumber(storyId,image.toChapterId());
            if(image.fromChapterId()!=null&&image.toChapterId()!=null&&facts.chapterNumber(storyId,image.fromChapterId())>=facts.chapterNumber(storyId,image.toChapterId())) throw new BusinessException(400,"图片阶段范围无效");
            jdbc.update("""
                INSERT INTO character_reference_images(story_id,character_profile_id,object_key,is_cover,sort_order,caption,appearance_version,reveal_chapter_id,from_chapter_id,to_chapter_id)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(object_key) DO UPDATE SET is_cover=EXCLUDED.is_cover,sort_order=EXCLUDED.sort_order,caption=EXCLUDED.caption,
                appearance_version=EXCLUDED.appearance_version,reveal_chapter_id=EXCLUDED.reveal_chapter_id,from_chapter_id=EXCLUDED.from_chapter_id,to_chapter_id=EXCLUDED.to_chapter_id
                """,storyId,character,image.objectKey(),image.cover(),image.order(),Objects.toString(image.caption(),""),Objects.toString(image.appearanceVersion(),""),image.revealChapterId(),image.fromChapterId(),image.toChapterId());
        }
        return jdbc.queryForList("SELECT * FROM character_reference_images WHERE character_profile_id=? ORDER BY is_cover DESC,sort_order,id",character);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    private static List<?> list(Object v){return v instanceof List<?> l?l:List.of();}
}
