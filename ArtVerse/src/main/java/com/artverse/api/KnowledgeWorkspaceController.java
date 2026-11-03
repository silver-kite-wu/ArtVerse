package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.KnowledgeService;
import com.artverse.application.knowledge.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.artverse.common.BusinessException;
import java.util.*;

/** Typed workspace API; legacy array endpoints remain available for existing clients. */
@RestController
@RequestMapping("/api/stories/{storyId}/knowledge")
@RequiredArgsConstructor
public class KnowledgeWorkspaceController {
    private final KnowledgeFactService facts;
    private final KnowledgeSchema schema;
    private final KnowledgeProjectionService projections;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledge;
    private long user() { return currentUser.requireCurrentUser().getId(); }

    @GetMapping("/schema")
    public List<KnowledgeSchema.TypeSchema> schema(@PathVariable long storyId) { facts.requireStory(storyId,user()); return schema.schemas(); }
    @GetMapping("/browse")
    public KnowledgeFactService.Browse browse(@PathVariable long storyId,@RequestParam(defaultValue="ALL") String type,
        @RequestParam(defaultValue="ACTIVE") String state,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) Long asOfChapterId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int pageSize,
        @RequestParam(defaultValue="") String subtype,@RequestParam(defaultValue="NARRATIVE") String order,@RequestParam(required=false) Long characterId,@RequestParam(defaultValue="") String storyline,
        @RequestParam(required=false) Long relatedCharacterId,@RequestParam(required=false) Integer minImportance,@RequestParam(required=false) Long resolutionFromChapterId,@RequestParam(required=false) Long resolutionToChapterId) {
        if(!Set.of("ALL","CHARACTER_CARD","CHARACTER_RELATION","WORLDVIEW","TIMELINE","FORESHADOWING").contains(type) || !Set.of("ACTIVE","ARCHIVED","PENDING","REJECTED","SUPERSEDED").contains(state)) throw new BusinessException(400,"筛选条件无效");
        return facts.browse(storyId,user(),type,state,q,asOfChapterId,page,pageSize,subtype,order,characterId,storyline,relatedCharacterId,minImportance,resolutionFromChapterId,resolutionToChapterId);
    }
    @GetMapping("/overview")
    public Map<String,Object> overview(@PathVariable long storyId,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Long asOfChapterId,@RequestParam(defaultValue="") String subtype,@RequestParam(required=false) Long characterId,@RequestParam(defaultValue="") String storyline,
        @RequestParam(required=false) Long relatedCharacterId,@RequestParam(required=false) Integer minImportance,@RequestParam(required=false) Long resolutionFromChapterId,@RequestParam(required=false) Long resolutionToChapterId) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String state:List.of("ACTIVE","PENDING","REJECTED","ARCHIVED","SUPERSEDED")) {
            var data=facts.browse(storyId,user(),"ALL",state,q,asOfChapterId,0,1,subtype,"NARRATIVE",characterId,storyline,relatedCharacterId,minImportance,resolutionFromChapterId,resolutionToChapterId);
            result.put(state,Map.of("total",data.total(),"counts",data.counts()));
        }
        return result;
    }
    @GetMapping("/options")
    public Map<String,Object> options(@PathVariable long storyId,@RequestParam(required=false) Long asOfChapterId) {
        facts.requireStory(storyId,user());
        List<Map<String,Object>> people=asOfChapterId==null?jdbc.queryForList("SELECT id,name FROM character_profiles WHERE story_id=? ORDER BY id",storyId):facts.project(storyId,asOfChapterId,null).stream().filter(r->r.get("type").equals("CHARACTER_CARD")).map(r->{Map<String,Object> p=new LinkedHashMap<>();p.put("id",((Map<?,?>)r.get("structuredData")).get("character_id"));p.put("name",r.get("title"));return p;}).toList();
        List<Map<String,Object>> entities=asOfChapterId==null?facts.entities(storyId,user()):facts.project(storyId,asOfChapterId,null).stream().filter(r->r.get("type").equals("WORLDVIEW")).map(r->{Map<String,Object> p=new LinkedHashMap<>();var d=(Map<?,?>)r.get("structuredData");p.put("id",d.get("entity_id"));p.put("kind",d.get("subtype"));p.put("name",r.get("title"));return p;}).filter(e->e.get("id")!=null).toList();
        return Map.of("chapters",jdbc.queryForList("SELECT id,chapter_number,display_title FROM chapters WHERE story_id=? ORDER BY chapter_number",storyId),
            "characters",people,"entities",entities,"events",facts.all(storyId,asOfChapterId,null).stream().filter(f->f.kind().equals("EVENT")).toList());
    }
    @GetMapping("/units/{unitId}")
    public Map<String,Object> unit(@PathVariable long storyId,@PathVariable long unitId,@RequestParam(required=false) Long asOfChapterId) {
        facts.requireStory(storyId,user());return facts.project(storyId,asOfChapterId,null).stream().filter(r->((Number)r.get("id")).longValue()==unitId).findFirst().orElseThrow(()->new BusinessException(404,"此视角无可见知识"));
    }
    @GetMapping("/characters")
    public List<Map<String,Object>> characters(@PathVariable long storyId,@RequestParam(required=false) Long asOfChapterId) { facts.requireStory(storyId,user());return projections.characters(storyId,asOfChapterId); }
    @GetMapping("/characters/{characterId}")
    public Map<String,Object> character(@PathVariable long storyId,@PathVariable long characterId,@RequestParam(required=false) Long asOfChapterId) {
        facts.requireStory(storyId,user());return projections.characters(storyId,asOfChapterId).stream().filter(c->Objects.equals(KnowledgeSchema.id(c.get("characterId")),characterId)).findFirst().orElseThrow(()->new BusinessException(404,"该视角无此人物"));
    }
    @GetMapping("/relations/graph")
    public KnowledgeFactService.Graph graph(@PathVariable long storyId,@RequestParam(required=false) Long asOfChapterId,@RequestParam(required=false) Long characterId,@RequestParam(defaultValue="false") boolean pending,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int hops) { return facts.graph(storyId,user(),asOfChapterId,characterId,pending,q,hops); }
    @GetMapping("/events")
    public List<Map<String,Object>> events(@PathVariable long storyId,@RequestParam(required=false) Long asOfChapterId,@RequestParam(required=false) Long characterId,@RequestParam(defaultValue="NARRATIVE") String order) { facts.requireStory(storyId,user());return projections.events(storyId,asOfChapterId,characterId,order); }
    @GetMapping("/foreshadowing")
    public List<Map<String,Object>> foreshadowing(@PathVariable long storyId,@RequestParam(required=false) Long asOfChapterId) {facts.requireStory(storyId,user());return projections.foreshadowing(storyId,asOfChapterId);}
    @PostMapping("/entities")
    public Map<String,Long> entity(@PathVariable long storyId,@RequestBody Map<String,String> body) {return Map.of("id",facts.defineEntity(storyId,user(),body.get("kind"),body.get("name")));}
    @GetMapping("/{unitId}/facts")
    public List<KnowledgeFactService.Fact> listFacts(@PathVariable long storyId,@PathVariable long unitId,@RequestParam(required=false) Long asOfChapterId) {facts.requireStory(storyId,user());return facts.all(storyId,asOfChapterId,null).stream().filter(f->f.unitId()==unitId).toList();}
    @PostMapping("/{unitId}/facts")
    public KnowledgeFactService.Fact saveFact(@PathVariable long storyId,@PathVariable long unitId,@RequestBody KnowledgeFactService.Command command) {return knowledge.saveFactCommand(storyId,unitId,user(),command);}
    @GetMapping("/facts/{factId}/history")
    public List<Map<String,Object>> history(@PathVariable long storyId,@PathVariable long factId) {return facts.history(storyId,user(),factId);}
    @GetMapping("/facts/{factId}/sources")
    public List<Map<String,Object>> evidence(@PathVariable long storyId,@PathVariable long factId) {return facts.evidence(storyId,user(),factId);}
    @PutMapping("/characters/{characterId}/images")
    public List<Map<String,Object>> images(@PathVariable long storyId,@PathVariable long characterId,@RequestBody List<KnowledgeProjectionService.ImageMetadata> images) {return projections.saveImages(storyId,user(),characterId,images);}
}
