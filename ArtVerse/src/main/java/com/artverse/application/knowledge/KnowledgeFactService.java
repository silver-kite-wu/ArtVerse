package com.artverse.application.knowledge;

import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import static com.artverse.application.knowledge.KnowledgeSchema.*;

/** Versioned atomic facts and the shared chapter/character visibility boundary. */
@Service
@RequiredArgsConstructor
public class KnowledgeFactService {
    private final JdbcTemplate jdbc;
    private final KnowledgeSchema schema;
    public record Fact(long id,long unitId,long revisionId,int revision,String kind,Map<String,Object> payload,
                       Long revealChapterId,Long fromChapterId,Long toChapterId,String certainty,String scope,
                       List<Map<String,Object>> sources) {}
    public record Command(Long factId,Integer baseRevision,String kind,Map<String,Object> payload,
                          Long revealChapterId,Long fromChapterId,Long toChapterId,String certainty,String businessKey,Long previousFactId) {
        public Command(Long factId,Integer baseRevision,String kind,Map<String,Object> payload,Long revealChapterId,Long fromChapterId,Long toChapterId,String certainty,String businessKey) {
            this(factId,baseRevision,kind,payload,revealChapterId,fromChapterId,toChapterId,certainty,businessKey,null);
        }
    }
    public record Browse(List<Map<String,Object>> items,long total,Map<String,Long> counts,int page,int pageSize) {}
    public record Graph(List<Map<String,Object>> nodes,List<Map<String,Object>> edges,boolean truncated,int totalEdges) {}

