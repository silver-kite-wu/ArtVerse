package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AgentAnswerDelivery;
import com.artverse.application.workflow.MangaWorkflowExecutionContext.ExecutionClaim;
import com.artverse.agent.gateway.AgentSessionTurns;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
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
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.TextBlock;
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
    private final NovelDraftReviewService draftReviews;

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config) {
        runBackground(conversation, message, requestId, config, null);
    }

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config,
                              com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var sink=eventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=concurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runTask(()->execute(conversation,message,requestId,config,sink,ref,null,Map.of(),List.of(),null,null,parentClaim),conversation,requestId,sink,ref); }
        finally { concurrencyGate.release(permit); }
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String decision,UUID artifactId,String answer,UserProviderConfig config) {
        resumeBackground(conversation, requestId, decision, artifactId, answer, null, null, config);
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String decision,UUID artifactId,
                                 String answer,String toolCallId,Long pendingVersion,UserProviderConfig config) {
        resumeBackground(conversation, requestId, decision, artifactId, answer, toolCallId, pendingVersion, null, config);
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String decision,UUID artifactId,
                                 String answer,String toolCallId,Long pendingVersion,String selectedOptionId,UserProviderConfig config) {
        resumeBackground(conversation, requestId, decision, artifactId, answer, toolCallId, pendingVersion, selectedOptionId, config, null);
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String decision,UUID artifactId,
                                 String answer,String toolCallId,Long pendingVersion,String selectedOptionId,UserProviderConfig config,
                                 com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var sink=eventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=concurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runTask(() -> resumeTask(conversation, requestId, decision, artifactId, answer, toolCallId, pendingVersion, selectedOptionId, config, sink, ref, parentClaim),
                conversation, requestId, sink, ref); }
        finally { concurrencyGate.release(permit); }
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
                                       String toolCallId, Long pendingVersion, String selectedOptionId,
                                       User user, UserProviderConfig llmConfig) {
        StoryChatPreparationService.Scope taskScope = scopeOf(requireConversation(chapterId, conversationId, user));
        Long userId = user.getId();
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = eventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();
        submit(userId, requestId, () -> {
            MangaAgentConversation conversation = taskScope.reference();
            runTask(() -> resumeTask(conversation, requestId, decision, artifactId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig, sink, runRef),
                    conversation, requestId, sink, runRef);
        });
        return emitter;
    }

    private void resumeTask(MangaAgentConversation conversation, UUID requestId, String decision, UUID artifactId,
                            String answer, String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig config, MangaAgentRunEventPublisher.RunEventSink sink,
                            AtomicReference<MangaAgentRun> runRef) {
        resumeTask(conversation, requestId, decision, artifactId, answer, toolCallId, pendingVersion, selectedOptionId,
                config, sink, runRef, null);
    }

    private void resumeTask(MangaAgentConversation conversation, UUID requestId, String decision, UUID artifactId,
                            String answer, String toolCallId, Long pendingVersion, String selectedOptionId,
                            UserProviderConfig config, MangaAgentRunEventPublisher.RunEventSink sink,
                            AtomicReference<MangaAgentRun> runRef, com.artverse.agent.AutomationExecutionClaim parentClaim) {
        if ("reviewed".equals(decision)) {
            draftReviews.reconcile(conversation.getUser().getId(), conversation.getChapter().getId(),
                    conversation.getConversationUuid(), requestId, artifactId, true);
            MangaAgentRun reviewedRun = runService.claimReviewedContinuation(conversation, requestId, artifactId);
            runRef.set(reviewedRun);
            if (reviewedRun.getStatus() == com.artverse.domain.MangaAgentRunStatus.SUCCEEDED) {
                sink.sendDone(reviewedRun, reviewedRun.getFinalReply(), requestId);
                return;
            }
            var snapshot = runService.snapshot(reviewedRun);
            var receipt = reviewedReceipt(snapshot, artifactId);
            execute(conversation, "人工审阅已完成，请根据实际保存回执用中文总结。", requestId,
                    config, sink, runRef, reviewedRun, receipt, reviewToolResults(snapshot, receipt), null, null, parentClaim);
            return;
        }
        if (!"answer".equals(decision)) {
            throw new BusinessException(400, "请先在正文审阅页保存或放弃最终稿，再继续生成总结。");
        }
        if (selectedOptionId == null && (answer == null || answer.isBlank() || answer.length() > 4000)) {
            throw new BusinessException(400, "请填写 1 至 4000 字的补充说明");
        }
        // These are two calls through the Spring service proxy: acceptance commits before any gate/claim.
        var accepted = runService.acceptAnswer(conversation, requestId, toolCallId, pendingVersion, answer, selectedOptionId);
        StoryChatRoute originalRoute = restoredRoute(accepted.run());
        var boundScope = scopeOf(conversation);
        var identity = new AgentRunRequest(String.valueOf(boundScope.userId()), boundScope.storyId(), boundScope.chapterId(),
                originalRoute == StoryChatRoute.WRITE || originalRoute == StoryChatRoute.POLISH
                        ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.STORY_CHAT_READ,
                List.of(), Map.of(), null, "", requestId, conversation.getConversationUuid());
        try (var turn = gateway.acquireSessionTurn(identity)) {
            if (turn == null) throw new BusinessException(503, "暂时无法取得答复投递执行权");
            var continuation = runService.claimAnswerDelivery(conversation, requestId, accepted);
            if (!continuation.claimed()) {
                sink.sendStatus(continuation.run(), "这份答复已受理，请查看当前任务状态", requestId);
                return;
            }
            MangaAgentRun resumedRun = continuation.run();
            runRef.set(resumedRun);
            execute(conversation, "用户补充说明：" + continuation.answer() + "。本次回复不代表确认写入原文；需要修改时重新准备草稿并等待确认。",
                    requestId, config, sink, runRef, resumedRun, Map.of(), List.of(continuation.delivery().toolResult()),
                    continuation.delivery(), turn, parentClaim);
        }
    }

    public OptionalRun latestOpenRun(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = requireConversation(chapterId, conversationId, user);
        return new OptionalRun(runService.findLatestOpenRun(conversation).map(runService::snapshot).orElse(null));
    }

    public List<MangaAgentRunSnapshots.RunSnapshot> conversationRuns(Long chapterId, UUID conversationId, User user) {
        return runService.storyConversationRuns(requireConversation(chapterId, conversationId, user));
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
        agentScopeGateway.interrupt(String.valueOf(user.getId()), conversation.getConversationUuid(), requestId);
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
        if (resume) throw new IllegalArgumentException("A continuation must supply its claimed execution");
        execute(conversation, message, requestId, llmConfig, sink, runRef, null, Map.of(), List.of());
    }

    private void execute(MangaAgentConversation conversation, String message, UUID requestId,
                         UserProviderConfig llmConfig, MangaAgentRunEventPublisher.RunEventSink sink,
                          AtomicReference<MangaAgentRun> runRef, MangaAgentRun claimedRun, Map<String, Object> reviewReceipt,
                         List<ToolResultBlock> externalResults) {
        execute(conversation, message, requestId, llmConfig, sink, runRef, claimedRun, reviewReceipt, externalResults, null, null);
    }

    private void execute(MangaAgentConversation conversation, String message, UUID requestId,
                         UserProviderConfig llmConfig, MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef, MangaAgentRun claimedRun, Map<String, Object> reviewReceipt,
                         List<ToolResultBlock> externalResults, AgentAnswerDelivery delivery, AgentSessionTurns.Lease turn) {
        execute(conversation, message, requestId, llmConfig, sink, runRef, claimedRun, reviewReceipt, externalResults, delivery, turn, null);
    }

    private void execute(MangaAgentConversation conversation, String message, UUID requestId,
                         UserProviderConfig llmConfig, MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef, MangaAgentRun claimedRun, Map<String, Object> reviewReceipt,
                         List<ToolResultBlock> externalResults, AgentAnswerDelivery delivery, AgentSessionTurns.Lease turn,
                         com.artverse.agent.AutomationExecutionClaim parentClaim) {
        boolean resume = claimedRun != null;
        StoryChatRoute route = resume ? restoredRoute(claimedRun) : router.classify(message);
        MangaAgentRun run = resume ? claimedRun : runService.startOrReuse(conversation, requestId, message,
                AgentRunType.STORY_CHAT, com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION, route.name());
        ExecutionClaim originalClaim = ExecutionClaim.from(run);
        try {
        runService.bindAutomationExecutionClaim(conversation, requestId, originalClaim, parentClaim);
        runRef.set(run);
        if (runService.isTerminal(conversation, requestId)) {
            throw new BusinessException(409, "该请求已经结束，请重新发送消息。");
        }
        sink.recordProgress(run, "PREPARING_CONTEXT");
        run.setCurrentPhase("PREPARING_CONTEXT");
        sink.sendStatus(run, "正在读取章节内容", requestId);
        if (reviewReceipt.isEmpty()) saveUserMessage(conversation, message, requestId);
        if (llmConfig != null && llmConfig.configId() != null) runService.recordModelConfig(run, llmConfig.configId(), originalClaim);
        try (AgentRunToolStatus.RunScope scope = toolStatus.start(
                conversation.getUser().getId(), conversation.getChapter().getId(), requestId,
                event -> {
                    MangaAgentRun active = runRef.get();
                    sink.recordToolProgress(active);
                    sink.sendToolEvent(active, event);
                })) {
            StoryChatContextSnapshot snapshot = preparation.snapshot(scopeOf(conversation), requestId, message);
            requireActive(conversation, requestId, scope, originalClaim);
            sink.sendPhase(run, requestId, "BUILDING_REQUEST", "正在整理创作上下文");
            List<AgentMessage> messages = contextAssembler.assemble(snapshot, message, route);
            AgentRunRequest request = agentRequest(snapshot, route, messages, llmConfig, run, resume, !reviewReceipt.isEmpty(), originalClaim);
            if (delivery != null) {
                var variables = new java.util.LinkedHashMap<>(request.variables());
                variables.put("answer_delivery", delivery);
                request = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                        request.messages(), Map.copyOf(variables), request.modelSpec(), request.llmApiKey(),
                        request.requestId(), request.conversationId(), request.businessSkillSelection());
            }
            if (!resume) runService.bindStoryChatExecution(conversation, requestId, executionIdentity(route, request), originalClaim);
            if (!reviewReceipt.isEmpty()) request = reviewedRequest(request, reviewReceipt);
            requireActive(conversation, requestId, scope, originalClaim);
            sink.sendPhase(run, requestId, "WAITING_MODEL", reviewReceipt.isEmpty() ? "正在分析本章" : "正文审阅已完成，正在继续总结");
            StringBuilder reply = new StringBuilder();
            String reviewedContent = "COMMITTED".equals(reviewReceipt.get("status"))
                    ? artifactService.reviewedContent(snapshot.userId(), snapshot.chapterId(), requestId,
                            UUID.fromString(String.valueOf(reviewReceipt.get("artifact_id"))), String.valueOf(reviewReceipt.get("content_hash")))
                    : snapshot.novelContent();
            var results = reviewReceipt.isEmpty() ? externalResults
                    : reviewedToolResults(externalResults, reviewReceipt, reviewedContent);
            var stream = turn != null ? gateway.streamEvents(request, results, turn)
                    : results.isEmpty() ? gateway.streamEvents(request) : gateway.streamEvents(request, results);
            try {
              for (AgentEvent event : stream.toIterable()) {
                if (scope.state().isCancelled()) throw new BusinessException(409, "任务已停止。");
                if (event instanceof ModelCallStartEvent || event instanceof ToolCallStartEvent) {
                    requireActive(conversation, requestId, scope, originalClaim);
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
            } catch (RuntimeException error) {
                if (!reply.isEmpty()) preparation.saveOwnedAssistantResult(scopeOf(conversation), reply.toString(), requestId,
                        writeSkillVersions(request.businessSkillSelection()), com.artverse.domain.ChatMessageCompletionStatus.PARTIAL, originalClaim);
                throw error;
            }
            if (scope.state().userInputRequest() != null) {
                preparation.saveWaitingAssistantResult(scopeOf(conversation), reply.isEmpty() ? "修改草稿已生成，请在正文区审阅后确认。" : reply.toString(),
                        requestId, writeSkillVersions(request.businessSkillSelection()), com.artverse.domain.ChatMessageCompletionStatus.PARTIAL, originalClaim);
                throw new AgentUserInputRequiredException(scope.state().userInputRequest());
            }
            requireActive(conversation, requestId, scope, originalClaim);
            if (reviewReceipt.isEmpty() && (route == StoryChatRoute.WRITE || route == StoryChatRoute.POLISH)) {
                var draft = artifactService.list(snapshot.userId(), snapshot.chapterId(), requestId).stream()
                        .filter(item -> "VALIDATED".equals(item.status()))
                        .reduce((left, right) -> right);
                if (draft.isPresent()) {
                    preparation.saveOwnedAssistantResult(scopeOf(conversation), reply.isEmpty() ? "修改草稿已生成，请在正文区审阅后确认。" : reply.toString(),
                            requestId, writeSkillVersions(request.businessSkillSelection()), com.artverse.domain.ChatMessageCompletionStatus.PARTIAL, originalClaim);
                    // Persist waiting even when the model omitted ask_user or its external pause was lost.
                    throw new AgentUserInputRequiredException(new AgentUserInputRequest(
                            "请在正文区审阅本章修改。", List.of(), false,
                            "artifact_id=" + draft.get().artifactId(), "BUSINESS_CONFIRMATION"));
                }
                throw new BusinessException(409, "AI 未生成可审阅的修改草稿，请重试本次优化。");
            }
            String finalReply = reply.toString().trim();
            if (finalReply.isBlank()) {
                finalReply = reviewReceipt.isEmpty() ? "本次小说对话已完成。"
                        : "REJECTED".equals(reviewReceipt.get("status")) ? "已放弃本次修改，章节正文未改变。" : "已按审阅结果保存小说原文。";
            }
            preparation.completeAssistantResult(scopeOf(conversation), finalReply, requestId,
                    writeSkillVersions(request.businessSkillSelection()), originalClaim,
                    reviewReceipt.isEmpty() ? null : UUID.fromString(String.valueOf(reviewReceipt.get("artifact_id"))));
            sink.sendDone(runRef.get(), finalReply, requestId);
        }
        } catch (AgentUserInputRequiredException waiting) {
            runService.markWaiting(conversation, requestId, waiting.request(), originalClaim);
            throw waiting;
        } catch (RuntimeException failure) {
            try {
                runService.markFailed(conversation, requestId, StoryChatFailure.from(failure, run.getCurrentPhase()).message(), originalClaim);
            } catch (BusinessException stale) { failure.addSuppressed(stale); }
            throw failure;
        }
    }

    private AgentRunRequest agentRequest(StoryChatContextSnapshot snapshot, StoryChatRoute route,
                                         List<AgentMessage> messages, UserProviderConfig llmConfig,
                                          MangaAgentRun run, boolean resume, boolean summary, ExecutionClaim claim) {
        AgentTaskType taskType = route == StoryChatRoute.WRITE || route == StoryChatRoute.POLISH
                ? AgentTaskType.STORY_CHAT_WRITE
                : AgentTaskType.STORY_CHAT_READ;
        String latestUser = messages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .reduce((left, right) -> right)
                .map(AgentMessage::content)
                .orElse("");
        BusinessSkillSelection selection = summary ? BusinessSkillSelection.empty()
                : resume ? restoredSkills(run) : skillRegistry.selectionForNovelMode(
                        route == StoryChatRoute.POLISH ? NovelBusinessSkillMode.PROSE_POLISH : skillRouter.classify(latestUser).mode());
        if (llmConfig == null || llmConfig.model().isBlank() || llmConfig.apiKey().isBlank()) {
            throw new BusinessException(400, "请先配置可用的 AI 模型。");
        }
        var variables = new java.util.LinkedHashMap<String, Object>();
        variables.put("fencing_token", claim.fencingToken());
        variables.put("execution_owner", claim.owner());
        variables.put("step_id", "story-chat:" + route.name());
        variables.put("chapter_version", snapshot.chapterVersion());
        var automationVariables = runService.automationExecutionVariables(run.getConversation(), run.getRequestId(), claim);
        if (automationVariables != null) variables.putAll(automationVariables);
        return new AgentRunRequest(
                String.valueOf(snapshot.userId()),
                snapshot.storyId(),
                snapshot.chapterId(),
                taskType,
                List.copyOf(messages),
                Map.copyOf(variables),
                modelSpecFactory.fromProviderConfig(llmConfig),
                llmConfig.apiKey(),
                snapshot.requestId(),
                snapshot.conversationId(),
                selection
        );
    }

    private Map<String, Object> executionIdentity(StoryChatRoute route, AgentRunRequest request) {
        return Map.of("version", 1, "route", route.name(), "task_type", request.taskType().name(),
                "step_id", request.variables().get("step_id"), "profile", "local-tools-v1",
                "skills", request.businessSkillSelection().manifests().stream().map(skill -> Map.of(
                        "key", skill.skillKey(), "version", skill.semanticVersion(), "checksum", skill.checksum())).toList());
    }

    private Map<?, ?> originalExecution(MangaAgentRun run) {
        try {
            var attributes = objectMapper.readValue(run.getRunAttributesJson(), Map.class);
            if (attributes.get("story_chat_execution") instanceof Map<?, ?> execution
                    && Integer.valueOf(1).equals(execution.get("version"))
                    && "local-tools-v1".equals(execution.get("profile"))) return execution;
        } catch (java.io.IOException ignored) {
            // An incompatible old state must never be reclassified into a writable continuation.
        }
        throw new BusinessException(409, "任务工具契约已更新，请保留现有产物并开始新对话。");
    }

    private StoryChatRoute restoredRoute(MangaAgentRun run) {
        var execution = originalExecution(run);
        try {
            StoryChatRoute route = StoryChatRoute.valueOf(String.valueOf(execution.get("route")));
            AgentTaskType type = route == StoryChatRoute.WRITE || route == StoryChatRoute.POLISH
                    ? AgentTaskType.STORY_CHAT_WRITE : AgentTaskType.STORY_CHAT_READ;
            if (route.name().equals(run.getRouteKey()) && type.name().equals(execution.get("task_type"))
                    && ("story-chat:" + route.name()).equals(execution.get("step_id"))) return route;
        } catch (IllegalArgumentException ignored) {
            // The persisted identity must describe one supported original route.
        }
        throw new BusinessException(409, "任务恢复身份不一致，请刷新后检查已有产物。");
    }

    private BusinessSkillSelection restoredSkills(MangaAgentRun run) {
        Object value = originalExecution(run).get("skills");
        if (!(value instanceof List<?> skills)) throw new BusinessException(409, "任务技能记录不可用。");
        var manifests = new java.util.ArrayList<ArtVerseSkillRegistry.SkillManifest>();
        for (Object entry : skills) {
            if (!(entry instanceof Map<?, ?> saved) || !(saved.get("key") instanceof String key)) {
                throw new BusinessException(409, "任务技能记录不可用。");
            }
            var current = skillRegistry.requirePublished(key);
            if (!current.semanticVersion().equals(saved.get("version")) || !current.checksum().equals(saved.get("checksum"))) {
                throw new BusinessException(409, "任务技能已更新，请保留现有产物并开始新对话。");
            }
            manifests.add(current);
        }
        var restored = new BusinessSkillSelection(manifests);
        var enabled = skillRegistry.selectionForKeys(restored.skillKeys());
        if (!restored.equals(enabled)) throw new BusinessException(409, "任务原有技能当前不可用。");
        return restored;
    }

    private void runTask(Runnable task, MangaAgentConversation conversation, UUID requestId,
                         MangaAgentRunEventPublisher.RunEventSink sink,
                         AtomicReference<MangaAgentRun> runRef) {
        try {
            task.run();
        } catch (AgentUserInputRequiredException e) {
            MangaAgentRun run = runRef.get();
            sink.sendUserInputRequested(run, requestId, e.request());
        } catch (Exception error) {
            MangaAgentRun run = runRef.get();
            String phase = run == null ? "PREPARING_CONTEXT" : run.getCurrentPhase();
            StoryChatFailure failure = StoryChatFailure.from(error, phase);
            log.error("Story chat failed requestId={} conversationId={} phase={} code={} upstreamStatus={} exception={}",
                    requestId, conversation.getConversationUuid(), phase, failure.code(), failure.upstreamStatus(), error.getClass().getName());
            String detail = failure.message();
            if (run != null) sink.sendRunEvent(run, new AgentRunEvent("execution_error", failure.stage(), detail,
                    null, "failed", null, failure.metadata(), java.time.OffsetDateTime.now()));
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

    private Map<String, Object> reviewedReceipt(MangaAgentRunSnapshots.RunSnapshot snapshot, UUID artifactId) {
        Object raw = snapshot.runAttributes().get("review_result");
        if (!(raw instanceof Map<?, ?> receipt) || artifactId == null
                || !artifactId.toString().equals(receipt.get("artifact_id"))) {
            throw new BusinessException(409, "请先完成人工审阅并保存或放弃这份草稿。");
        }
        if (!List.of("COMMITTED", "REJECTED").contains(String.valueOf(receipt.get("status")))) {
            throw new BusinessException(409, "审阅结果尚未提交，不能恢复执行。");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        receipt.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    List<ToolResultBlock> reviewToolResults(MangaAgentRunSnapshots.RunSnapshot snapshot, Map<String, Object> receipt) {
        // Complete the actual external tool call rather than letting pending-tool recovery
        // synthesize an error. IDs originate exclusively from this owned run's durable log.
        for (int i = snapshot.events().size() - 1; i >= 0; i--) {
            Map<String, Object> event = snapshot.events().get(i).data();
            // A failed model call may already have consumed the external result. Its
            // retry continues with the saved receipt, without duplicating a tool response.
            if ("review_resumed".equals(event.get("type"))) return List.of();
            if (!"external_exec_required".equals(event.get("type"))) continue;
            if (!(event.get("data") instanceof Map<?, ?> data)
                    || !(data.get("toolCalls") instanceof List<?> calls)) continue;
            return calls.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(call -> "ask_user".equals(call.get("name")) && call.get("id") instanceof String)
                    .map(call -> ToolResultBlock.builder().id(String.valueOf(call.get("id"))).name("ask_user")
                            .state(ToolResultState.SUCCESS)
                            .output(List.of(TextBlock.builder().text(writeReceipt(receipt)).build())).build())
                    .toList();
        }
        return List.of();
    }

    List<ToolResultBlock> reviewedToolResults(List<ToolResultBlock> pending, Map<String, Object> receipt, String currentContent) {
        var actualResult = new java.util.LinkedHashMap<>(receipt);
        // The author may edit the draft. Return that actual text to the original external
        // call so the resumed model cannot mistake its earlier draft for the reviewed result.
        actualResult.put("reviewed_content", currentContent);
        return pending.stream().map(call -> ToolResultBlock.builder().id(call.getId()).name(call.getName())
                .state(ToolResultState.SUCCESS)
                .output(List.of(TextBlock.builder().text(writeReceipt(actualResult)).build())).build()).toList();
    }

    private AgentRunRequest reviewedRequest(AgentRunRequest request, Map<String, Object> receipt) {
        var variables = new java.util.LinkedHashMap<>(request.variables());
        variables.put("review_completed", true);
        var messages = new java.util.ArrayList<>(request.messages());
        messages.add(0, new AgentMessage("system", """
                You are continuing the same Harness loop after the author's actual text-diff review.
                The server has already applied the exact reviewed decision. This receipt is authoritative:
                %s
                The review result contains the exact saved text, including author edits. Canonical context may have later edits;
                those later edits must not replace the immutable reviewed result or be attributed to this action.
                Continue with a concise Chinese summary of the actual result and main changes, at most 4 short bullets.
                Use the receipt's word_count for the final text length, never an earlier AI draft's length.
                The ask_user result includes reviewed_content with the author's exact final text.
                Earlier draft quotations are obsolete. Verify any quoted wording against reviewed_content;
                describe the author's changes as well as the retained AI changes, without inventing edits.
                Do not expose artifact IDs, checksums or internal tool identifiers in the author's summary.
                Do not draft, commit, request confirmation again, or create scheduled tasks.
                If the receipt is REJECTED, say that the draft was discarded and the chapter was not changed.
                """.formatted(writeReceipt(receipt))));
        return new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                List.copyOf(messages), Map.copyOf(variables), request.modelSpec(), request.llmApiKey(),
                request.requestId(), request.conversationId(), BusinessSkillSelection.empty());
    }

    private String writeReceipt(Map<String, Object> receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize author review result", error);
        }
    }

    private MangaAgentConversation requireConversation(Long chapterId, UUID conversationId, User user) {
        UUID selected = conversationId == null
                ? conversationService.storyConversation(user, chapterId).getConversationUuid() : conversationId;
        return preparation.resolve(user.getId(), chapterId, selected).reference();
    }

    private StoryChatPreparationService.Scope scopeOf(MangaAgentConversation conversation) {
        Story story = conversation.getStory();
        return new StoryChatPreparationService.Scope(conversation.getId(), conversation.getConversationUuid(),
                conversation.getUser().getId(), story == null ? null : story.getId(),
                story == null || story.getVersion() == null ? 0L : story.getVersion(),
                conversation.getChapter().getId());
    }

    private void requireActive(MangaAgentConversation conversation, UUID requestId, AgentRunToolStatus.RunScope scope, ExecutionClaim claim) {
        if (scope.state().isCancelled() || runService.isTerminal(conversation, requestId)) {
            throw new BusinessException(409, "任务已停止。");
        }
        runService.requireExecutionClaim(conversation, requestId, claim);
    }

    @Transactional
    public ChatMessage saveUserMessage(MangaAgentConversation conversation, String content, UUID requestId) {
        return preparation.saveMessage(scopeOf(conversation), MessageRole.USER, content, requestId, null);
    }

    @Transactional
    public ChatMessage saveAssistantMessage(MangaAgentConversation conversation, String content,
                                            UUID requestId, String skillVersionsJson) {
        return preparation.saveAssistantResult(scopeOf(conversation), content, requestId,
                skillVersionsJson == null || skillVersionsJson.isBlank() ? "{}" : skillVersionsJson,
                com.artverse.domain.ChatMessageCompletionStatus.COMPLETE);
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
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(AgentRunEvent.of("thinking_started", "thinking", "正在思考修改方案"));
        }
        if (event instanceof ThinkingBlockEndEvent) {
            return Optional.of(AgentRunEvent.of("thinking_finished", "thinking", "修改方案分析完成"));
        }
        if (event instanceof ExternalExecutionResultEvent result) {
            return Optional.of(AgentRunEvent.of("review_resumed", "review", "已将人工审核结果交回 Harness，继续执行"));
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
