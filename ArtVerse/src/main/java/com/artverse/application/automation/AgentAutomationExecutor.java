package com.artverse.application.automation;

import com.artverse.application.*;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.MangaAgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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
        if(!store.owned(run))return Observation.running("执行权已转移");
        boolean story="STORY_CHAT".equals(run.definition().executorKey());
        long chapterId=run.definition().payload().path("chapterId").asLong();
        User user=inputs.user(run.userId());
        var conversation=conversations.automationConversation(user,story?AiConversationType.STORY_CHAT:AiConversationType.MANGA_AGENT,chapterId,run.id(),"定时任务："+run.definition().name());
        var child=runs.findByUserIdAndChapterIdAndRequestId(run.userId(),chapterId,run.id()).orElse(null);
        store.attach(run,"agent",run.definition().executorKey(),child==null?null:child.getId(),null,null,conversation.getConversationUuid(),run.id());
        if(child!=null&&"RUNNING".equals(child.getStatus().name())) {
            boolean expired=!runService.hasActiveExecutionLease(child);
            boolean resume=run.response()!=null&&("answer".equals(run.response().path("decision").asText())
                    || (story&&"reviewed".equals(run.response().path("decision").asText())));
            if(expired&&!resume)
                return Observation.waiting("AGENT_INTERRUPTED","上次 Agent 执行已中断，请核对已有产物后关闭或重新执行");
            if(!expired)return Observation.running("Agent 正在执行");
        }
        // Cleanup after a completed invocation does not require a live provider configuration.
        if(child!=null&&run.response()!=null
                &&(child.getStatus()==MangaAgentRunStatus.SUCCEEDED||child.getStatus()==MangaAgentRunStatus.WAITING_USER)
                &&consumptionReady(run,conversation,child)) {
            consumeResponse(run);
            return observe(child);
        }
        UserProviderConfig config=inputs.config(run.userId(),run.definition().payload(),false);
        var parentClaim = new com.artverse.agent.AutomationExecutionClaim(run.id(),run.leaseOwner(),run.fencingToken());
        if(child==null) {
            if(!store.owned(run))return Observation.running("执行权已转移");
            if(story)novel.runBackground(conversation,run.definition().instruction(),run.id(),config,parentClaim);
            else manga.runBackground(conversation,run.definition().instruction(),run.id(),config,parentClaim);
        } else if(story&&run.response()!=null&&"reviewed".equals(run.response().path("decision").asText())) {
            novel.resumeBackground(conversation,run.id(),"reviewed",UUID.fromString(run.response().path("artifactId").asText()),
                    null,null,null,null,config,parentClaim);
            var refreshed=runService.findRun(conversation,run.id()).orElse(null);
            if(refreshed!=null&&"COMPLETED".equals(runService.snapshot(refreshed).runAttributes().get("review_summary_status")))consumeResponse(run);
        } else if(run.response()!=null&&(child.getStatus()==MangaAgentRunStatus.WAITING_USER
                ||("answer".equals(run.response().path("decision").asText())
                   &&Set.of(MangaAgentRunStatus.RUNNING,MangaAgentRunStatus.FAILED,MangaAgentRunStatus.INTERRUPTED).contains(child.getStatus())))) {
            var response=run.response();
            if(story) {
                try {
                    novel.resumeBackground(conversation,run.id(),response.path("decision").asText(),
                            response.hasNonNull("artifactId")?UUID.fromString(response.path("artifactId").asText()):null,response.path("answer").asText(null),
                            response.path("toolCallId").asText(null),response.hasNonNull("pendingVersion")?response.path("pendingVersion").asLong():null,
                            response.path("selectedOptionId").asText(null),config,parentClaim);
                } catch (RuntimeException error) {
                    try { if(consumptionReady(run,conversation,child))consumeResponse(run); }
                    catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
                if(consumptionReady(run,conversation,child))consumeResponse(run);
            } else {
                try {
                    manga.resumeBackground(conversation,run.id(),response.path("answer").asText(null),
                            response.path("toolCallId").asText(null),response.hasNonNull("pendingVersion")?response.path("pendingVersion").asLong():null,
                            response.path("selectedOptionId").asText(null),config,parentClaim);
                } catch (RuntimeException error) {
                    try { if(consumptionReady(run,conversation,child))consumeResponse(run); }
                    catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
                if(consumptionReady(run,conversation,child))consumeResponse(run);
            }
        } else if(Set.of(MangaAgentRunStatus.FAILED,MangaAgentRunStatus.DEGRADED).contains(child.getStatus())
                &&run.response()!=null&&"retry".equals(run.response().path("action").asText())) {
            return Observation.waiting("AGENT_INTERRUPTED","原执行无法安全重放，已有产物保留；请核对后关闭并创建新的明确运行");
        }
        child=runs.findByUserIdAndChapterIdAndRequestId(run.userId(),chapterId,run.id()).orElseThrow(()->new BusinessException(503,"Agent 尚未启动"));
        store.attach(run,"agent",run.definition().executorKey(),child.getId(),null,null,conversation.getConversationUuid(),run.id());
        return observe(child);
    }

    private boolean consumptionReady(AutomationStore.Run run,MangaAgentConversation conversation,MangaAgentRun child) {
        var response=run.response();
        if("answer".equals(response.path("decision").asText()))
            return runService.isNativeAnswerRecorded(conversation,run.id(),response.path("toolCallId").asText(null),
                    response.hasNonNull("pendingVersion")?response.path("pendingVersion").asLong():null);
        if("reviewed".equals(response.path("decision").asText())) {
            var refreshed=runService.findRun(conversation,run.id()).orElse(child);
            return "COMPLETED".equals(runService.snapshot(refreshed).runAttributes().get("review_summary_status"));
        }
        return false;
    }

    private void consumeResponse(AutomationStore.Run run) {
        if(!store.consumeResponse(run)&&!store.responseConsumed(run))
            throw new BusinessException(409,"原答复尚未完成消费或执行权已转移，已保留当前响应");
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
