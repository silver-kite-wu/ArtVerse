package com.artverse.application.automation;

import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AgentAutomationExecutor implements AutomationExecutor {
    private final AutomationStore store;
    private final AutomationInputService inputs;
    private final AiConversationService conversations;
    private final MangaAgentRunRepository runs;
    private final MangaAgentRunService runService;
    private final MangaAgentService manga;
    private final StoryChatAgentService novel;
    public Set<String> keys() { return Set.of("MANGA_AGENT","STORY_CHAT"); }

    public Observation execute(AutomationStore.Run run) {
        boolean story="STORY_CHAT".equals(run.definition().executorKey());
        long chapterId=run.definition().payload().path("chapterId").asLong();
        User user=inputs.user(run.userId());
        var conversation=conversations.automationConversation(user,story?AiConversationType.STORY_CHAT:AiConversationType.MANGA_AGENT,chapterId,run.id(),"定时任务："+run.definition().name());
        var child=runs.findByUserIdAndChapterIdAndRequestId(run.userId(),chapterId,run.id()).orElse(null);
        store.attach(run,"agent",run.definition().executorKey(),child==null?null:child.getId(),null,null,conversation.getConversationUuid(),run.id());
        if(child!=null&&"RUNNING".equals(child.getStatus().name())) {
            if(child.getLeaseUntil()!=null&&child.getLeaseUntil().isBefore(OffsetDateTime.now()))
                return Observation.waiting("AGENT_INTERRUPTED","上次 Agent 执行已中断，请核对已有产物后关闭或重新执行");
            return Observation.running("Agent 正在执行");
        }
        UserProviderConfig config=inputs.config(run.userId(),run.definition().payload(),false);
        if(child==null) {
            if(!store.owned(run))return Observation.running("执行权已转移");
            if(story)novel.runBackground(conversation,run.definition().instruction(),run.id(),config);
            else manga.runBackground(conversation,run.definition().instruction(),run.id(),config);
        } else if(child.getStatus()==MangaAgentRunStatus.WAITING_USER&&run.response()!=null) {
            var response=run.response();
            if(story)novel.resumeBackground(conversation,run.id(),response.path("decision").asText(),
                    response.hasNonNull("artifactId")?UUID.fromString(response.path("artifactId").asText()):null,response.path("answer").asText(),config);
            else manga.resumeBackground(conversation,run.id(),response.path("answer").asText(),config);
            store.jdbc().update("UPDATE automation_run SET response=NULL WHERE id=? AND fencing_token=?",run.id(),run.fencingToken());
        } else if(Set.of(MangaAgentRunStatus.FAILED,MangaAgentRunStatus.DEGRADED).contains(child.getStatus())
                &&run.response()!=null&&"retry".equals(run.response().path("action").asText())) {
            // Reopen only an explicitly failed run. Existing artifact/tool idempotency remains authoritative.
            store.jdbc().update("UPDATE manga_agent_runs SET status='WAITING_USER',completed_at=NULL,error_message=NULL WHERE id=? AND status IN ('FAILED','DEGRADED')",child.getId());
            if(story)novel.runBackground(conversation,run.definition().instruction(),run.id(),config);
            else manga.runBackground(conversation,run.definition().instruction(),run.id(),config);
            store.jdbc().update("UPDATE automation_run SET response=NULL WHERE id=? AND fencing_token=?",run.id(),run.fencingToken());
        }
        child=runs.findByUserIdAndChapterIdAndRequestId(run.userId(),chapterId,run.id()).orElseThrow(()->new BusinessException(503,"Agent 尚未启动"));
        store.attach(run,"agent",run.definition().executorKey(),child.getId(),null,null,conversation.getConversationUuid(),run.id());
        return observe(child);
    }
    public Observation cancel(AutomationStore.Run run) {
        long chapterId=run.definition().payload().path("chapterId").asLong();
        var child=runs.findByUserIdAndChapterIdAndRequestId(run.userId(),chapterId,run.id()).orElse(null);
        if(child==null)return new Observation("CANCELLED",null,"本次运行已停止",null,0);
        if(child.getStatus()==MangaAgentRunStatus.SUCCEEDED)return observe(child);
        User user=inputs.user(run.userId());
        if("STORY_CHAT".equals(run.definition().executorKey()))novel.cancelRun(chapterId,run.id(),run.id(),user);
        else manga.cancelRun(chapterId,run.id(),run.id(),user);
        return new Observation("CANCELLED",null,"已停止 Agent 执行，已有产物保留",null,0);
    }
    private Observation observe(MangaAgentRun child) {
        return switch(child.getStatus()) {
            case RUNNING -> Observation.running("Agent 正在执行");
            case WAITING_USER -> Observation.waiting("AGENT_INPUT","Agent 需要你确认或补充信息，请查看运行详情");
            case SUCCEEDED -> Observation.done(Objects.toString(child.getFinalReply(),"Agent 任务完成"));
            case CANCELLED -> new Observation("CANCELLED",null,"Agent 运行已停止",null,0);
            case INTERRUPTED -> Observation.waiting("AGENT_INTERRUPTED","Agent 运行中断，请核对已有产物后处理");
            case DEGRADED -> new Observation("PARTIAL_FAILED","AGENT_DEGRADED",Objects.toString(child.getFinalReply(),"Agent 任务部分完成"),null,0);
            case FAILED -> Observation.failed("AGENT_FAILED",AutomationStore.clip(Objects.toString(child.getErrorMessage(),"Agent 执行失败"),500));
        };
    }
}
