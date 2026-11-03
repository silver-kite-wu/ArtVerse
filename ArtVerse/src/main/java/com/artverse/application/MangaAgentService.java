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

    @Qualifier("mangaGenerationExecutor")
    private final ExecutorService executor;
    private final MangaRoutingMetrics routingMetrics;

    public void runBackground(MangaAgentConversation conversation,String message,UUID requestId,UserProviderConfig config) {
        var sink=mangaAgentRunEventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=agentConcurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runStreamTask(()->executeStreamConversation(conversation,message,requestId,null,MangaRouteSource.AUTO,config,sink,ref),conversation,requestId,sink,ref); }
        finally { agentConcurrencyGate.release(permit); }
    }

    public void resumeBackground(MangaAgentConversation conversation,UUID requestId,String answer,UserProviderConfig config) {
        var sink=mangaAgentRunEventPublisher.newPersistentSink();
        var ref=new AtomicReference<MangaAgentRun>();
        var permit=agentConcurrencyGate.acquireOrReject(conversation.getUser().getId(),requestId);
        try { runStreamTask(()-> {
            var snapshot=requireWaitingSnapshot(conversation,requestId);
            if(handleMutationDecision(conversation,snapshot,answer,sink,ref)||handleContextMissingDecision(conversation,snapshot,answer,sink,ref))return;
            executeStreamConversation(conversation,resumeMessage(snapshot,answer),requestId,routeForResume(snapshot,answer),routeSourceForResume(snapshot,answer),config,sink,ref);
        },conversation,requestId,sink,ref); } finally { agentConcurrencyGate.release(permit); }
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
        } catch (AgentUserInputRequiredException e) {
            mangaAgentRunService.findRun(conversation, effectiveRequestId)
                    .ifPresent(run -> mangaAgentRunService.markWaiting(conversation, effectiveRequestId, e.request()));
            throw e;
        } catch (RuntimeException e) {
            mangaAgentRunService.findRun(conversation, effectiveRequestId).ifPresent(run -> {
                if (!mangaAgentRunService.isTerminal(conversation, effectiveRequestId)) {
                    mangaAgentRunService.markFailed(conversation, effectiveRequestId,
                            e.getMessage() == null ? "Agent request failed" : e.getMessage());
                    routingMetrics.recordRunOutcome(run.getRoute(), "FAILED");
                }
            });
            throw e;
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
                    conversation, message, requestId, route, ignored.state(), sink, runRef, llmConfig, routeSource);
        }
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeStreamInternal(conversation, requestId, answer, llmConfig);
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resumeAgUiStream(chapterId, conversationId, requestId, answer, user,
                mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public SseEmitter resumeAgUiStream(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                                       UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeStreamInternal(conversation, requestId, answer, llmConfig);
    }

    private SseEmitter resumeStreamInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                            UserProviderConfig llmConfig) {
        SseEmitter emitter = new SseEmitter(0L);
        MangaAgentRunEventPublisher.RunEventSink sink = mangaAgentRunEventPublisher.newSink(emitter);
        AtomicReference<MangaAgentRun> runRef = new AtomicReference<>();

        submitStreamTask(conversation.getUser().getId(), requestId, () -> runStreamTask(() -> {
            MangaAgentRunSnapshots.RunSnapshot snapshot = requireWaitingSnapshot(conversation, requestId);
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
            if (run != null) {
                mangaAgentRunService.markWaiting(conversation, requestId, e.request());
            }
            sink.sendUserInputRequested(run, requestId, e.request());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            MangaAgentRun run = runRef.get();
            if (run != null && !mangaAgentRunService.isTerminal(conversation, requestId)) {
                mangaAgentRunService.markFailed(conversation, requestId, detail);
                routingMetrics.recordRunOutcome(run.getRoute(), "FAILED");
            }
            sink.sendError(run, requestId, detail);
        } finally {
            sink.complete();
        }
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user) {
        return resume(chapterId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID requestId, String answer, User user, UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.activeOrCreate(chapterId, user);
        return resumeInternal(conversation, requestId, answer, llmConfig);
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user) {
        return resume(chapterId, conversationId, requestId, answer, user, mangaWorkflowOrchestrator.requireLlmConfig(user));
    }

    public RunResult resume(Long chapterId, UUID conversationId, UUID requestId, String answer, User user,
                            UserProviderConfig llmConfig) {
        MangaAgentConversation conversation = conversationService.requireConversation(chapterId, user, conversationId);
        return resumeInternal(conversation, requestId, answer, llmConfig);
    }

    private RunResult resumeInternal(MangaAgentConversation conversation, UUID requestId, String answer,
                                     UserProviderConfig llmConfig) {
        MangaAgentRunSnapshots.RunSnapshot snapshot = mangaAgentRunService.snapshot(
                mangaAgentRunService.findRun(conversation, requestId)
                        .orElseThrow(() -> new BusinessException(404, "Agent run not found"))
        );
        if (snapshot.status() != com.artverse.domain.MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(409, "Can only resume a paused run");
        }
        AgentUserInputRequest waiting = snapshot.userInputRequest();
        if (waiting == null) {
            throw new BusinessException(409, "No waiting user input request on the run");
        }
        if ("MUTATION_CONFIRMATION".equalsIgnoreCase(waiting.purpose())) {
            if (!isAffirmative(answer)) {
                String reply = "已取消覆盖操作，当前分镜保持不变。";
                conversationService.saveMessage(
                        conversation, com.artverse.domain.MessageRole.ASSISTANT, reply, requestId);
                mangaAgentRunService.markSucceeded(conversation, requestId, reply);
                return new RunResult(reply, requestId);
            }
            agentRunToolStatus.authorizeMutation(
                    conversation.getUser().getId(), conversation.getChapter().getId(), requestId);
        }
        if ("CONTEXT_MISSING".equalsIgnoreCase(waiting.purpose()) && isCancelSelection(waiting, answer)) {
            String reply = "已取消本次需要上下文的写入请求。补齐章节正文或分镜后，可以随时重新发起。";
            conversationService.saveMessage(
                    conversation, com.artverse.domain.MessageRole.ASSISTANT, reply, requestId);
            mangaAgentRunService.markSucceeded(conversation, requestId, reply);
            return new RunResult(reply, requestId);
        }
        String message = conversationService.resumeMessage(snapshot.inputMessage(), waiting, answer);
        try {
            RunResult result = runInternal(conversation, message, requestId, routeForResume(snapshot, answer),
                    routeSourceForResume(snapshot, answer), llmConfig);
            mangaAgentRunService.markSucceeded(conversation, requestId, result.reply());
            return result;
        } catch (AgentUserInputRequiredException e) {
            mangaAgentRunService.markWaiting(conversation, requestId, e.request());
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "Agent request failed" : e.getMessage();
            mangaAgentRunService.markFailed(conversation, requestId, detail);
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
        agentScopeGateway.interrupt(requestId);
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
        agentScopeGateway.interrupt(requestId);
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
            agentRunToolStatus.authorizeMutation(
                    conversation.getUser().getId(), conversation.getChapter().getId(), snapshot.requestId());
            return false;
        }
        MangaAgentRun run = mangaAgentRunService.findRun(conversation, snapshot.requestId())
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        runRef.set(run);
        String reply = "已取消覆盖操作，当前分镜保持不变。";
        conversationService.saveMessage(
                conversation, com.artverse.domain.MessageRole.ASSISTANT, reply, snapshot.requestId());
        mangaAgentRunService.markSucceeded(conversation, snapshot.requestId(), reply);
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
        conversationService.saveMessage(
                conversation, com.artverse.domain.MessageRole.ASSISTANT, reply, snapshot.requestId());
        mangaAgentRunService.markSucceeded(conversation, snapshot.requestId(), reply);
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