    public void requireStory(long storyId,long userId) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM stories WHERE id=? AND user_id=?",Integer.class,storyId,userId);
        if (count == null || count != 1) throw new BusinessException(404,"作品不存在");
    }
    /** Release historical references only as part of an explicitly authorized whole-story deletion. */
    @Transactional
    public void deleteForStory(long storyId,long userId) {
        requireStory(storyId,userId);
        jdbc.queryForList("SELECT id FROM stories WHERE id=? FOR UPDATE",storyId);
        for(String table:List.of("knowledge_fact_links","knowledge_fact_entity_links","knowledge_fact_sources","character_reference_images","knowledge_facts","knowledge_sources","knowledge_entities"))
            jdbc.update("DELETE FROM "+table+" WHERE story_id=?",storyId);
    }
    public void requireChapterUnreferenced(long storyId,long chapterId) {
        Integer count=jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM knowledge_fact_revisions WHERE story_id=? AND ? IN(reveal_chapter_id,effective_from_chapter_id,effective_to_chapter_id))
                 + (SELECT count(*) FROM knowledge_sources WHERE story_id=? AND chapter_id=?)
                 + (SELECT count(*) FROM character_reference_images WHERE story_id=? AND ? IN(reveal_chapter_id,from_chapter_id,to_chapter_id))
            """,Integer.class,storyId,chapterId,storyId,chapterId,storyId,chapterId);
        if(count!=null&&count>0) throw new BusinessException(409,"该章节被知识事实、历史来源或角色参考图引用，请先整理引用；整部作品可在作品管理中删除");
    }
    public int chapterNumber(long storyId,long chapterId) {
        List<Integer> rows=jdbc.query("SELECT chapter_number FROM chapters WHERE story_id=? AND id=?",(r,n)->r.getInt(1),storyId,chapterId);
        if (rows.isEmpty()) throw new BusinessException(400,"章节不属于当前作品");
        return rows.getFirst();
    }
    public Long chapterId(long storyId,Integer number) {
        if (number == null) return null;
        List<Long> rows=jdbc.query("SELECT id FROM chapters WHERE story_id=? AND chapter_number=?",(r,n)->r.getLong(1),storyId,number);
        if (rows.isEmpty()) throw new BusinessException(400,"第"+number+"章不存在");
        return rows.getFirst();
    }
    public long characterEntity(long storyId,long characterId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,name FROM character_profiles WHERE id=? AND story_id=?",characterId,storyId);
        if (rows.isEmpty()) throw new BusinessException(400,"人物不属于当前作品，请重新绑定人物");
        return jdbc.queryForObject("""
            INSERT INTO knowledge_entities(story_id,kind,name,character_profile_id) VALUES (?,'CHARACTER',?,?)
            ON CONFLICT(character_profile_id) DO UPDATE SET name=EXCLUDED.name RETURNING id
            """,Long.class,storyId,rows.getFirst().get("name"),characterId);
    }
    public void validateReferences(long storyId,Map<String,Object> data) {
        for (var entry:data.entrySet()) {
            String key=entry.getKey(); Object value=entry.getValue();
            if (value == null) continue;
            if (key.endsWith("chapter_id")) {
                Long chapter=id(value); if (chapter==null) throw new BusinessException(400,key+"：章节格式错误");
                chapterNumber(storyId,chapter);
            } else if (key.equals("character_id") || key.endsWith("_character_id")) {
                Long character=id(value); if (character==null) throw new BusinessException(400,key+"：人物格式错误");
                requireCharacter(storyId,character);
            } else if (key.endsWith("_character_ids") || key.equals("known_by")) {
                if (!(value instanceof List<?> values)) throw new BusinessException(400,key+"：请选择人物列表");
                for(Object v:values) { Long character=id(v); if(character==null) throw new BusinessException(400,key+"：人物格式错误"); requireCharacter(storyId,character); }
            } else if (key.endsWith("entity_id")) {
                Long entity=id(value);
                if(entity==null || jdbc.queryForObject("SELECT count(*) FROM knowledge_entities WHERE id=? AND story_id=? AND NOT archived",Integer.class,entity,storyId)!=1)
                    throw new BusinessException(400,key+"：实体不属于当前作品");
            } else if (key.endsWith("event_id")) requireFact(storyId,id(value),"EVENT");
            else if(key.endsWith("_event_ids")) {
                if(!(value instanceof List<?> values)) throw new BusinessException(400,"关联事件应为列表");
                for(Object v:values) requireFact(storyId,id(v),"EVENT");
            } else if(key.endsWith("_entity_ids")) {
                if(!(value instanceof List<?> values)) throw new BusinessException(400,"关联实体应为列表");
                for(Object v:values) validateReferences(storyId,Map.of("entity_id",v));
            } else if(key.equals("participant_roles")) {
                if(!(value instanceof Map<?,?> roles)) throw new BusinessException(400,"参与者作用格式无效");
                for(Object c:roles.keySet()) if(id(c)==null || list(data.get("participant_character_ids")).stream().noneMatch(v->Objects.equals(id(v),id(c)))) throw new BusinessException(400,"参与者作用必须对应事件的参与人物");
            }
        }
        Long first=id(data.get("first_appearance_chapter_id")), exit=id(data.get("exit_chapter_id"));
        if(first!=null && exit!=null && chapterNumber(storyId,exit)<chapterNumber(storyId,first))
            throw new BusinessException(400,"退场章节不能早于首次出场章节");
        Long window=id(data.get("expected_resolution_chapter_id")),end=id(data.get("expected_resolution_end_chapter_id"));
        if(window!=null&&end!=null&&chapterNumber(storyId,window)>chapterNumber(storyId,end)) throw new BusinessException(400,"预计回收窗口截止不能早于开始");
    }
    private void requireCharacter(long storyId,long characterId) {
        if(jdbc.queryForObject("SELECT count(*) FROM character_profiles WHERE id=? AND story_id=?",Integer.class,characterId,storyId)!=1)
            throw new BusinessException(400,"人物不属于当前作品，请重新绑定人物");
    }
    private void requireFact(long storyId,Long factId,String kind) {
        if(factId==null || jdbc.queryForObject("SELECT count(*) FROM knowledge_facts WHERE id=? AND story_id=? AND kind=? AND NOT archived",Integer.class,factId,storyId,kind)!=1)
            throw new BusinessException(400,"关联事件不存在或不属于当前作品");
    }
    public void saveRoot(KnowledgeUnit unit,long userId) {
        long storyId=unit.getStory().getId();
        Map<String,Object> structured=schema.read(unit.getStructuredData());
        if(unit.getCharacterProfile()!=null) structured.put("character_id",unit.getCharacterProfile().getId());
        validateReferences(storyId,structured);
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("title",unit.getTitle()); payload.put("body",unit.getBody()); payload.put("summary",unit.getSummary());
        payload.put("structured",structured); payload.put("importance",unit.getImportance());
        String kind=switch(unit.getType()) { case CHARACTER_CARD->"CHARACTER_CORE"; case CHARACTER_RELATION->"RELATION_STATE";
            case TIMELINE->"EVENT"; case FORESHADOWING->"FORESHADOWING"; case WORLDVIEW->"WORLD_DEFINITION"; };
        String key="root:"+unit.getId();
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT f.id,f.current_revision,f.business_key FROM knowledge_facts f
            JOIN knowledge_fact_revisions r ON r.fact_id=f.id AND r.revision=f.current_revision
            LEFT JOIN chapters c ON c.id=r.effective_from_chapter_id
            WHERE f.story_id=? AND f.unit_id=? AND f.kind=? AND NOT f.archived
            ORDER BY c.chapter_number DESC NULLS LAST,f.id DESC LIMIT 1 FOR UPDATE OF f
            """,storyId,unit.getId(),kind);
        if(!existing.isEmpty()) key=existing.getFirst().get("business_key").toString();
        Long reveal=id(structured.get("reveal_chapter_id"));
        // Existing v1 numbers are resolved to actual chapter IDs; unknown stays unlocated.
        if(reveal==null && unit.getEffectiveFromChapter()!=null) reveal=chapterId(storyId,unit.getEffectiveFromChapter());
        Long from=id(structured.get("effective_from_chapter_id")), to=id(structured.get("effective_to_chapter_id"));
        if(!existing.isEmpty()) {
            Fact current=fact(storyId,((Number)existing.getFirst().get("id")).longValue());
            if(!structured.containsKey("effective_from_chapter_id") && unit.getEffectiveFromChapter()==null) from=current.fromChapterId();
            if(!structured.containsKey("effective_to_chapter_id") && unit.getEffectiveToChapter()==null) to=current.toChapterId();
            if(!structured.containsKey("reveal_chapter_id") && unit.getEffectiveFromChapter()==null) reveal=current.revealChapterId();
        }
        if(from==null && unit.getEffectiveFromChapter()!=null) from=chapterId(storyId,unit.getEffectiveFromChapter());
        if(to==null && unit.getEffectiveToChapter()!=null) {
            // Legacy inclusive endpoint converted to the next real chapter, never compare IDs as order.
            List<Long> next=jdbc.query("SELECT id FROM chapters WHERE story_id=? AND chapter_number>? ORDER BY chapter_number LIMIT 1",(r,n)->r.getLong(1),storyId,unit.getEffectiveToChapter());
            if(!next.isEmpty()) to=next.getFirst();
        }
        String certainty=text(structured,"certainty"); if(certainty.isBlank()) certainty="DRAFT_PLAN".equals(structured.get("status"))?"PLAN":"FACT";
        Command command=new Command(existing.isEmpty()?null:((Number)existing.getFirst().get("id")).longValue(),
            existing.isEmpty()?null:((Number)existing.getFirst().get("current_revision")).intValue(),kind,payload,reveal,from,to,certainty,key);
        save(storyId,unit.getId(),userId,command);
    }
    @Transactional
    public Fact save(long storyId,long unitId,long userId,Command command) {
        requireStory(storyId,userId);
        List<String> roots=jdbc.query("SELECT type FROM knowledge_units WHERE id=? AND story_id=? AND status='ACTIVE' FOR UPDATE",(r,n)->r.getString(1),unitId,storyId);
        if(roots.isEmpty()) throw new BusinessException(404,"知识不存在或已归档");
        Set<String> allowed=switch(roots.getFirst()) {
            case "CHARACTER_CARD"->Set.of("CHARACTER_CORE","LEVEL_STATE","IDENTITY_STATE","HOLDING_STATE","ASSET_CHANGE");
            case "CHARACTER_RELATION"->Set.of("RELATION_STATE"); case "TIMELINE"->Set.of("EVENT");
            case "FORESHADOWING"->Set.of("FORESHADOWING","FORESHADOWING_CLUE"); default->Set.of("WORLD_DEFINITION"); };
        if(!allowed.contains(command.kind())) throw new BusinessException(400,"此类型不支持该事实");
        Map<String,Object> payload=new LinkedHashMap<>(command.payload()==null?Map.of():command.payload());
        Map<String,Object> data=structured(payload);
        boolean root=Set.of("CHARACTER_CORE","RELATION_STATE","EVENT","FORESHADOWING","WORLD_DEFINITION").contains(command.kind());
        if(root) {
            KnowledgeUnitType type=KnowledgeUnitType.valueOf(roots.getFirst());
            data=schema.normalize(type,data);
            schema.requireValid(type,text(payload,"title"),text(payload,"body"),data);
            payload.put("structured",data);
        } else data=payload;
        validateReferences(storyId,data);
        if(Set.of("CHARACTER_CORE","LEVEL_STATE","IDENTITY_STATE","HOLDING_STATE","ASSET_CHANGE").contains(command.kind())) {
            Long owner=jdbc.queryForObject("SELECT character_profile_id FROM knowledge_units WHERE id=?",Long.class,unitId);
            if(owner==null || !owner.equals(id(data.get("character_id")))) throw new BusinessException(400,"人物事实必须绑定当前角色卡的人物");
        }
        if(command.revealChapterId()!=null) chapterNumber(storyId,command.revealChapterId());
        if(command.fromChapterId()!=null) chapterNumber(storyId,command.fromChapterId());
        if(command.toChapterId()!=null) chapterNumber(storyId,command.toChapterId());
        if(command.fromChapterId()!=null && command.toChapterId()!=null && chapterNumber(storyId,command.toChapterId())<=chapterNumber(storyId,command.fromChapterId()))
            throw new BusinessException(400,"阶段区间应为 [起始章节,截止章节)，截止必须晚于起始");
        String certainty=command.certainty()==null?"FACT":command.certainty();
        if("DRAFT_PLAN".equals(data.get("status"))) certainty="PLAN";
        data.put("certainty",certainty);
        if(!Set.of("FACT","PLAN","RUMOR","BELIEF").contains(certainty)) throw new BusinessException(400,"事实属性无效");
        validateKind(storyId,command.kind(),data,certainty);
        Long payoff=id(data.get("resolved_event_id"));
        if(payoff!=null && command.revealChapterId()!=null) {
            Fact event=fact(storyId,payoff);
            if(event.revealChapterId()==null || event.certainty().equals("PLAN") || chapterNumber(storyId,event.revealChapterId())>chapterNumber(storyId,command.revealChapterId()))
                throw new BusinessException(400,"实际回收事件必须是本阶段已揭示的事实");
        }
        String key=command.businessKey();
        if(key==null || key.isBlank()) key="fact:"+UUID.randomUUID();
        // Typed canonical keys stop symmetric duplicate edges and duplicate ledger transactions.
        if(command.kind().equals("ASSET_CHANGE")) key="asset:"+text(data,"transaction_id");
        if(command.kind().equals("RELATION_STATE") && "SYMMETRIC".equals(data.get("direction")))
            key="symmetric:"+id(data.get("source_character_id"))+":"+id(data.get("target_character_id"))+":"+text(data,"relation_type")+":"+command.fromChapterId();
        List<Map<String,Object>> same=jdbc.queryForList("SELECT id,unit_id,current_revision,kind FROM knowledge_facts WHERE story_id=? AND business_key=? FOR UPDATE",storyId,key);
        long factId; int revision;
        if(command.factId()!=null) {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,unit_id,current_revision,kind,business_key FROM knowledge_facts WHERE id=? AND story_id=? FOR UPDATE",command.factId(),storyId);
            if(rows.isEmpty() || ((Number)rows.getFirst().get("unit_id")).longValue()!=unitId) throw new BusinessException(404,"事实不存在");
            var row=rows.getFirst(); factId=command.factId();
            if(command.businessKey()==null && !Set.of("ASSET_CHANGE","RELATION_STATE").contains(command.kind())) key=row.get("business_key").toString();
            if(command.businessKey()==null && command.kind().equals("RELATION_STATE") && !"SYMMETRIC".equals(data.get("direction"))) key=row.get("business_key").toString();
            if(!row.get("kind").equals(command.kind())) throw new BusinessException(400,"事实类型不能更改");
            int current=((Number)row.get("current_revision")).intValue();
            if(command.baseRevision()==null || command.baseRevision()!=current) throw new BusinessException(409,"事实已更新，请重新比较后保存");
            if(!same.isEmpty() && ((Number)same.getFirst().get("id")).longValue()!=factId) throw new BusinessException(409,"已存在相同关系或资产交易");
            revision=current+1;
            jdbc.update("UPDATE knowledge_facts SET current_revision=?,business_key=? WHERE id=?",revision,key,factId);
        } else {
            if(!same.isEmpty()) {
                Fact old=fact(storyId,((Number)same.getFirst().get("id")).longValue());
                if(old.unitId()==unitId && schema.hash(old.payload()).equals(schema.hash(payload)) && Objects.equals(old.revealChapterId(),command.revealChapterId()) && Objects.equals(old.fromChapterId(),command.fromChapterId()) && Objects.equals(old.toChapterId(),command.toChapterId()) && old.certainty().equals(certainty)) return old;
                throw new BusinessException(409,"业务事件已存在，请选择原记录修订");
            }
            factId=jdbc.queryForObject("INSERT INTO knowledge_facts(story_id,unit_id,kind,business_key) VALUES (?,?,?,?) RETURNING id",Long.class,storyId,unitId,command.kind(),key);
            revision=1;
        }
        if(command.previousFactId()!=null) {
            if(command.factId()!=null || command.fromChapterId()==null) throw new BusinessException(400,"新增阶段应指定起始章节及上一阶段");
            Fact previous=fact(storyId,command.previousFactId());
            if(previous.unitId()!=unitId || !previous.kind().equals(command.kind()) || previous.id()==factId) throw new BusinessException(400,"上一阶段不属于当前知识");
            if(previous.fromChapterId()!=null && chapterNumber(storyId,previous.fromChapterId())>=chapterNumber(storyId,command.fromChapterId())) throw new BusinessException(400,"新阶段应晚于上一阶段");
            save(storyId,unitId,userId,new Command(previous.id(),previous.revision(),previous.kind(),previous.payload(),previous.revealChapterId(),previous.fromChapterId(),command.fromChapterId(),previous.certainty(),null));
        }
        checkStageOverlap(storyId,factId,command,data);
        if(command.kind().equals("EVENT") && !"TIME_LOOP".equals(data.get("causal_mode"))) checkEventCycles(storyId,factId,data);
        long revisionId=jdbc.queryForObject("""
            INSERT INTO knowledge_fact_revisions(fact_id,story_id,revision,payload,reveal_chapter_id,effective_from_chapter_id,effective_to_chapter_id,certainty,scope,approved_by)
            VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,?,?) RETURNING id
            """,Long.class,factId,storyId,revision,schema.write(payload),command.revealChapterId(),command.fromChapterId(),command.toChapterId(),certainty,command.revealChapterId()==null?"UNLOCATED":"SCOPED",userId);
        linkEntities(storyId,revisionId,data);
        for(var e:data.entrySet()) if(e.getKey().endsWith("event_id") && e.getValue()!=null)
            jdbc.update("INSERT INTO knowledge_fact_links(revision_id,story_id,target_fact_id,role) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",revisionId,storyId,id(e.getValue()),e.getKey());
        for(var e:data.entrySet()) if(e.getKey().endsWith("_event_ids") && e.getValue() instanceof List<?> values) for(Object v:values)
            jdbc.update("INSERT INTO knowledge_fact_links(revision_id,story_id,target_fact_id,role) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",revisionId,storyId,id(v),e.getKey());
        Object evidence=data.get("evidence");
        Map<String,Object> snapshot=new LinkedHashMap<>(); snapshot.put("author_id",userId); snapshot.put("evidence",evidence);
        long sourceId=jdbc.queryForObject("INSERT INTO knowledge_sources(story_id,source_type,source_hash,snapshot) VALUES (?,'AUTHOR',?,CAST(? AS jsonb)) RETURNING id",Long.class,storyId,schema.hash(snapshot),schema.write(snapshot));
        jdbc.update("INSERT INTO knowledge_fact_sources(revision_id,story_id,source_id,excerpt) VALUES (?,?,?,?)",revisionId,storyId,sourceId,Objects.toString(evidence,""));
        return fact(storyId,factId);
    }
    private void validateKind(long storyId,String kind,Map<String,Object> data,String certainty) {
        switch(kind) {
            case "LEVEL_STATE","IDENTITY_STATE" -> {
                require(data,"character_id","dimension","label","evidence");
                if(kind.equals("LEVEL_STATE")) require(data,"system");
            }
            case "HOLDING_STATE","ASSET_CHANGE" -> {
                require(data,"character_id","asset_entity_id","evidence");
                Object qty=data.get("quantity");
                if(qty!=null) {
                    try { BigDecimal decimal=new BigDecimal(qty.toString()); if(decimal.signum()<0 || decimal.scale()>18 || decimal.precision()>38) throw new NumberFormatException(); data.put("quantity",decimal.toPlainString()); }
                    catch(Exception e) { throw new BusinessException(400,"数量应为非负精确十进制数或留空（未知）"); }
                }
                if(kind.equals("ASSET_CHANGE")) {
                    require(data,"transaction_id","action");
                    if(!Set.of("GAIN","LOSS","TRANSFER","CONSUME","EQUIP","UNEQUIP","OBSERVED_BALANCE").contains(text(data,"action"))) throw new BusinessException(400,"外物变动类型无效");
                    if("TRANSFER".equals(data.get("action"))) {
                        require(data,"target_character_id","source_event_id");
                        if(id(data.get("character_id")).equals(id(data.get("target_character_id")))) throw new BusinessException(400,"转赠双方不能相同");
                    }
                }
            }
            case "FORESHADOWING_CLUE" -> require(data,"description","evidence");
            case "RELATION_STATE" -> schema.requireValid(KnowledgeUnitType.CHARACTER_RELATION,"关系","关系",data);
            case "FORESHADOWING" -> schema.requireValid(KnowledgeUnitType.FORESHADOWING,"伏笔","伏笔",data);
            default -> { }
        }
        if("PLAN".equals(certainty) && kind.equals("ASSET_CHANGE")) throw new BusinessException(400,"计划不能记为实际资产变动");
        if(data.containsKey("asset_entity_id")) {
            String entityKind=jdbc.queryForObject("SELECT kind FROM knowledge_entities WHERE id=? AND story_id=?",String.class,id(data.get("asset_entity_id")),storyId);
            if(!Set.of("ITEM","CURRENCY","RESOURCE").contains(entityKind)) throw new BusinessException(400,"资产必须绑定物品或货币定义");
        }
    }
    private void require(Map<String,Object> data,String... keys) {
        for(String key:keys) if(data.get(key)==null || data.get(key).toString().isBlank()) throw new BusinessException(400,key+"：请补充必要字段");
    }
    private void checkEventCycles(long storyId,long factId,Map<String,Object> data) {
        Map<Long,Set<Long>> links=new HashMap<>();
        for(var row:jdbc.queryForList("""
            SELECT f.id,l.target_fact_id FROM knowledge_facts f JOIN knowledge_fact_revisions r ON r.fact_id=f.id AND r.revision=f.current_revision
            JOIN knowledge_fact_links l ON l.revision_id=r.id
            WHERE f.story_id=? AND f.id<>? AND l.role IN('cause_event_ids','follows_event_ids','depends_on_event_ids')
              AND coalesce(r.payload->'structured'->>'causal_mode','STRICT')<>'TIME_LOOP'
            """,storyId,factId)) links.computeIfAbsent(id(row.get("id")),k->new HashSet<>()).add(id(row.get("target_fact_id")));
        Set<Long> prerequisites=new HashSet<>();for(String key:List.of("cause_event_ids","follows_event_ids","depends_on_event_ids")) for(Object v:list(data.get(key))) prerequisites.add(id(v));
        links.put(factId,prerequisites);Set<Long> seen=new HashSet<>();Deque<Long> queue=new ArrayDeque<>(prerequisites);
        while(!queue.isEmpty()) {Long current=queue.removeFirst();if(current==factId) throw new BusinessException(409,"事件先后依赖形成循环，请修正前置事件；时间循环剧情需明确选择时间循环模式");if(seen.add(current))queue.addAll(links.getOrDefault(current,Set.of()));}
    }
    private void checkStageOverlap(long storyId,long factId,Command command,Map<String,Object> data) {
        if(!Set.of("LEVEL_STATE","IDENTITY_STATE","HOLDING_STATE","FORESHADOWING","RELATION_STATE").contains(command.kind())) return;
        Long unitId=jdbc.queryForObject("SELECT unit_id FROM knowledge_facts WHERE id=?",Long.class,factId);
        for(Fact f:all(storyId,null,null)) {
            if(f.id()==factId || !f.kind().equals(command.kind())) continue;
            if(Set.of("FORESHADOWING","RELATION_STATE").contains(command.kind()) && f.unitId()!=unitId) continue;
            Map<String,Object> p=f.payload();
            if(!Objects.equals(id(p.get("character_id")),id(data.get("character_id")))) continue;
            String dimension=command.kind().equals("HOLDING_STATE")?"asset_entity_id":"dimension";
            if(!Objects.equals(Objects.toString(p.get(dimension),""),Objects.toString(data.get(dimension),"")) || !Objects.equals(p.get("system"),data.get("system"))) continue;
            if(command.kind().equals("HOLDING_STATE") && (!Objects.equals(Objects.toString(p.get("custody"),"OWNED"),Objects.toString(data.get("custody"),"OWNED")) || !text(p,"instance_id").equals(text(data,"instance_id")))) continue;
            int a=command.fromChapterId()==null?Integer.MIN_VALUE:chapterNumber(storyId,command.fromChapterId());
            int b=command.toChapterId()==null?Integer.MAX_VALUE:chapterNumber(storyId,command.toChapterId());
            int c=f.fromChapterId()==null?Integer.MIN_VALUE:chapterNumber(storyId,f.fromChapterId());
            int d=f.toChapterId()==null?Integer.MAX_VALUE:chapterNumber(storyId,f.toChapterId());
            if(a<d && c<b) throw new BusinessException(409,"同一人物的此项状态存在重叠阶段，请先结束旧阶段");
        }
    }
    private void linkEntities(long storyId,long revisionId,Map<String,Object> data) {
        for(var e:data.entrySet()) {
            if(e.getValue()==null) continue;
            String key=e.getKey();
            if(key.equals("character_id") || key.endsWith("_character_id")) link(storyId,revisionId,characterEntity(storyId,id(e.getValue())),key);
            else if(key.endsWith("_character_ids") || key.equals("known_by")) {
                for(Object c:(List<?>)e.getValue()) link(storyId,revisionId,characterEntity(storyId,id(c)),key);
            } else if(key.endsWith("entity_id")) link(storyId,revisionId,id(e.getValue()),key);
            else if(key.endsWith("_entity_ids")) for(Object entity:(List<?>)e.getValue()) link(storyId,revisionId,id(entity),key);
        }
    }
    private void link(long storyId,long revisionId,long entity,String role) {
        jdbc.update("INSERT INTO knowledge_fact_entity_links(revision_id,story_id,entity_id,role) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",revisionId,storyId,entity,role);
    }
    public void sourceForCandidate(long storyId,long unitId,KnowledgeCandidate candidate) {
        long source=jdbc.queryForObject("INSERT INTO knowledge_sources(story_id,source_type,chapter_id,source_version,source_hash,snapshot) VALUES (?,?,?,?,?,CAST(? AS jsonb)) RETURNING id",Long.class,
            storyId,candidate.getSourceType(),candidate.getChapter()==null?null:candidate.getChapter().getId(),"sha256:"+candidate.getSourceHash(),
            candidate.getSourceHash()==null?schema.hash(candidate.getBody()):candidate.getSourceHash(),candidate.getSourceSnapshot());
        jdbc.update("""
            INSERT INTO knowledge_fact_sources(revision_id,story_id,source_id,excerpt)
            SELECT r.id,r.story_id,?,? FROM knowledge_facts f JOIN knowledge_fact_revisions r ON r.fact_id=f.id AND r.revision=f.current_revision
            WHERE f.unit_id=? AND f.story_id=? AND f.id=?
            """,source,candidate.getBody().substring(0,Math.min(candidate.getBody().length(),1200)),unitId,storyId,
                project(storyId,null,null).stream().filter(r->id(r.get("id"))==unitId).findFirst().orElseThrow().get("factId"));
    }
    public Fact fact(long storyId,long factId) {
        return queryFacts(storyId,null,null,factId).stream().findFirst().orElseThrow(()->new BusinessException(404,"事实不存在"));
    }
    public List<Fact> all(long storyId,Long asOfChapterId,Long knowingCharacterId) {
        if(asOfChapterId!=null) chapterNumber(storyId,asOfChapterId);
        return queryFacts(storyId,asOfChapterId,knowingCharacterId,null);
    }
    public List<Fact> published(long storyId,long asOfChapterId,Long knowingCharacterId) {
        List<Fact> scoped=all(storyId,asOfChapterId,knowingCharacterId);
        Set<Long> published=new HashSet<>(jdbc.query("SELECT id FROM chapters WHERE story_id=? AND novel_is_published",(r,n)->r.getLong(1),storyId));
        Set<Long> blocked=new HashSet<>(jdbc.query("""
            SELECT fs.revision_id FROM knowledge_fact_sources fs JOIN knowledge_sources s ON s.id=fs.source_id
            JOIN chapters c ON c.id=s.chapter_id WHERE fs.story_id=? AND NOT c.novel_is_published
            """,(r,n)->r.getLong(1),storyId));
        var visible=scoped.stream().filter(f->published.contains(f.revealChapterId())&&!blocked.contains(f.revisionId())).toList();
        Set<Long> events=new HashSet<>();visible.stream().filter(f->f.kind().equals("EVENT")).forEach(f->events.add(f.id()));
        int number=chapterNumber(storyId,asOfChapterId);
        return visible.stream().map(f->sanitize(storyId,f,number,events)).toList();
    }
    private List<Fact> queryFacts(long storyId,Long asOf,Long knowing,Long factId) {
        Integer number=asOf==null?null:chapterNumber(storyId,asOf);
        List<Object> args=new ArrayList<>(List.of(storyId));
        String where="";
        if(factId!=null) { where+=" AND f.id=?"; args.add(factId); }
        if(number!=null) {
            where+=" AND r.scope='SCOPED' AND r.certainty<>'PLAN' AND reveal.chapter_number<=? AND (start.chapter_number IS NULL OR start.chapter_number<=?) AND (finish.chapter_number IS NULL OR finish.chapter_number>?)";
            args.add(number);args.add(number);args.add(number);
        }
        List<Fact> result=jdbc.query("""
            SELECT f.id,f.unit_id,f.kind,r.id AS revision_id,r.revision,r.payload,r.reveal_chapter_id,
            r.effective_from_chapter_id,r.effective_to_chapter_id,r.certainty,r.scope FROM knowledge_facts f
            JOIN knowledge_units u ON u.id=f.unit_id AND u.status='ACTIVE'
            JOIN knowledge_fact_revisions r ON r.fact_id=f.id AND r.revision=f.current_revision
            LEFT JOIN chapters reveal ON reveal.id=r.reveal_chapter_id
            LEFT JOIN chapters start ON start.id=r.effective_from_chapter_id
            LEFT JOIN chapters finish ON finish.id=r.effective_to_chapter_id
            WHERE f.story_id=? AND NOT f.archived
            """+where+" ORDER BY f.id",(r,n)->new Fact(r.getLong(1),r.getLong("unit_id"),r.getLong("revision_id"),r.getInt("revision"),r.getString("kind"),schema.read(r.getString("payload")),
                r.getObject("reveal_chapter_id",Long.class),r.getObject("effective_from_chapter_id",Long.class),r.getObject("effective_to_chapter_id",Long.class),r.getString("certainty"),r.getString("scope"),List.of()),args.toArray());
        List<Fact> permitted=result.stream().filter(f->visibleTo(f,knowing)).toList();
        Set<Long> visibleEvents=new HashSet<>();permitted.stream().filter(f->f.kind().equals("EVENT")).forEach(f->visibleEvents.add(f.id()));
        return permitted.stream().map(f->sanitize(storyId,f,number,visibleEvents)).toList();
    }
    private boolean visibleTo(Fact f,Long knowing) {
        if(knowing==null) return true;
        Map<String,Object> data=structured(f.payload()); if(data.isEmpty()) data=f.payload();
        Object by=data.get("known_by");
        if(by instanceof List<?> list && !list.isEmpty()) return list.stream().anyMatch(v->Objects.equals(id(v),knowing));
        return !"PRIVATE".equals(data.get("visibility")) && text(data,"hidden_information").isBlank();
    }
    private Fact sanitize(long storyId,Fact f,Integer number,Set<Long> visibleEvents) {
        if(number==null) return f;
        Map<String,Object> payload=new LinkedHashMap<>(f.payload());boolean root=payload.get("structured") instanceof Map;
        Map<String,Object> data=new LinkedHashMap<>(root?structured(payload):payload);
        for(String key:List.of("intended_payoff","expected_resolution_chapter_id","expected_resolution_end_chapter_id","reminder_after_chapters","resolution_condition","author_notes")) data.remove(key);
        data.entrySet().removeIf(e->e.getKey().endsWith("chapter_id") && id(e.getValue())!=null && chapterNumber(storyId,id(e.getValue()))>number);
        data.entrySet().removeIf(e->e.getKey().endsWith("event_id") && id(e.getValue())!=null && !visibleEvents.contains(id(e.getValue())));
        for(var e:data.entrySet()) if(e.getKey().endsWith("_event_ids") && e.getValue() instanceof List<?> ids) e.setValue(ids.stream().filter(v->visibleEvents.contains(id(v))).toList());
        if(root) payload.put("structured",data);else payload=data;
        Long visibleEnd=f.toChapterId()!=null&&chapterNumber(storyId,f.toChapterId())>number?null:f.toChapterId();
        return new Fact(f.id(),f.unitId(),f.revisionId(),f.revision(),f.kind(),payload,f.revealChapterId(),f.fromChapterId(),visibleEnd,f.certainty(),f.scope(),List.of());
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> structured(Map<String,Object> payload) {
        Object value=payload.get("structured"); return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();
    }
    public List<Map<String,Object>> project(long storyId,Long asOf,Long knowing) {
        return projectFacts(storyId,all(storyId,asOf,knowing));
    }
    public List<Map<String,Object>> projectPublished(long storyId,long asOf,Long knowing) {
        return projectFacts(storyId,published(storyId,asOf,knowing));
    }
    private List<Map<String,Object>> projectFacts(long storyId,List<Fact> facts) {
        Map<Long,List<Fact>> grouped=new LinkedHashMap<>();
        facts.forEach(f->grouped.computeIfAbsent(f.unitId(),k->new ArrayList<>()).add(f));
        List<Map<String,Object>> units=jdbc.queryForList("SELECT id,type,status,version,updated_at,character_profile_id FROM knowledge_units WHERE story_id=? AND status='ACTIVE' ORDER BY updated_at DESC,id DESC",storyId);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var unit:units) {
            long unitId=((Number)unit.get("id")).longValue();
            List<Fact> scoped=grouped.getOrDefault(unitId,List.of());
            Optional<Fact> core=scoped.stream().filter(f->Set.of("CHARACTER_CORE","RELATION_STATE","EVENT","WORLD_DEFINITION","FORESHADOWING").contains(f.kind()))
                .max(Comparator.comparingInt((Fact f)->f.fromChapterId()==null?Integer.MIN_VALUE:chapterNumber(storyId,f.fromChapterId())).thenComparingLong(Fact::id));
            if(core.isEmpty()) continue;
            Fact f=core.get(); Map<String,Object> item=new LinkedHashMap<>(unit);item.putAll(f.payload());
            Map<String,Object> data=schema.normalize(KnowledgeUnitType.valueOf(unit.get("type").toString()),structured(f.payload()));
            if(unit.get("type").equals("CHARACTER_CARD") && id(data.get("character_id"))==null && unit.get("character_profile_id")!=null) data.put("character_id",unit.get("character_profile_id"));
            data.put("reveal_chapter_id",f.revealChapterId());data.put("effective_from_chapter_id",f.fromChapterId());data.put("effective_to_chapter_id",f.toChapterId());
            item.put("structuredData",data);item.remove("structured");
            item.put("factId",f.id());item.put("scope",f.scope());item.put("certainty",f.certainty());item.put("facts",scoped);
            item.put("revealChapterId",f.revealChapterId());item.put("fromChapterId",f.fromChapterId()); item.put("toChapterId",f.toChapterId());
            result.add(item);
        }
        return result;
    }
    public Browse browse(long storyId,long userId,String type,String state,String q,Long asOf,int page,int size) {
        return browse(storyId,userId,type,state,q,asOf,page,size,"","NARRATIVE",null,"");
    }
    public Browse browse(long storyId,long userId,String type,String state,String q,Long asOf,int page,int size,String subtype,String order,Long character,String storyline) {
        return browse(storyId,userId,type,state,q,asOf,page,size,subtype,order,character,storyline,null,null,null,null);
    }
    public Browse browse(long storyId,long userId,String type,String state,String q,Long asOf,int page,int size,String subtype,String order,Long character,String storyline,Long relatedCharacter,Integer minImportance,Long resolutionFrom,Long resolutionTo) {
        requireStory(storyId,userId); int limit=Math.max(1,Math.min(100,size)); int offset=Math.max(0,page)*limit;
        Integer windowFrom=resolutionFrom==null?null:chapterNumber(storyId,resolutionFrom),windowTo=resolutionTo==null?null:chapterNumber(storyId,resolutionTo);
        if(windowFrom!=null && windowTo!=null && windowFrom>windowTo) throw new BusinessException(400,"回收窗口开始章节不能晚于截止章节");
        List<Map<String,Object>> records;
        if(Set.of("PENDING","REJECTED","SUPERSEDED").contains(state)) {
            List<Map<String,Object>> raw=jdbc.queryForList("SELECT * FROM knowledge_candidates WHERE story_id=? AND user_id=? AND status=? ORDER BY created_at DESC,id DESC",storyId,userId,state);
            records=new ArrayList<>();
            for(var r:raw) {
                Map<String,Object> item=new LinkedHashMap<>(r); Map<String,Object> data=schema.normalize(KnowledgeUnitType.valueOf(r.get("knowledge_type").toString()),schema.read(r.get("structured_data").toString()));
                if(asOf!=null) { Long reveal=id(data.get("reveal_chapter_id")); if(reveal==null || chapterNumber(storyId,reveal)>chapterNumber(storyId,asOf)) continue; }
                item.put("type",r.get("knowledge_type")); item.put("structuredData",data);
                item.put("issues",schema.issues(KnowledgeUnitType.valueOf(r.get("knowledge_type").toString()),Objects.toString(r.get("title"),""),Objects.toString(r.get("body"),""),data));
                records.add(item);
            }
        } else if("ARCHIVED".equals(state)) {
            if(asOf!=null) records=List.of();
            else records=jdbc.queryForList("SELECT id,type,title,body,summary,structured_data AS \"structuredData\",version,status FROM knowledge_units WHERE story_id=? AND status='ARCHIVED' ORDER BY updated_at DESC",storyId)
                .stream().map(r->{r.put("structuredData",schema.read(r.get("structuredData").toString()));return r;}).toList();
        } else records=project(storyId,asOf,null);
        String query=q==null?"":q.trim().toLowerCase(Locale.ROOT);
        List<Map<String,Object>> searched=records.stream().filter(r->query.isBlank() || searchable(r).toLowerCase(Locale.ROOT).contains(query))
            .filter(r->subtype==null||subtype.isBlank()||subtype.equals(map(r.get("structuredData")).get("subtype")))
            .filter(r->character==null||list(map(r.get("structuredData")).get("participant_character_ids")).stream().anyMatch(v->character.equals(id(v))))
            .filter(r->storyline==null||storyline.isBlank()||storyline.equals(map(r.get("structuredData")).get("storyline")))
            .filter(r->relatedCharacter==null||list(map(r.get("structuredData")).get("related_character_ids")).stream().anyMatch(v->relatedCharacter.equals(id(v))))
            .filter(r->minImportance==null||(r.get("importance") instanceof Number n && n.intValue()>=minImportance))
            .filter(r->{if(windowFrom==null && windowTo==null)return true;var d=map(r.get("structuredData"));Long start=id(d.get("expected_resolution_chapter_id")),end=id(d.get("expected_resolution_end_chapter_id"));if(start==null && end==null)return false;int first=chapterNumber(storyId,start==null?end:start),last=chapterNumber(storyId,end==null?start:end);return (windowFrom==null||last>=windowFrom)&&(windowTo==null||first<=windowTo);}).toList();
        Map<String,Long> counts=new LinkedHashMap<>(); for(KnowledgeUnitType t:KnowledgeUnitType.values()) counts.put(t.name(),searched.stream().filter(r->t.name().equals(r.get("type"))).count());
        List<Map<String,Object>> filtered=searched.stream().filter(r->type==null || type.isBlank() || type.equals("ALL") || type.equals(r.get("type"))).toList();
        if("TIMELINE".equals(type)) {
            Comparator<Map<String,Object>> sort="STORY".equals(order)
                ? Comparator.comparing(r->{Object v=map(r.get("structuredData")).get("story_order");return v instanceof Number?new BigDecimal(v.toString()):null;},Comparator.nullsLast(Comparator.naturalOrder()))
                : Comparator.comparingInt(r->{Long c=id(r.get("revealChapterId"));if(c==null)c=id(map(r.get("structuredData")).get("reveal_chapter_id"));return c==null?Integer.MAX_VALUE:chapterNumber(storyId,c);});
            filtered=filtered.stream().sorted(sort.thenComparingLong(r->id(r.get("id")))).toList();
        }
        return new Browse(filtered.stream().skip(offset).limit(limit).toList(),filtered.size(),counts,Math.max(0,page),limit);
    }
    private static List<?> list(Object value){return value instanceof List<?> values?values:List.of();}
    private String searchable(Map<String,Object> r) {
        List<Object> content=new ArrayList<>();for(String key:List.of("title","body","summary","structuredData")) content.add(r.get(key));
        if(r.get("facts") instanceof List<?> fs) fs.stream().filter(Fact.class::isInstance).map(Fact.class::cast).forEach(f->content.add(f.payload()));
        return schema.write(content);
    }
    public List<Map<String,Object>> entities(long storyId,long userId) {
        requireStory(storyId,userId);
        return jdbc.queryForList("SELECT id,kind,name,character_profile_id FROM knowledge_entities WHERE story_id=? AND NOT archived ORDER BY name,id",storyId);
    }
    @Transactional
    public long defineEntity(long storyId,long userId,String kind,String name) {
        requireStory(storyId,userId);
        if(!Set.of("PLACE","ORGANIZATION","ITEM","CURRENCY","RESOURCE","POWER_SYSTEM","RULE","HISTORY").contains(kind) || name==null || name.isBlank()) throw new BusinessException(400,"请填写实体分类与名称");
        return jdbc.queryForObject("INSERT INTO knowledge_entities(story_id,kind,name) VALUES (?,?,?) RETURNING id",Long.class,storyId,kind,name.trim());
    }
    public Graph graph(long storyId,long userId,Long asOf,Long characterId,boolean pending) {
        return graph(storyId,userId,asOf,characterId,pending,"",1);
    }
    public Graph graph(long storyId,long userId,Long asOf,Long characterId,boolean pending,String q,int hops) {
        requireStory(storyId,userId);
        List<Map<String,Object>> projected=project(storyId,asOf,null);
        List<Map<String,Object>> relations=projected.stream().filter(r->r.get("type").equals("CHARACTER_RELATION")).toList();
        List<Map<String,Object>> cards=projected.stream().filter(r->r.get("type").equals("CHARACTER_CARD")).toList();
        Map<Long,Map<String,Object>> people=new LinkedHashMap<>();
        if(asOf==null) for(var p:jdbc.queryForList("SELECT id,name FROM character_profiles WHERE story_id=?",storyId)) people.put(id(p.get("id")),new LinkedHashMap<>(p));
        for(var c:cards) {Long cid=id(map(c.get("structuredData")).get("character_id"));people.put(cid,new LinkedHashMap<>(Map.of("id",cid,"name",c.get("title"),"unitId",c.get("id"))));}
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(var r:relations) {
            Map<String,Object> data=map(r.get("structuredData")); Long from=id(data.get("source_character_id")),to=id(data.get("target_character_id"));
            if(from==null || to==null) continue;
            Map<String,Object> edge=new LinkedHashMap<>(); edge.put("id",r.get("factId"));edge.put("unitId",r.get("id"));edge.put("source",from);edge.put("target",to);edge.put("data",data);edge.put("pending",false);edge.put("search",searchable(r));candidates.add(edge);
        }
        if(pending) for(var r:browse(storyId,userId,"CHARACTER_RELATION","PENDING",q,asOf,0,100).items()) {
            Map<String,Object> data=map(r.get("structuredData"));Long from=id(data.get("source_character_id")),to=id(data.get("target_character_id"));
            if(from==null || to==null) continue;
            candidates.add(new LinkedHashMap<>(Map.of("id","candidate-"+r.get("id"),"candidateId",r.get("id"),"source",from,"target",to,"data",data,"pending",true,"search",searchable(r))));
        }
        String query=q==null?"":q.trim().toLowerCase(Locale.ROOT);
        candidates.removeIf(e->!people.containsKey(id(e.get("source")))||!people.containsKey(id(e.get("target"))));
        if(characterId!=null) {
            Set<Long> visited=new HashSet<>(Set.of(characterId)),frontier=new HashSet<>(visited);
            Set<Object> included=new HashSet<>();
            for(int step=0;step<Math.max(1,Math.min(2,hops));step++) {
                Set<Long> next=new HashSet<>();
                for(var e:candidates) if(frontier.contains(id(e.get("source")))||frontier.contains(id(e.get("target")))) {
                    included.add(e.get("id"));next.add(id(e.get("source")));next.add(id(e.get("target")));
                }
                next.removeAll(visited);visited.addAll(next);frontier=next;
            }
            candidates.removeIf(e->!included.contains(e.get("id")));
        }
        candidates.removeIf(e->!query.isBlank() && !(e.get("search")+" "+people.get(id(e.get("source"))).get("name")+" "+people.get(id(e.get("target"))).get("name")).toLowerCase(Locale.ROOT).contains(query));
        List<Map<String,Object>> edges=new ArrayList<>();Set<Long> ids=new LinkedHashSet<>();
        for(var e:candidates) {Set<Long> added=new HashSet<>(ids);added.add(id(e.get("source")));added.add(id(e.get("target")));
            if(edges.size()>=200||added.size()>80) continue;e.remove("search");edges.add(e);ids= new LinkedHashSet<>(added);
        }
        List<Map<String,Object>> nodes=ids.stream().sorted().map(people::get).toList();
        return new Graph(nodes,edges,candidates.size()>edges.size(),candidates.size());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of(); }
    public List<Map<String,Object>> history(long storyId,long userId,long factId) {
        requireStory(storyId,userId);fact(storyId,factId);
        return jdbc.queryForList("SELECT revision,payload,certainty,scope,reveal_chapter_id,effective_from_chapter_id,effective_to_chapter_id,created_at FROM knowledge_fact_revisions WHERE fact_id=? ORDER BY revision DESC",factId)
            .stream().map(r->{r.put("payload",schema.read(r.get("payload").toString()));return r;}).toList();
    }
    public List<Map<String,Object>> evidence(long storyId,long userId,long factId) {
        requireStory(storyId,userId);Fact f=fact(storyId,factId);
        return jdbc.queryForList("SELECT s.source_type,s.chapter_id,s.source_version,s.source_hash,s.snapshot,fs.excerpt,fs.location FROM knowledge_fact_sources fs JOIN knowledge_sources s ON s.id=fs.source_id WHERE fs.revision_id=?",f.revisionId())
            .stream().map(r->{r.put("snapshot",schema.read(r.get("snapshot").toString()));return r;}).toList();
    }
}
