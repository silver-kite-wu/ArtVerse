package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.guard.AgentConcurrencyGate;
import com.artverse.persistence.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoryChatAgentService {

    private final AiConversationService conversationService;
    private final MangaAgentRunService runService;
    private final MangaAgentRunEventPublisher eventPublisher;
    private final AgentRunToolStatus toolStatus;
    private final StoryChatRouter router;
    private final StoryChatRuntimeContextAssembler contextAssembler;
    private final AgentScopeHarnessAgentGateway gateway;
    private final AgentModelSpecFactory modelSpecFactory;
    private final ArtVerseSkillRegistry skillRegistry;
    private final NovelBusinessSkillRouter skillRouter;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentConcurrencyGate concurrencyGate;
    private final NovelContentArtifactService artifactService;
    private final AgentScopeHarnessAgentGateway agentScopeGateway;
    private final ObjectMapper objectMapper;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;
    private final StoryChatPreparationService preparation;

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config) {
        var sink=eventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=concurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runTask(()->execute(conversation,message,requestId,config,sink,ref,false),conversation,requestId,sink,ref); }
        finally { concurrencyGate.release(permit); }
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String decision,UUID artifactId,String answer,UserProviderConfig config) {
        var sink=eventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=concurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runTask(()-> {
            var snapshot=requireWaitingSnapshot(conversation,requestId);
            if("answer".equals(decision)) {
                if(answer==null||answer.isBlank())throw new BusinessException(400,"请填写补充说明");
                execute(conversation,"用户补充说明："+answer+"。本次回复不代表确认写入原文；需要修改时重新准备草稿并等待确认。",requestId,config,sink,ref,true);
                return;
            }
            UUID selected=artifactId==null?artifactFromWaiting(snapshot):artifactId;
            if("discard".equals(decision)) {
                artifactService.reject(conversation.getUser().getId(),conversation.getChapter().getId(),requestId,selected);
                runService.markSucceeded(conversation,requestId,"已放弃本次草稿。");return;
            }
            if(!"confirm".equals(decision))throw new BusinessException(400,"decision must be confirm or discard");
            toolStatus.authorizeMutationArtifact(conversation.getUser().getId(),conversation.getChapter().getId(),requestId,selected);
            execute(conversation,"用户已确认写入小说原文 artifact_id="+selected+"。调用 commit_novel_content 提交该 artifact。",requestId,config,sink,ref,true);
        },conversation,requestId,sink,ref); } finally { concurrencyGate.release(permit); }
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = conversationService.storyConversation(user, chapterId);
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message,
                                    UUID requestId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        StoryChatPreparationService.Scope taskScope = scopeOf(conversation);
        preparation.touch(taskScope, message);
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();
        submit(user.getId(), effectiveRequestId, () -> {
            MangaAgentConversation reference = taskScope.reference();
            runTask(() -> execute(reference, message, effectiveRequestId, llmConfig, sink, runRef, false),
                    reference, effectiveRequestId, sink, runRef);
        });
        return emitter;
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId,
                                       String decision, UUID artifactId, String answer,
                                       User user, UserProviderConfig llmConfig) {
        StoryChatPreparationService.Scope taskScope = scopeOf(requireConversation(chapterId, conversationId, user));
        Long userId = user.getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();
        submit(userId, requestId, () -> {
            MangaAgentConversation conversation = taskScope.reference();
            runTask(() -> {
                MangaAgentRunSnapshots.RunSnapshot snapshot = requireWaitingSnapshot(conversation, requestId);
                UUID effectiveArtifactId = artifactId == null ? artifactFromWaiting(snapshot) : artifactId;
                if ("discard".equalsIgnoreCase(decision)) {
                    artifactService.reject(userId, chapterId, requestId, effectiveArtifactId);
                    String reply = "已放弃本次小说原文草稿，章节原文未改变。";
                    saveAssistantMessage(conversation, reply, requestId, "{}");
                    MangaAgentRun run = runService.findRun(conversation, requestId)
                            .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
                    runRef.set(run);
                    runService.markSucceeded(conversation, requestId, reply);
                    sink.sendDone(run, reply, requestId);
                    return;
                }
                if (!"confirm".equalsIgnoreCase(decision)) {
                    throw new BusinessException(400, "decision must be confirm or discard");
                }
                toolStatus.authorizeMutationArtifact(userId, chapterId, requestId, effectiveArtifactId);
                String resumeMessage = "用户已确认写入小说原文 artifact_id=" + effectiveArtifactId
                        + "。现在调用 commit_novel_content 提交该 artifact，并只在工具成功后声明已保存。";
                execute(conversation, resumeMessage, requestId, llmConfig, sink, runRef, true);
            }, conversation, requestId, sink, runRef);
        });
        return emitter;
    }

    public OptionalRun latestOpenRun(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return new OptionalRun(runService.findLatestOpenRun(conversation).map(runService::snapshot).orElse(null));
    }

    public MangaAgentRunSnapshots.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return runService.findRun(conversation, requestId)
                .map(runService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunSnapshots.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        MangaAgentRun run = runService.cancel(conversation, requestId, "Story chat run cancelled by user");
        toolStatus.markCancelled(user.getId(), chapterId, requestId);
        toolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        agentScopeGateway.interrupt(requestId);
        return runService.snapshot(run);
    }

    public List<NovelContentArtifactService.ArtifactView> artifacts(Long chapterId, UUID conversationId,
                                                                    UUID requestId, User user) {
        requireConversation(chapterId, conversationId, user);
        return artifactService.list(user.getId(), chapterId, requestId);
    }

    private void execute(MangaAgentConversation conversation, String message, UUID requestId,
                         UserProviderConfig llmConfig, MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef, boolean resume) {
        StoryChatRoute route = resume ? StoryChatRoute.WRITE : router.classify(message);
        MangaAgentRun run = runService.startOrReuse(conversation, requestId, message,
                AgentRunType.STORY_CHAT, com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION, route.name());
        runRef.set(run);
        if (runService.isTerminal(conversation, requestId)) {
            throw new BusinessException(409, "该请求已经结束，请重新发送消息。");
        }
        sink.recordProgress(run, "PREPARING_CONTEXT");
        run.setCurrentPhase("PREPARING_CONTEXT");
        sink.sendStatus(run, "正在读取章节内容", requestId);
        saveUserMessage(conversation, message, requestId);
        try (AgentRunToolStatus.RunScope scope = toolStatus.start(
                conversation.getUser().getId(), conversation.getChapter().getId(), requestId,
                event -> {
                    MangaAgentRun active = runRef.get();
                    sink.recordToolProgress(active);
                    sink.sendToolEvent(active, event);
                })) {
            StoryChatContextSnapshot snapshot = preparation.snapshot(scopeOf(conversation), requestId, message);
            requireActive(conversation, requestId, scope);
            sink.sendPhase(run, requestId, "BUILDING_REQUEST", "正在整理创作上下文");
            List<AgentMessage> messages = contextAssembler.assemble(snapshot, message, route);
            AgentRunRequest request = agentRequest(snapshot, route, messages, llmConfig, run);
            requireActive(conversation, requestId, scope);
            sink.sendPhase(run, requestId, "WAITING_MODEL", "正在分析本章");
            StringBuilder reply = new StringBuilder();
            for (AgentEvent event : gateway.streamEvents(request).toIterable()) {
                if (scope.state().isCancelled()) throw new BusinessException(409, "任务已停止。");
                if (event instanceof ModelCallStartEvent || event instanceof ToolCallStartEvent) {
                    requireActive(conversation, requestId, scope);
                }
                if (event instanceof TextBlockDeltaEvent && !"GENERATING".equals(run.getCurrentPhase())) {
                    sink.sendPhase(run, requestId, "GENERATING", "正在生成回复");
                } else if (event instanceof ModelCallStartEvent) {
                    sink.sendPhase(run, requestId, "WAITING_MODEL", "正在分析本章");
                }
                sink.recordProgress(runRef.get(), run.getCurrentPhase());
                Optional<AgentRunEvent> mapped = mapAgentScopeEvent(event);
                mapped.ifPresent(agentRunEvent -> {
                    sink.sendRunEvent(runRef.get(), agentRunEvent);
                    if ("text_delta".equals(agentRunEvent.type()) && agentRunEvent.text() != null) {
                        reply.append(agentRunEvent.text());
                    }
                });
            }
            if (scope.state().userInputRequest() != null) {
                throw new AgentUserInputRequiredException(scope.state().userInputRequest());
            }
            requireActive(conversation, requestId, scope);
            if (!resume && (route == StoryChatRoute.WRITE || route == StoryChatRoute.POLISH)) {
                var draft = artifactService.list(snapshot.userId(), snapshot.chapterId(), requestId).stream()
                        .filter(item -> "VALIDATED".equals(item.status()))
                        .reduce((left, right) -> right);
                if (draft.isPresent()) {
                    // Persist waiting even when the model omitted ask_user or its external pause was lost.
                    throw new AgentUserInputRequiredException(new AgentUserInputRequest(
                            "请在正文区审阅本章修改。", List.of(), false,
                            "artifact_id=" + draft.get().artifactId(), "BUSINESS_CONFIRMATION"));
                }
                throw new BusinessException(409, "AI 未生成可审阅的修改草稿，请重试本次优化。");
            }
            String finalReply = reply.toString().trim();
            if (finalReply.isBlank() && route == StoryChatRoute.WRITE
                    && scope.state().lastSuccessfulMutatingEvent() != null) {
                finalReply = "小说原文已保存。";
            }
            if (finalReply.isBlank()) {
                finalReply = "本次小说对话已完成。";
            }
            saveAssistantMessage(conversation, finalReply, requestId,
                    writeSkillVersions(request.businessSkillSelection()));
            runService.markSucceeded(conversation, requestId, finalReply);
            sink.sendDone(runRef.get(), finalReply, requestId);
        }
    }

    private AgentRunRequest agentRequest(StoryChatContextSnapshot snapshot, StoryChatRoute route,
                                         List<AgentMessage> messages, UserProviderConfig llmConfig,
                                         MangaAgentRun run) {
        AgentTaskType taskType = route == StoryChatRoute.WRITE || route == StoryChatRoute.POLISH
                ? AgentTaskType.STORY_CHAT_WRITE
                : AgentTaskType.STORY_CHAT_READ;
        String latestUser = messages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .reduce((left, right) -> right)
                .map(AgentMessage::content)
                .orElse("");
        BusinessSkillSelection selection = skillRegistry.selectionForNovelMode(
                route == StoryChatRoute.POLISH ? NovelBusinessSkillMode.PROSE_POLISH : skillRouter.classify(latestUser).mode());
        if (llmConfig == null || llmConfig.model().isBlank() || llmConfig.apiKey().isBlank()) {
            throw new BusinessException(400, "请先配置可用的 AI 模型。");
        }
        return new AgentRunRequest(
                String.valueOf(snapshot.userId()),
                snapshot.storyId(),
                snapshot.chapterId(),
                taskType,
                List.copyOf(messages),
                Map.of("fencing_token", run.getFencingToken(), "step_id", "story-chat:" + route.name(),
                        "chapter_version", snapshot.chapterVersion()),
                modelSpecFactory.fromProviderConfig(llmConfig),
                llmConfig.apiKey(),
                snapshot.requestId(),
                snapshot.conversationId(),
                selection
        );
    }

    private void runTask(Runnable task, MangaAgentConversation conversation, UUID requestId,
                         MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef) {
        try {
            task.run();
        } catch (AgentUserInputRequiredException e) {
            MangaAgentRun run = runRef.get();
            if (run != null) {
                runService.markWaiting(conversation, requestId, e.request());
            }
            sink.sendUserInputRequested(run, requestId, e.request());
        } catch (Exception error) {
            MangaAgentRun run = runRef.get();
            String phase = run == null ? "PREPARING_CONTEXT" : run.getCurrentPhase();
            log.error("Story chat failed requestId={} conversationId={} phase={}",
                    requestId, conversation.getConversationUuid(), phase, error);
            String detail = error instanceof BusinessException ? error.getMessage()
                    : "PREPARING_CONTEXT".equals(phase) ? "章节内容准备失败，请重试。"
                    : "BUILDING_REQUEST".equals(phase) ? "创作上下文整理失败，请重试。"
                    : "AI 回复失败，请稍后重试。";
            if (run != null && !runService.isTerminal(conversation, requestId)) {
                runService.markFailed(conversation, requestId, detail);
            }
            sink.sendError(run, requestId, detail);
        } finally {
            sink.complete();
        }
    }

    private void submit(Long userId, UUID requestId, Runnable task) {
        AgentConcurrencyGate.Permit permit = concurrencyGate.acquireOrReject(userId, requestId);
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    concurrencyGate.release(permit);
                }
            });
        } catch (RejectedExecutionException error) {
            concurrencyGate.release(permit);
            throw new BusinessException(503, "Failed to submit story chat task: system overloaded, please retry");
        }
    }

    private MangaAgentRunSnapshots.RunSnapshot requireWaitingSnapshot(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRunSnapshots.RunSnapshot snapshot = runService.findRun(conversation, requestId)
                .map(runService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "该任务已不在等待确认，请刷新章节后审阅最新草稿。");
        }
        return snapshot;
    }

    private UUID artifactFromWaiting(MangaAgentRunSnapshots.RunSnapshot snapshot) {
        String reason = snapshot.userInputRequest() == null ? "" :
                java.util.Objects.toString(snapshot.userInputRequest().reason(), "") + "\n"
                        + java.util.Objects.toString(snapshot.userInputRequest().question(), "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(reason == null ? "" : reason);
        if (!matcher.find()) {
            throw new BusinessException(400, "artifact_id is required for story chat resume");
        }
        return UUID.fromString(matcher.group());
    }

    private MangaAgentConversation requireConversation(Long chapterId, UUID conversationId, User user) {
        UUID selected = conversationId == null
                ? conversationService.storyConversation(user, chapterId).getConversationUuid() : conversationId;
        return preparation.resolve(user.getId(), chapterId, selected).reference();
    }

    private StoryChatPreparationService.Scope scopeOf(MangaAgentConversation conversation) {
        return new StoryChatPreparationService.Scope(conversation.getId(), conversation.getConversationUuid(),
                conversation.getUser().getId(), conversation.getStory() == null ? null : conversation.getStory().getId(),
                conversation.getChapter().getId());
    }

    private void requireActive(MangaAgentConversation conversation, UUID requestId, AgentRunToolStatus.RunScope scope) {
        if (scope.state().isCancelled() || runService.isTerminal(conversation, requestId)) {
            throw new BusinessException(409, "任务已停止。");
        }
    }

    @Transactional
    public ChatMessage saveUserMessage(MangaAgentConversation conversation, String content, UUID requestId) {
        return preparation.saveMessage(scopeOf(conversation), MessageRole.USER, content, requestId, null);
    }

    @Transactional
    public ChatMessage saveAssistantMessage(MangaAgentConversation conversation, String content,
                                            UUID requestId, String skillVersionsJson) {
        return preparation.saveMessage(scopeOf(conversation), MessageRole.ASSISTANT, content, requestId,
                skillVersionsJson == null || skillVersionsJson.isBlank() ? "{}" : skillVersionsJson);
    }

    private String writeSkillVersions(BusinessSkillSelection selection) {
        try {
            return objectMapper.writeValueAsString(selection.skillVersions());
        } catch (Exception error) {
            return "{}";
        }
    }

    private Optional<AgentRunEvent> mapAgentScopeEvent(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            return Optional.of(new AgentRunEvent("run_started", "started", "story chat started",
                    null, "running", null, Map.of("agent", start.getName() == null ? "unknown" : start.getName()),
                    java.time.OffsetDateTime.now()));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "model is analyzing the chapter"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "model analysis complete"));
        }
        if (event instanceof RequireExternalExecutionEvent ext) {
            return Optional.of(new AgentRunEvent("external_exec_required", "waiting_input",
                    "waiting for user confirmation", null, "waiting", null,
                    Map.of("toolCalls", ext.getToolCalls()), java.time.OffsetDateTime.now()));
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_call_started", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_call_ready", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool("tool_started", tool.getToolCallName(),
                    tool.getToolCallName(), "running", Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool("tool_finished", tool.getToolCallName(),
                    tool.getToolCallName(), status, Map.of("toolCallId", tool.getToolCallId())));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isBlank() ? Optional.empty() : Optional.of(AgentRunEvent.text(delta));
        }
        if (event instanceof AgentResultEvent) {
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "final reply generated"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(AgentRunEvent.of("run_finished", "finished", "agent finished"));
        }
        return Optional.empty();
    }

    public record OptionalRun(MangaAgentRunSnapshots.RunSnapshot run) { }
}
