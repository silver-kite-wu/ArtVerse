package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.artverse.application.CreativeProposalArtifacts.*;

@Service @RequiredArgsConstructor
public class SettingsProposalService {
    private final CreativeProposalArtifacts proposals;
    private final StoryRepository stories;
    private final CharacterProfileRepository characters;
    private final KnowledgeCandidateService candidates;
    private final JdbcTemplate jdbc;

    @Transactional
    public Map<String,Object> story(MangaAgentRuntimeContext c,long expected,Map<String,String> patch,AgentToolAttempt attempt) {
        proposals.active(c);lockStory(c.storyId(),c.userId());
        var story=stories.findById(c.storyId()).orElseThrow(CreativeProposalArtifacts::denied);
        if(story.getVersion()!=expected)throw conflict("作品基准版本已变化");
        var before=storySnapshot(story);var after=apply(before,patch,false);
        return proposals.create(c,"SETTINGS_CHANGE",Map.of("before",before,"after",after,"base_version",Long.toString(expected)),attempt);
    }
    @Transactional
    public Map<String,Object> character(MangaAgentRuntimeContext c,String mode,Long id,Long expected,Map<String,String> patch,AgentToolAttempt attempt) {
        proposals.active(c);lockStory(c.storyId(),c.userId());
        if(!Set.of("CREATE","UPDATE").contains(Objects.toString(mode,"")))throw new BusinessException(400,"需要明确 CREATE 或 UPDATE");
        var p=new LinkedHashMap<String,Object>();p.put("mode",mode);
        Map<String,String> before=Map.of("name","","description","");
        if("UPDATE".equals(mode)) {
            if(id==null||expected==null)throw new BusinessException(400,"修改人物需要稳定 ID 和 base_version");
            lockCharacter(id,c.storyId());var profile=characters.findByIdAndUserId(id,c.userId()).orElseThrow(CreativeProposalArtifacts::denied);
            if(!profile.getStory().getId().equals(c.storyId())||!profile.getVersion().equals(expected))throw conflict("人物基准版本已变化");
            before=characterSnapshot(profile);p.put("character_id",id.toString());p.put("base_version",expected.toString());
        } else if(id!=null||expected!=null)throw new BusinessException(400,"新建人物不能携带修改目标或版本");
        var after=apply(before,patch,true);p.put("before",before);p.put("after",after);
        return proposals.create(c,"CHARACTER_CHANGE",p,attempt);
    }
    @Transactional
    public Map<String,Object> decide(long actor,UUID id,String decision,String hash) {
        var row=proposals.owned(actor,id,true);
        if(!Set.of("SETTINGS_CHANGE","CHARACTER_CHANGE").contains(row.get("artifact_type")))throw denied();
        proposals.verifyDecision(row,decision,hash);
        if(!"DRAFT".equals(row.get("status")))return proposals.view(row);
        if("discard".equals(decision))proposals.finish(row,"REJECTED",Map.of());
        else {
            long storyId=((Number)row.get("story_id")).longValue();lockStory(storyId,actor);
            var story=stories.findById(storyId).orElseThrow(CreativeProposalArtifacts::denied);
            var p=proposals.payload(row);var after=strings(p.get("after"));var result=new LinkedHashMap<String,Object>();
            if("SETTINGS_CHANGE".equals(row.get("artifact_type"))) {
                if(!story.getVersion().toString().equals(p.get("base_version"))||!storySnapshot(story).equals(p.get("before")))throw conflict("作品已被编辑，请重新比较");
                apply(storySnapshot(story),after,false);
                story.setTitle(after.get("title"));story.setDescription(after.get("description"));story.setMangaStyle(after.get("manga_style"));stories.saveAndFlush(story);
                var candidate=candidates.proposeStorySettings(story,actor,id.toString());result.put("candidate_id",candidate.id().toString());result.put("saved_version",story.getVersion().toString());
            } else {
                CharacterProfile profile;
                if("CREATE".equals(p.get("mode"))) {profile=new CharacterProfile();profile.setStory(story);}
                else {
                    long target=Long.parseLong(p.get("character_id").toString());lockCharacter(target,storyId);
                    profile=characters.findByIdAndUserId(target,actor).orElseThrow(CreativeProposalArtifacts::denied);
                    if(!profile.getVersion().toString().equals(p.get("base_version"))||!characterSnapshot(profile).equals(p.get("before")))throw conflict("人物已被编辑，请重新比较");
                }
                apply(Map.of("name","","description",""),after,true);
                profile.setName(after.get("name"));profile.setDescription(after.get("description"));profile=characters.saveAndFlush(profile);
                var candidate=candidates.proposeCharacterProfile(profile,actor);result.put("candidate_id",candidate.id().toString());result.put("character_id",profile.getId().toString());result.put("saved_version",profile.getVersion().toString());
            }
            result.put("confirmed_fact",false);proposals.finish(row,"COMMITTED",result);
        }
        return proposals.view(proposals.owned(actor,id,false));
    }
    private void lockStory(long id,long actor) {if(jdbc.queryForList("SELECT id FROM stories WHERE id=? AND user_id=? FOR UPDATE",id,actor).size()!=1)throw denied();}
    private void lockCharacter(long id,long story) {if(jdbc.queryForList("SELECT id FROM character_profiles WHERE id=? AND story_id=? FOR UPDATE",id,story).size()!=1)throw denied();}
    public static Map<String,String> storySnapshot(Story s) {return Map.of("title",s.getTitle(),"description",Objects.toString(s.getDescription(),""),"manga_style",s.getMangaStyle());}
    private Map<String,String> characterSnapshot(CharacterProfile p) {return Map.of("name",p.getName(),"description",Objects.toString(p.getDescription(),""));}
    private Map<String,String> apply(Map<String,String> before,Map<String,String> patch,boolean character) {
        if(patch==null||patch.isEmpty()||!before.keySet().containsAll(patch.keySet())||patch.values().stream().anyMatch(Objects::isNull))throw new BusinessException(400,"字段不在白名单或没有修改");
        var result=new LinkedHashMap<>(before);result.putAll(patch);String title=result.get(character?"name":"title");
        if(title==null||title.isBlank()||title.length()>255||result.get("description").length()>12000)throw new BusinessException(400,"标题/人物名不能为空且不得超过 255 字符，描述上限 12000");
        if(!character&&!StorySettingsPolicy.MANGA_STYLES.contains(result.get("manga_style")))throw new BusinessException(400,"不支持的漫画风格");return result;
    }
    @SuppressWarnings("unchecked") private Map<String,String> strings(Object value) {return (Map<String,String>)value;}
}
