package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunEvent;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.ApiKeyService;
import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunEventPublisher;
import com.artverse.application.MangaAgentRunService;
import com.artverse.application.MangaAgentRunSnapshots.RunContextSnapshot;
import com.artverse.application.UserProviderConfig;
import com.artverse.application.AgentUserInputRequiredException;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.User;
import com.artverse.guard.GenerationGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MangaWorkflowOrchestrator {

    private final MangaAgentConversationService mangaAgentConversationService;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ApiKeyService apiKeyService;
    private final GenerationGuardService generationGuardService;
    private final MangaAgentRunService mangaAgentRunService;
    private final MangaWorkflowContextAssembler contextAssembler;
    private final MangaWorkflowContextPolicy contextPolicy;
    private final MangaWorkflowNodeRegistry nodeRegistry;
    private final MangaWorkflowRouter workflowRouter;
    private final MangaRoutingMetrics routingMetrics;
    private final ResultFinalizer resultFinalizer;

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, null, toolState);
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState) {
        return runWithToolState(conversation, message, effectiveRequestId, route, toolState,
                requireLlmConfig(conversation.getUser()));
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState,
                                                UserProviderConfig llmConfig) {
        return runWithToolState(conversation, message, effectiveRequestId, route, toolState, llmConfig,
                route == null ? MangaRouteSource.AUTO : MangaRouteSource.RESUME_FIXED);
    }

    public Map<String, Object> runWithToolState(MangaAgentConversation conversation, String message,
                                                UUID effectiveRequestId, MangaWorkflowRoute route,
                                                AgentRunToolStatus.RunState toolState,
                                                UserProviderConfig llmConfig,
                                                MangaRouteSource requestedSource) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }
        var cached = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cached.isPresent()) {
            var persisted = mangaAgentRunService.reconcileCachedReply(conversation, effectiveRequestId, cached.get().getContent());
            if (persisted.isPresent() && persisted.get().getStatus() != MangaAgentRunStatus.SUCCEEDED
                    && persisted.get().getStatus() != MangaAgentRunStatus.DEGRADED)
                throw new BusinessException(409, "Cached reply does not prove the current execution completed");
            return Map.of("reply", cached.get().getContent());
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        AgentModelSpec modelSpec = agentModelSpecFactory.fromProviderConfig(llmConfig);
        MangaAgentRun run = mangaAgentRunService.startOrReuse(
                conversation, effectiveRequestId, message,
                route == null ? MangaWorkflowRoute.DIRECTOR : route);
        var originalClaim = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        try {
        mangaAgentRunService.bindAutomationExecutionClaim(conversation, effectiveRequestId, originalClaim, null);
        MangaAgentRun configuredRun = mangaAgentRunService.recordModelConfig(run, llmConfig.configId(), originalClaim);
        // Keep compatibility with older/test adapters that persisted metadata
        // but did not return the attached entity.
        if (configuredRun != null) run = configuredRun;
        run = mangaAgentRunService.markRouting(run, originalClaim);
        RoutingDecision decision = resolveRoute(conversation, message, effectiveRequestId, route, modelSpec, llmConfig.apiKey());
        run = mangaAgentRunService.updateRoutingDecision(run, decision, routeSource(decision, requestedSource), originalClaim);
        MangaWorkflowRoute effectiveRoute = decision.route();
        MangaAgentRun workflowRun = run;
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapter.getStory().getId(),
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowLeader(conversation, message, effectiveRequestId, llmConfig.apiKey(), modelSpec,
                        decision, toolState, workflowRun, originalClaim, null)
        );
        return completeSyncRun(conversation, effectiveRequestId, effectiveRoute, result, originalClaim);
        } catch (AgentUserInputRequiredException waiting) {
            mangaAgentRunService.markWaiting(conversation, effectiveRequestId, waiting.request(), originalClaim);
            throw waiting;
        } catch (RuntimeException failure) {
            recordOwnedFailure(conversation, effectiveRequestId, originalClaim, failure);
            throw failure;
        }
    }

    private Map<String, Object> runWorkflowLeader(MangaAgentConversation conversation, String message,
                                                  UUID effectiveRequestId, String llmApiKey,
                                                  AgentModelSpec modelSpec, RoutingDecision decision,
                                                  AgentRunToolStatus.RunState toolState,
                                                  MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim originalClaim,
                                                  MangaWorkflowExecutionContext.AnswerResume answerResume) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message, decision);
        persistContextSnapshot(run, workflowContext, originalClaim);
        List<String> missingRequired = contextPolicy.missingRequiredFields(workflowContext);
        if (answerResume == null && !missingRequired.isEmpty()) {
            if (contextPolicy.blocksWrite(decision)) {
                throw contextPolicy.missingContextHitl(workflowContext, decision);
            }
            return saveReadOnlyExplanation(conversation, message, effectiveRequestId,
                    contextPolicy.readOnlyExplanation(workflowContext, decision));
        }
        log.info("Workflow route for request {} -> {}", effectiveRequestId, decision.route());
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, llmApiKey, modelSpec, toolState, workflowContext, originalClaim, answerResume);
        return nodeRegistry.handlerFor(decision.route()).run(context).toPayload();
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef) {
        runStreamLeader(conversation, message, effectiveRequestId, route, toolState, sink, runRef,
                requireLlmConfig(conversation.getUser()));
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef, UserProviderConfig llmConfig) {
        runStreamLeader(conversation, message, effectiveRequestId, route, toolState, sink, runRef, llmConfig,
                route == null ? MangaRouteSource.AUTO : MangaRouteSource.RESUME_FIXED);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route,
                                AgentRunToolStatus.RunState toolState, MangaAgentRunEventPublisher.RunEventSink sink,
                                AtomicReference<MangaAgentRun> runRef, UserProviderConfig llmConfig,
                                MangaRouteSource requestedSource) {
        runStreamLeader(conversation, message, effectiveRequestId, route, toolState, sink, runRef, llmConfig, requestedSource, null);
    }

    public void runStreamLeader(MangaAgentConversation conversation, String message, UUID effectiveRequestId,
                                MangaWorkflowRoute route, AgentRunToolStatus.RunState toolState,
                                MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> runRef,
                                UserProviderConfig llmConfig, MangaRouteSource requestedSource,
                                com.artverse.agent.AutomationExecutionClaim parentClaim) {
        if (message == null || message.isBlank()) {
            throw new com.artverse.common.BusinessException(400, "Message cannot be empty");
        }

        User user = conversation.getUser();
        Chapter chapter = conversation.getChapter();
        Long chapterId = chapter.getId();

        var cachedReply = mangaAgentConversationService.findAssistantReply(conversation, effectiveRequestId);
        if (cachedReply.isPresent()) {
            replayCachedReply(conversation, effectiveRequestId, cachedReply.get().getContent(), sink, runRef);
            return;
        }

        MangaAgentRun run = mangaAgentRunService.startOrReuse(
                conversation, effectiveRequestId, message,
                route == null ? MangaWorkflowRoute.DIRECTOR : route);
        var originalClaim = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        try {
        mangaAgentRunService.bindAutomationExecutionClaim(conversation, effectiveRequestId, originalClaim, parentClaim);
        run = mangaAgentRunService.markRouting(run, originalClaim);
        runRef.set(run);
        sink.sendStatus(run, "Agent started processing the current chapter", effectiveRequestId);

        AgentModelSpec modelSpec = agentModelSpecFactory.fromProviderConfig(llmConfig);
        sink.sendRunEvent(run, AgentRunEvent.step("ROUTER", "running", "正在识别任务并选择智能体", Map.of()));
        RoutingDecision decision = resolveRoute(conversation, message, effectiveRequestId, route, modelSpec, llmConfig.apiKey());
        MangaWorkflowRoute effectiveRoute = decision.route();
        run = mangaAgentRunService.updateRoutingDecision(run, decision, routeSource(decision, requestedSource), originalClaim);
        runRef.set(run);
        MangaAgentRun routedRun = run;
        sink.sendRunEvent(run, AgentRunEvent.step("ROUTER", "completed", "已选择" + effectiveRoute.name() + "智能体",
                Map.of("route", effectiveRoute.name(), "confidence", decision.confidence(),
                        "reasonCode", decision.reasonCode(), "routerVersion", decision.routerVersion())));
        Map<String, Object> result = generationGuardService.executeMangaAgentRun(
                user.getId(),
                chapterId,
                effectiveRequestId.toString(),
                message,
                modelSpec.provider(),
                modelSpec.model(),
                AgentModelSpecFactory.shortHash(modelSpec.baseUrl()),
                () -> runWorkflowStream(conversation, message, effectiveRequestId, sink, decision, toolState,
                        llmConfig.apiKey(), modelSpec, routedRun, originalClaim, null)
        );

        completeRun(routedRun, sink, chapterId, user, effectiveRequestId, result, originalClaim);
        } catch (AgentUserInputRequiredException waiting) {
            mangaAgentRunService.markWaiting(conversation, effectiveRequestId, waiting.request(), originalClaim);
            throw waiting;
        } catch (RuntimeException failure) {
            recordOwnedFailure(conversation, effectiveRequestId, originalClaim, failure);
            throw failure;
        }
    }

    private void replayCachedReply(MangaAgentConversation conversation, UUID requestId, String reply,
                                   MangaAgentRunEventPublisher.RunEventSink sink,
                                   AtomicReference<MangaAgentRun> runRef) {
        var persistedRun = mangaAgentRunService.reconcileCachedReply(conversation, requestId, reply);
        if (persistedRun.isEmpty()) {
            sink.sendDone(null, reply, requestId);
            return;
        }

        MangaAgentRun run = persistedRun.get();
        runRef.set(run);
        if (run.getStatus() == MangaAgentRunStatus.SUCCEEDED
                || run.getStatus() == MangaAgentRunStatus.DEGRADED) {
            sink.sendDone(run, reply, requestId);
        } else if (run.getStatus() == MangaAgentRunStatus.FAILED) {
            sink.sendError(run, requestId, run.getErrorMessage());
        } else {
            // A cancellation/interruption remains authoritative even if the reply was saved just before it.
            sink.complete();
        }
    }

    private Map<String, Object> runWorkflowStream(MangaAgentConversation conversation, String message,
                                                 UUID effectiveRequestId,
                                                 MangaAgentRunEventPublisher.RunEventSink sink,
                                                 RoutingDecision decision,
                                                 AgentRunToolStatus.RunState toolState,
                                                 String llmApiKey, AgentModelSpec modelSpec,
                                                 MangaAgentRun run, MangaWorkflowExecutionContext.ExecutionClaim originalClaim,
                                                 MangaWorkflowExecutionContext.AnswerResume answerResume) {
        MangaWorkflowContextSnapshot workflowContext = contextAssembler.assemble(conversation, message, decision);
        persistContextSnapshot(run, workflowContext, originalClaim);
        MangaWorkflowExecutionContext context = executionContext(
                conversation, message, effectiveRequestId, llmApiKey, modelSpec, toolState, workflowContext, originalClaim, answerResume);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.COLLECTING_CONTEXT.name(),
                "running",
                "Collecting chapter context",
                contextAssembler.summary(workflowContext)
        ));

        List<String> missingRequired = contextPolicy.missingRequiredFields(workflowContext);
        if (answerResume == null && !missingRequired.isEmpty()) {
            if (contextPolicy.blocksWrite(decision)) {
                throw contextPolicy.missingContextHitl(workflowContext, decision);
            }
            return saveReadOnlyExplanation(conversation, message, effectiveRequestId,
                    contextPolicy.readOnlyExplanation(workflowContext, decision));
        }

        MangaWorkflowStreamContext streamCtx = new MangaWorkflowStreamContext(run, sink);
        MangaWorkflowResult response = nodeRegistry.handlerFor(decision.route())
                .stream(context, streamCtx);

        sink.sendRunEvent(run, AgentRunEvent.step(
                MangaWorkflowNode.EVALUATING.name(),
                "running",
                "Evaluating generated result",
                Map.of("degraded", response.degraded())
        ));
        return response.toPayload();
    }

    public void completeRun(MangaAgentRun run, MangaAgentRunEventPublisher.RunEventSink sink, Long chapterId, User user,
                            UUID requestId, Map<String, Object> result, MangaWorkflowExecutionContext.ExecutionClaim claim) {
        if (mangaAgentRunService.isTerminal(requestId, user.getId(), chapterId)) {
            sink.complete();
            return;
        }
        MangaWorkflowResult finalized = resultFinalizer.finalizeResult(
                run.getConversation(), requestId, run.getRoute(), MangaWorkflowResult.fromPayload(result), claim);
        routingMetrics.recordRunOutcome(run.getRoute(), finalized.degraded() ? "DEGRADED" : "SUCCEEDED");
        sink.sendDone(run, finalized.reply(), requestId);
    }

    public UserProviderConfig requireLlmConfig(User user) {
        return apiKeyService.requireActiveUserProviderConfig(
                user,
                ApiKeyService.SLOT_LLM,
                "Please configure an LLM provider API key in Settings before using the manga agent."
        );
    }

    public void completeWaitingDecision(MangaAgentConversation conversation, UUID requestId,
                                         com.artverse.application.AgentUserInputRequest expected, String reply) {
        resultFinalizer.finalizeWaitingDecision(conversation, requestId, expected, reply);
    }

    private MangaWorkflowExecutionContext executionContext(MangaAgentConversation conversation, String message,
                                                           UUID effectiveRequestId, String llmApiKey,
                                                           AgentModelSpec modelSpec,
                                                           AgentRunToolStatus.RunState toolState,
                                                           MangaWorkflowContextSnapshot workflowContext,
                                                           MangaWorkflowExecutionContext.ExecutionClaim originalClaim,
                                                           MangaWorkflowExecutionContext.AnswerResume answerResume) {
        return new MangaWorkflowExecutionContext(
                conversation,
                message,
                effectiveRequestId,
                llmApiKey,
                modelSpec,
                toolState,
                conversation.getUser(),
                conversation.getChapter(),
                workflowContext,
                answerResume == null,
                answerResume == null ? null : answerResume.stepId(),
                originalClaim,
                answerResume
        );
    }

    private RoutingDecision resolveRoute(MangaAgentConversation conversation, String message, UUID requestId,
                                         MangaWorkflowRoute requestedRoute, AgentModelSpec modelSpec, String llmApiKey) {
        if (requestedRoute != null) {
            return RoutingDecision.fixed(requestedRoute, "persisted_route");
        }
        return workflowRouter.route(conversation, message, requestId, modelSpec, llmApiKey);
    }

    /** Resume one persisted external call through the original workflow; never reclassify its answer. */
    public Map<String, Object> resumeAnswerWithToolState(MangaAgentConversation conversation, UUID requestId,
            MangaAgentRunService.AcceptedAnswer accepted, MangaAgentRunService.AnswerExecution execution,
            com.artverse.agent.gateway.AgentSessionTurns.Lease turn, AgentRunToolStatus.RunState toolState,
            UserProviderConfig config, MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> runRef) {
        return resumeAnswerWithToolState(conversation, requestId, accepted, execution, turn, toolState, config, sink, runRef, null);
    }

    public Map<String, Object> resumeAnswerWithToolState(MangaAgentConversation conversation, UUID requestId,
            MangaAgentRunService.AcceptedAnswer accepted, MangaAgentRunService.AnswerExecution execution,
            com.artverse.agent.gateway.AgentSessionTurns.Lease turn, AgentRunToolStatus.RunState toolState,
            UserProviderConfig config, MangaAgentRunEventPublisher.RunEventSink sink, AtomicReference<MangaAgentRun> runRef,
            com.artverse.agent.AutomationExecutionClaim parentClaim) {
        var continuation = mangaAgentRunService.claimAnswerDelivery(conversation, requestId, accepted);
        MangaAgentRun run = continuation.run();
        if (runRef != null) runRef.set(run);
        if (!continuation.claimed()) {
            String reply = run.getFinalReply() == null ? "这份答复已受理，请查看当前任务状态。" : run.getFinalReply();
            if (sink != null) sink.sendStatus(run, reply, requestId);
            return Map.of("reply", reply);
        }
        var claim = MangaWorkflowExecutionContext.ExecutionClaim.from(run);
        try {
            mangaAgentRunService.bindAutomationExecutionClaim(conversation, requestId, claim, parentClaim);
            if (run.getRunType() != com.artverse.domain.AgentRunType.MANGA_AGENT)
                throw new BusinessException(409, "原提问不属于漫画工作流");
            RoutingDecision decision = mangaAgentRunService.routingDecision(run);
            if (decision == null || decision.route() != run.getRoute())
                throw new BusinessException(409, "原提问路由缺失或已变化，已有产物保留");
            var model = agentModelSpecFactory.fromProviderConfig(config);
            mangaAgentRunService.recordModelConfig(run, config.configId(), claim);
            var resume = new MangaWorkflowExecutionContext.AnswerResume(continuation.delivery(), turn, execution.taskType(), execution.stepId());
            String original = run.getInputMessage();
            if (sink == null) {
                var result = runWorkflowLeader(conversation, original, requestId, config.apiKey(), model, decision, toolState, run, claim, resume);
                return completeSyncRun(conversation, requestId, decision.route(), result, claim);
            }
            var result = runWorkflowStream(conversation, original, requestId, sink, decision, toolState,
                    config.apiKey(), model, run, claim, resume);
            completeRun(run, sink, conversation.getChapter().getId(), conversation.getUser(), requestId, result, claim);
            return result;
        } catch (AgentUserInputRequiredException waiting) {
            mangaAgentRunService.markWaiting(conversation, requestId, waiting.request(), claim);
            throw waiting;
        } catch (RuntimeException failure) {
            recordOwnedFailure(conversation, requestId, claim, failure);
            throw failure;
        }
    }

    private MangaRouteSource routeSource(RoutingDecision decision, MangaRouteSource requestedSource) {
        String reason = decision.reasonCode() == null ? "" : decision.reasonCode();
        if (reason.startsWith("shadow:")) {
            return MangaRouteSource.SHADOW;
        }
        if (reason.contains("fallback") || reason.startsWith("invalid_plan:")
                || reason.startsWith("invalid_contract:")
                || reason.equals("invalid_router_output")
                || reason.startsWith("unsupported_capability:")
                || "capability_route_mismatch".equals(reason)) {
            return MangaRouteSource.FALLBACK;
        }
        return requestedSource == null ? MangaRouteSource.AUTO : requestedSource;
    }

    private Map<String, Object> completeSyncRun(MangaAgentConversation conversation, UUID requestId,
                                                MangaWorkflowRoute route, Map<String, Object> result,
                                                MangaWorkflowExecutionContext.ExecutionClaim claim) {
        MangaWorkflowResult finalized = resultFinalizer.finalizeResult(
                conversation, requestId, route, MangaWorkflowResult.fromPayload(result), claim);
        routingMetrics.recordRunOutcome(route, finalized.degraded() ? "DEGRADED" : "SUCCEEDED");
        return finalized.toPayload();
    }

    private void recordOwnedFailure(MangaAgentConversation conversation, UUID requestId,
                                    MangaWorkflowExecutionContext.ExecutionClaim claim, RuntimeException failure) {
        try {
            resultFinalizer.finalizeFailure(conversation, requestId,
                    failure.getMessage() == null ? "Agent request failed" : failure.getMessage(), claim);
        } catch (BusinessException stale) {
            // The original callback may no longer own this continuation; preserve its real failure only.
            failure.addSuppressed(stale);
        }
    }

    private void persistRunAttributes(MangaAgentConversation conversation, UUID requestId, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(result);
        attributes.remove("reply");
        attributes.remove("agent_final_response_degraded");
        if (!attributes.isEmpty()) {
            mangaAgentRunService.mergeRunAttributes(conversation, requestId, attributes);
        }
    }

    private void persistContextSnapshot(MangaAgentRun run, MangaWorkflowContextSnapshot workflowContext,
                                        MangaWorkflowExecutionContext.ExecutionClaim claim) {
        mangaAgentRunService.recordContextSnapshot(run, new RunContextSnapshot(
                workflowContext.storyId(),
                workflowContext.chapterId(),
                workflowContext.storyTitle(),
                workflowContext.chapterDisplayName(),
                workflowContext.sceneCount(),
                workflowContext.imageCount(),
                workflowContext.contextHash(),
                null,
                workflowContext.requiredFields(),
                workflowContext.warnings()
        ), claim);
    }

    private Map<String, Object> saveReadOnlyExplanation(MangaAgentConversation conversation, String message,
                                                        UUID requestId, String reply) {
        mangaAgentConversationService.saveMessage(conversation,
                com.artverse.domain.MessageRole.USER, message, requestId);
        return Map.of("reply", reply, "agent_final_response_degraded", false);
    }
}
