package com.artverse.application;

import com.artverse.application.workflow.MangaWorkflowOrchestrator;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.application.workflow.MangaRoutingMetrics;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.User;
import com.artverse.guard.AgentConcurrencyGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private final MangaAgentConversationService conversationService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaAgentRunEventPublisher mangaAgentRunEventPublisher;
    private final MangaWorkflowOrchestrator mangaWorkflowOrchestrator;
    private final AgentRunToolStatus agentRunToolStatus;
    private final ChapterAccessService chapterAccessService;
    private final ArtVerseProperties properties;
    private final AgentConcurrencyGate agentConcurrencyGate;
    private final AgentScopeHarnessAgentGateway agentScopeGateway;
    private final StoryboardArtifactService storyboardArtifacts;

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;
    private final MangaRoutingMetrics routingMetrics;

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config) {
        runBackground(conversation, message, requestId, config, null);
    }

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config,
                              com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var sink=mangaAgentRunEventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=agentConcurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runStreamTask(()->executeStreamConversation(conversation,message,requestId,null,MangaRouteSource.AUTO,config,sink,ref,parentClaim),conversation,requestId,sink,ref); }
        finally { agentConcurrencyGate.release(permit); }
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String answer,UserProviderConfig config) {
        resumeBackground(conversation, requestId, answer, null, null, null, config);
    }

    public void resumeBackground(MangaAgentConversation conversation, UUID requestId, String answer,
                                 String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig config) {
        resumeBackground(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, config, null);
    }

    public void resumeBackground(MangaAgentConversation conversation, UUID requestId, String answer,
                                 String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig config,
                                 com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var sink=mangaAgentRunEventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=agentConcurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runStreamTask(()-> {
            if (toolCallId != null) {
                resumeNativeAnswer(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, config, sink, ref, parentClaim);
                return;
            }
            var snapshot=requireWaitingSnapshot(conversation,requestId);
            requireNonNativeDecision(snapshot);
            if(handleMutationDecision(conversation,snapshot,answer,sink,ref)||handleContextMissingDecision(conversation,snapshot,answer,sink,ref))return;
            executeStreamConversation(conversation,resumeMessage(snapshot,answer),requestId,routeForResume(snapshot,answer),routeSourceForResume(snapshot,answer),config,sink,ref,parentClaim);
        },conversation,requestId,sink,ref); } finally { agentConcurrencyGate.release(permit); }
    }

    private Map<String, Object> resumeNativeAnswer(MangaAgentConversation conversation, UUID requestId, String answer,
            String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig config,
            MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> suppliedRef) {
        return resumeNativeAnswer(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId,
                config, sink, suppliedRef, null);
    }

    private Map<String, Object> resumeNativeAnswer(MangaAgentConversation conversation, UUID requestId, String answer,
            String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig config,
            MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> suppliedRef,
            com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var accepted = mangaAgentRunService.acceptAnswer(conversation, requestId, toolCallId, pendingVersion, answer, selectedOptionId);
        var execution = mangaAgentRunService.answerExecution(conversation, requestId, accepted);
        var identity = new com.artverse.agent.AgentRunRequest(String.valueOf(conversation.getUser().getId()),
                conversation.getStory().getId(), conversation.getChapter().getId(), execution.taskType(), List.of(),
                Map.of("step_id", execution.stepId()), null, "", requestId, conversation.getConversationUuid());
        var runRef = suppliedRef == null ? new AtomicReference<MangaAgentRun>() : suppliedRef;
        try (var turn = agentScopeGateway.acquireSessionTurn(identity)) {
            if (turn == null) throw new BusinessException(503, "暂时无法取得答复投递执行权");
            try (var scope = agentRunToolStatus.start(conversation.getUser().getId(), conversation.getChapter().getId(), requestId,
                    event -> {
                        if (sink != null) { sink.recordToolProgress(runRef.get()); sink.sendToolEvent(runRef.get(), event); }
                    })) {
                return mangaWorkflowOrchestrator.resumeAnswerWithToolState(conversation, requestId, accepted, execution,
                        turn, scope.state(), config, sink, runRef, parentClaim);
            }
        }
    }

    private void requireNonNativeDecision(MangaAgentRunSnapshots.RunSnapshot snapshot) {
        if (snapshot.userInputRequest() != null && "USER_QUESTION".equals(snapshot.userInputRequest().purpose()))
            throw new BusinessException(400, "请提供原提问 toolCallId、pendingVersion 及原选项或答复");
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return conversationService.listMessages(conversation);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> listConversations(Long chapterId, User user) {
        return conversationService.listConversations(chapterId, user);
    }

    public MangaAgentConversation createConversation(Long chapterId, User user) {
        return conversationService.createConversation(chapterId, user);
    }

    public MangaAgentConversation archiveConversation(Long chapterId, UUID conversationId, User user) {
        return conversationService.archiveConversation(chapterId, user, conversationId);
    }

    public void deleteConversation(Long chapterId, UUID conversationId, User user) {
        conversationService.deleteConversation(chapterId, user, conversationId);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, UUID conversationId, User user) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return conversationService.listMessages(conversation);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        return run(chapterId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return runInternal(conversation, message, requestId, null, MangaRouteSource.AUTO, llmConfig);
    }

    public RunResult run(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        return run(chapterId, conversationId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult run(Long chapterId, UUID conversationId, String message, UUID requestId, User user,
                         UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return runInternal(conversation, message, requestId, null, MangaRouteSource.AUTO, llmConfig);
    }

    private RunResult runInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                  MangaWorkflowRoute route, MangaRouteSource routeSource,
                                  UserProviderConfig llmConfig) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        conversationService.autoTitle(conversation, message);
        conversationService.touch(conversation);
        AgentConcurrencyGate.Permit permit = agentConcurrencyGate.acquireOrReject(
                conversation.getUser().getId(), effectiveRequestId);
        try {
            try (AgentRunToolStatus.RunScope scope = agentRunToolStatus.start(
                    conversation.getUser().getId(),
                    conversation.getChapter().getId(),
                    effectiveRequestId
            )) {
                return new RunResult(
                        String.valueOf(mangaWorkflowOrchestrator.runWithToolState(
                                        conversation, message, effectiveRequestId, route, scope.state(), llmConfig, routeSource)
                                .getOrDefault("reply", "")),
                        effectiveRequestId
                );
            }
        } finally {
            agentConcurrencyGate.release(permit);
        }
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user) {
        return runAgUiStream(chapterId, message, requestId, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter runAgUiStream(Long chapterId, String message, UUID requestId, User user,
                                    UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return runStreamInternal(conversation, message, requestId, null, llmConfig);
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user) {
        return runAgUiStream(chapterId, conversationId, message, requestId, user,
                mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter runAgUiStream(Long chapterId, UUID conversationId, String message, UUID requestId, User user,
                                    UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return runStreamInternal(conversation, message, requestId, null, llmConfig);
    }

    private SseEmitter runStreamInternal(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaWorkflowRoute route, UserProviderConfig llmConfig) {
        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        conversationService.autoTitle(conversation, message);
        conversationService.touch(conversation);
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = mangaAgentRunEventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        submitStreamTask(conversation.getUser().getId(), effectiveRequestId,
                () -> runStreamTask(() -> executeStreamConversation(
                conversation,
                message,
                effectiveRequestId,
                route,
                MangaRouteSource.AUTO,
                llmConfig,
                sink,
                runRef
        ), conversation, effectiveRequestId, sink, runRef));

        return emitter;
    }

    private void submitStreamTask(Long userId, UUID requestId, Runnable task) {
        AgentConcurrencyGate.Permit permit = agentConcurrencyGate.acquireOrReject(userId, requestId);
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } finally {
                    agentConcurrencyGate.release(permit);
                }
            });
        } catch (RejectedExecutionException e) {
            agentConcurrencyGate.release(permit);
            throw new BusinessException(503, "Failed to submit agent task: system overloaded, please retry", "agent");
        }
    }

    private void executeStreamConversation(MangaAgentConversation conversation, String message, UUID requestId,
                                           MangaWorkflowRoute route, MangaRouteSource routeSource,
                                           UserProviderConfig llmConfig,
                                           MangaAgentRunEventPublisher.RunEventSink sink,
                                           AtomicReference<MangaAgentRun> runRef) {
        executeStreamConversation(conversation, message, requestId, route, routeSource, llmConfig, sink, runRef, null);
    }

    private void executeStreamConversation(MangaAgentConversation conversation, String message, UUID requestId,
                                           MangaWorkflowRoute route, MangaRouteSource routeSource, UserProviderConfig llmConfig,
                                           MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> runRef,
                                           com.artverse.agent.AutomationExecutionClaim parentClaim) {
        User user = conversation.getUser();
        Long chapterId = conversation.getChapter().getId();
        try (AgentRunToolStatus.RunScope ignored = agentRunToolStatus.start(
                user.getId(),
                chapterId,
                requestId,
                event -> {
                    MangaAgentRun run = runRef.get();
                    sink.recordToolProgress(run);
                    sink.sendToolEvent(run, event);
                }
        )) {
            mangaWorkflowOrchestrator.runStreamLeader(
                    conversation, message, requestId, route, ignored.state(), sink, runRef, llmConfig, routeSource, parentClaim);
        }
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        return resumeAgUiStream(chapterId, requestId, answer, null, null, null, user, llmConfig);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, String toolCallId,
                                       Long pendingVersion, String selectedOptionId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, conversationId, requestId, answer, user,
                mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        return resumeAgUiStream(chapterId, conversationId, requestId, answer, null, null, null, user, llmConfig);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer,
                                       String toolCallId, Long pendingVersion, String selectedOptionId,
                                       User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeStreamInternal(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig);
    }

    private SseEmitter resumeStreamInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                            String toolCallId, Long pendingVersion, String selectedOptionId,
                                            UserProviderConfig llmConfig) {
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = mangaAgentRunEventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        submitStreamTask(conversation.getUser().getId(), requestId, () -> runStreamTask(() -> {
            if (toolCallId != null) {
                resumeNativeAnswer(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig, sink, runRef);
                return;
            }
            MangaAgentRunSnapshots.RunSnapshot snapshot = requireWaitingSnapshot(conversation, requestId);
            requireNonNativeDecision(snapshot);
            if (handleMutationDecision(conversation, snapshot, answer, sink, runRef)) {
                return;
            }
            if (handleContextMissingDecision(conversation, snapshot, answer, sink, runRef)) {
                return;
            }
            executeStreamConversation(
                    conversation,
                    resumeMessage(snapshot, answer),
                    requestId,
                    routeForResume(snapshot, answer),
                    routeSourceForResume(snapshot, answer),
                    llmConfig,
                    sink,
                    runRef
            );
        }, conversation, requestId, sink, runRef));

        return emitter;
    }

    private void runStreamTask(Runnable task, MangaAgentConversation conversation, UUID requestId,
                               MangaAgentRunEventPublisher.RunEventSink sink,
                               AtomicReference<MangaAgentRun> runRef) {
        try {
            task.run();
        } catch (AgentUserInputRequiredException e) {
            MangaAgentRun run = runRef.get();
            sink.sendUserInputRequested(run, requestId, e.request());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            MangaAgentRun run = runRef.get();
            sink.sendError(run, requestId, detail);
        } finally {
            sink.complete();
        }
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        return resume(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user, UserProviderConfig llmConfig) {
        return resume(chapterId, requestId, answer, null, null, null, user, llmConfig);
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, String toolCallId,
                            Long pendingVersion, String selectedOptionId, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeInternal(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig);
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resume(chapterId, conversationId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                            UserProviderConfig llmConfig) {
        return resume(chapterId, conversationId, requestId, answer, null, null, null, user, llmConfig);
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer,
                            String toolCallId, Long pendingVersion, String selectedOptionId, User user,
                            UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeInternal(conversation, requestId, answer, toolCallId, pendingVersion, selectedOptionId, llmConfig);
    }

    private RunResult resumeInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                     String toolCallId, Long pendingVersion, String selectedOptionId, UserProviderConfig llmConfig) {
        if (toolCallId != null) {
            var permit = agentConcurrencyGate.acquireOrReject(conversation.getUser().getId(), requestId);
            try {
                return new RunResult(String.valueOf(resumeNativeAnswer(conversation, requestId, answer, toolCallId,
                        pendingVersion, selectedOptionId, llmConfig, null, null).getOrDefault("reply", "")), requestId);
            } finally {
                agentConcurrencyGate.release(permit);
            }
        }
        MangaAgentRunSnapshots.RunSnapshot snapshot = mangaAgentRunService.snapshot(
                mangaAgentRunService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"))
        );
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        requireNonNativeDecision(snapshot);
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null) {
            throw new BusinessException(409, "No waiting user input request on the run");
        }
        if ("MUTATION_CONFIRMATION".equalsIgnoreCase(waiting.purpose())) {
            if (!isAffirmative(answer)) {
                String reply = "已取消覆盖操作，当前分镜保持不变。";
                mangaWorkflowOrchestrator.completeWaitingDecision(conversation, requestId, waiting, reply);
                return new RunResult(reply, requestId);
            }
            storyboardArtifacts.authorizeOverwrite(conversation.getUser().getId(), conversation.getChapter().getId(),
                    requestId, conversation.getConversationUuid());
        }
        if ("CONTEXT_MISSING".equalsIgnoreCase(waiting.purpose()) && isCancelSelection(waiting, answer)) {
            String reply = "已取消本次需要上下文的写入请求。补齐章节正文或分镜后，可以随时重新发起。";
            mangaWorkflowOrchestrator.completeWaitingDecision(conversation, requestId, waiting, reply);
            return new RunResult(reply, requestId);
        }
        String message = conversationService.resumeMessage(snapshot.inputMessage(), waiting, answer);
        try {
            return runInternal(conversation, message, requestId, routeForResume(snapshot, answer),
                    routeSourceForResume(snapshot, answer), llmConfig);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(detail, e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRunSnapshots.RunSnapshot> latestOpenRun(Long chapterId, User user) {
        interruptStalledRunningRuns();
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public Optional<MangaAgentRunSnapshots.RunSnapshot> latestOpenRun(Long chapterId, UUID conversationId, User user) {
        interruptStalledRunningRuns();
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return mangaAgentRunService.findLatestOpenRun(conversation)
                .map(mangaAgentRunService::snapshot);
    }

    public MangaAgentRunSnapshots.RunSnapshot getRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        interruptStalledRunningRuns();
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunSnapshots.RunSnapshot getRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        interruptStalledRunningRuns();
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return mangaAgentRunService.findRun(conversation, requestId)
                .map(mangaAgentRunService::snapshot)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
    }

    public MangaAgentRunSnapshots.RunSnapshot cancelRun(Long chapterId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        routingMetrics.recordRunOutcome(run.getRoute(), "CANCELLED");
        agentRunToolStatus.markCancelled(user.getId(), chapterId, requestId);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        agentScopeGateway.interrupt(String.valueOf(user.getId()), conversation.getConversationUuid(), requestId);
        return mangaAgentRunService.snapshot(run);
    }

    public MangaAgentRunSnapshots.RunSnapshot cancelRun(Long chapterId, UUID conversationId, UUID requestId, User user) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required");
        }
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        MangaAgentRun run = mangaAgentRunService.cancel(conversation, requestId, "Agent run cancelled by user");
        routingMetrics.recordRunOutcome(run.getRoute(), "CANCELLED");
        agentRunToolStatus.markCancelled(user.getId(), chapterId, requestId);
        agentRunToolStatus.clearWaitingInput(user.getId(), chapterId, requestId);
        agentScopeGateway.interrupt(String.valueOf(user.getId()), conversation.getConversationUuid(), requestId);
        return mangaAgentRunService.snapshot(run);
    }

    private MangaAgentRunSnapshots.RunSnapshot requireWaitingSnapshot(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRunSnapshots.RunSnapshot snapshot = mangaAgentRunService.snapshot(
                mangaAgentRunService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"))
        );
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        return snapshot;
    }

    private String resumeMessage(MangaAgentRunSnapshots.RunSnapshot snapshot, String answer) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null) {
            throw new BusinessException(409, "No waiting user input request on the run");
        }
        return conversationService.resumeMessage(snapshot.inputMessage(), waiting, answer);
    }

    private MangaWorkflowRoute routeForResume(MangaAgentRunSnapshots.RunSnapshot snapshot, String answer) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting != null && "ROUTING".equalsIgnoreCase(waiting.purpose())) {
            // If user chose "advice" (只给建议), force safe read-only route instead of re-routing
            if (isAdviceSelection(waiting, answer)) {
                return MangaWorkflowRoute.CONVERSATION;
            }
            return null; // re-route via LLM for "edit" selection
        }
        if (waiting != null && "CONTEXT_MISSING".equalsIgnoreCase(waiting.purpose())
                && isAdviceSelection(waiting, answer)) {
            return MangaWorkflowRoute.CONVERSATION;
        }
        return snapshot.route();
    }

    private MangaRouteSource routeSourceForResume(MangaAgentRunSnapshots.RunSnapshot snapshot, String answer) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting != null && "ROUTING".equalsIgnoreCase(waiting.purpose())
                && !isAdviceSelection(waiting, answer)) {
            return MangaRouteSource.RESUME_RECLASSIFIED;
        }
        return MangaRouteSource.RESUME_FIXED;
    }

    private boolean isAdviceSelection(AgentUserInputRequest waiting, String answer) {
        if (waiting == null || waiting.options() == null || answer == null) {
            return false;
        }
        String trimmed = answer.trim();
        return waiting.options().stream()
                .anyMatch(opt -> "advice".equals(opt.id()) && opt.id().equals(trimmed));
    }

    private boolean isCancelSelection(AgentUserInputRequest waiting, String answer) {
        if (waiting == null || waiting.options() == null || answer == null) {
            return false;
        }
        String trimmed = answer.trim();
        return waiting.options().stream()
                .anyMatch(opt -> "cancel".equals(opt.id()) && opt.id().equals(trimmed));
    }

    private boolean handleMutationDecision(MangaAgentConversation conversation,
                                           MangaAgentRunSnapshots.RunSnapshot snapshot,
                                           String answer,
                                           MangaAgentRunEventPublisher.RunEventSink sink,
                                           AtomicReference<MangaAgentRun> runRef) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null || !"MUTATION_CONFIRMATION".equalsIgnoreCase(waiting.purpose())) {
            return false;
        }
        if (isAffirmative(answer)) {
            storyboardArtifacts.authorizeOverwrite(conversation.getUser().getId(), conversation.getChapter().getId(),
                    snapshot.requestId(), conversation.getConversationUuid());
            return false;
        }
        MangaAgentRun run = mangaAgentRunService.findRun(conversation, snapshot.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        runRef.set(run);
        String reply = "已取消覆盖操作，当前分镜保持不变。";
        mangaWorkflowOrchestrator.completeWaitingDecision(conversation, snapshot.requestId(), waiting, reply);
        sink.sendDone(run, reply, snapshot.requestId());
        return true;
    }

    private boolean handleContextMissingDecision(MangaAgentConversation conversation,
                                                 MangaAgentRunSnapshots.RunSnapshot snapshot,
                                                 String answer,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 AtomicReference<MangaAgentRun> runRef) {
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null || !"CONTEXT_MISSING".equalsIgnoreCase(waiting.purpose())
                || !isCancelSelection(waiting, answer)) {
            return false;
        }
        MangaAgentRun run = mangaAgentRunService.findRun(conversation, snapshot.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        runRef.set(run);
        String reply = "已取消本次需要上下文的写入请求。补齐章节正文或分镜后，可以随时重新发起。";
        mangaWorkflowOrchestrator.completeWaitingDecision(conversation, snapshot.requestId(), waiting, reply);
        sink.sendDone(run, reply, snapshot.requestId());
        return true;
    }

    private boolean isAffirmative(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase();
        return normalized.equals("confirm") || normalized.equals("yes") || normalized.equals("确认")
                || normalized.equals("确认覆盖") || normalized.equals("继续");
    }

    private void interruptStalledRunningRuns() {
        OffsetDateTime now = OffsetDateTime.now();
        mangaAgentRunService.interruptStalledRunningRuns(
                now.minusSeconds(properties.getAgent().getModelIdleTimeoutSeconds()),
                now.minusSeconds(properties.getAgent().getToolIdleTimeoutSeconds())
        );
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
