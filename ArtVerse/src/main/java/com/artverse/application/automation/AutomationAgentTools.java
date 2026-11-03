package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Tool arguments never determine identity, and background runs cannot multiply schedules. */
@Service
@RequiredArgsConstructor
public class AutomationAgentTools {
    private final AutomationService service;
    private final AutomationStore store;
    private final AutomationInputService inputs;
    private final ObjectMapper mapper;

    public void requireInteractive(MangaAgentRuntimeContext context) {
        if(context==null||context.requestId()==null)throw new BusinessException(403,"需要经过认证的交互式会话");
        if(context.conversationId()!=null&&Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM automation_run WHERE id=? AND user_id=?)",Boolean.class,context.conversationId(),context.userId())))
            throw new BusinessException(403,"后台定时任务不能创建或管理其他计划");
    }
    public Object create(MangaAgentRuntimeContext context,Map<String,Object> args) {
        requireInteractive(context);
        var definition=mapper.convertValue(args.get("definition"),AutomationDefinition.class);
        scope(context,definition);
        String key="agent:"+context.requestId()+":"+AutomationStore.hash(store.canonical(definition)).substring(0,32);
        var plan=service.create(context.userId(),key,definition);
        return Map.of("automation",plan,"url","#/automations/"+plan.id());
    }
    public Object list(MangaAgentRuntimeContext context) {
        requireInteractive(context);
        return service.list(context.userId(),"","",false,"",0,100).stream()
                .filter(plan->plan.definition().payload().path("storyId").asLong()==context.storyId()).toList();
    }
    public Object update(MangaAgentRuntimeContext context,Map<String,Object> args) {
        requireInteractive(context);
        UUID id=UUID.fromString(String.valueOf(args.get("automationId")));
        var existing=service.get(context.userId(),id);scope(context,existing.definition());
        String action=String.valueOf(args.getOrDefault("action","edit"));
        if("edit".equals(action)) {
            var definition=mapper.convertValue(args.get("definition"),AutomationDefinition.class);scope(context,definition);
            return service.update(context.userId(),id,definition);
        }
        Long version=args.get("expectedVersion") instanceof Number number?number.longValue():null;
        return service.lifecycle(context.userId(),id,action,version);
    }
    private void scope(MangaAgentRuntimeContext context,AutomationDefinition definition) {
        if(definition==null||definition.payload()==null||"IMAGE_GEN".equals(definition.executorKey()))throw new BusinessException(400,"对话任务工具仅支持当前作品的 Agent 与章节生图任务");
        for(Long chapterId:inputs.chapterIds(definition.payload(),definition.executorKey())) {
            var rows=store.jdbc().queryForList("SELECT c.id FROM chapters c JOIN stories s ON s.id=c.story_id WHERE c.id=? AND s.id=? AND s.user_id=?",chapterId,context.storyId(),context.userId());
            if(rows.isEmpty())throw new BusinessException(403,"任务范围必须属于当前作品");
        }
    }
}
