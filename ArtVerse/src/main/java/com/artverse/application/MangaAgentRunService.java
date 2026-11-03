package com.artverse.application;

import com.artverse.agent.AgentRunEvent;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.common.BusinessException;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
import com.artverse.domain.MangaAgentRunStep;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.User;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaRouteSource;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.application.workflow.ExecutionPlan;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.MangaAgentRunStepRepository;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.artverse.application.MangaAgentRunSnapshots.*;

@Slf4j
@Service
public class MangaAgentRunService {

    private static final List<MangaAgentRunStatus> OPEN_STATUSES = List.of(
            MangaAgentRunStatus.RUNNING,
            MangaAgentRunStatus.WAITING_USER
    );

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final MangaAgentRunStepRepository stepRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final AgentRunLeaseService leaseService;
    private final MangaAgentRunSnapshotReader snapshotReader;

    @Autowired
    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository,
                                AgentRunLeaseService leaseService,
                                MangaAgentRunSnapshotReader snapshotReader) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.stepRepository = stepRepository;
        this.artifactRepository = artifactRepository;
        this.leaseService = leaseService;
        this.snapshotReader = snapshotReader;
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository,
                                AgentRunLeaseService leaseService) {
        this(runRepository, eventRepository, objectMapper, stepRepository, artifactRepository, leaseService,
                new MangaAgentRunSnapshotReader(runRepository, eventRepository, stepRepository, artifactRepository, objectMapper));
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository,
                                MangaAgentRunArtifactRepository artifactRepository) {
        this(runRepository, eventRepository, objectMapper, stepRepository, artifactRepository, null);
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper,
                                MangaAgentRunStepRepository stepRepository) {
        this(runRepository, eventRepository, objectMapper, stepRepository, null, null);
    }

    public MangaAgentRunService(MangaAgentRunRepository runRepository,
                                MangaAgentRunEventRepository eventRepository,
                                ObjectMapper objectMapper) {
        this(runRepository, eventRepository, objectMapper, null, null, null);
    }

    @Transactional
    public MangaAgentRun startOrReuse(User user, Chapter chapter, UUID requestId, String inputMessage) {
        MangaAgentRun claimed = runRepository.findByUserIdAndChapterIdAndRequestId(user.getId(), chapter.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
                        existing.setCurrentPhase("MODEL");
                        existing.setLastProgressAt(OffsetDateTime.now());
                        existing.setUpdatedAt(OffsetDateTime.now());
                    }
                    return runRepository.save(existing);
                })
                .orElseGet(() -> {
                    MangaAgentRun run = new MangaAgentRun();
                    run.setUser(user);
                    run.setStory(chapter.getStory());
                    run.setChapter(chapter);
                    run.setRequestId(requestId);
                    run.setInputMessage(inputMessage);
                    run.setStatus(MangaAgentRunStatus.RUNNING);
                    run.setCurrentPhase("MODEL");
                    return runRepository.save(run);
                });
        return claimIfConfigured(claimed);
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage) {
        return startOrReuse(conversation, requestId, inputMessage, MangaWorkflowRoute.DIRECTOR);
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage,
                                      MangaWorkflowRoute route) {
        return startOrReuse(conversation, requestId, inputMessage, AgentRunType.MANGA_AGENT, route,
                route == null ? MangaWorkflowRoute.DIRECTOR.name() : route.name());
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage,
                                      AgentRunType runType, MangaWorkflowRoute route, String routeKey) {
        MangaWorkflowRoute effectiveRoute = route == null ? MangaWorkflowRoute.DIRECTOR : route;
        AgentRunType effectiveRunType = runType == null ? AgentRunType.MANGA_AGENT : runType;
        String effectiveRouteKey = routeKey == null || routeKey.isBlank()
                ? effectiveRoute.name()
                : routeKey.trim().toUpperCase();
        MangaAgentRun claimed = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        existing.setRoute(effectiveRoute);
                        existing.setRunType(effectiveRunType);
                        existing.setRouteKey(effectiveRouteKey);
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
                        existing.setCurrentPhase("MODEL");
                        existing.setLastProgressAt(OffsetDateTime.now());
                        existing.setUpdatedAt(OffsetDateTime.now());
                    }
                    return runRepository.save(existing);
                })
                .orElseGet(() -> {
                    MangaAgentRun run = new MangaAgentRun();
                    run.setUser(conversation.getUser());
                    run.setStory(conversation.getStory());
                    run.setChapter(conversation.getChapter());
                    run.setConversation(conversation);
                    run.setRequestId(requestId);
                    run.setInputMessage(inputMessage);
                    run.setRunType(effectiveRunType);
                    run.setRoute(effectiveRoute);
                    run.setRouteKey(effectiveRouteKey);
                    run.setStatus(MangaAgentRunStatus.RUNNING);
                    run.setCurrentPhase("MODEL");
                    return runRepository.save(run);
                });
        return claimIfConfigured(claimed);
    }

    private MangaAgentRun claimIfConfigured(MangaAgentRun run) {
        return leaseService == null ? run : leaseService.claim(run);
    }

    @Transactional
    public MangaAgentRun updateRoutingDecision(MangaAgentRun run, RoutingDecision decision, MangaRouteSource source) {
        MangaAgentRun attached = runRepository.getReferenceById(run.getId());
        attached.setRoute(decision.route());
        attached.setRouteKey(decision.route().name());
        attached.setRouteSource(source == null ? MangaRouteSource.AUTO : source);
        attached.setRouteConfidence(decision.confidence());
        attached.setRouterVersion(decision.routerVersion());
        attached.setRoutingDecisionJson(toJson(decision));
        attached.setCurrentPhase("AGENT");
        attached.setLastProgressAt(OffsetDateTime.now());
        return runRepository.save(attached);
    }

    @Transactional
    public MangaAgentRun markRouting(MangaAgentRun run) {
        MangaAgentRun attached = runRepository.getReferenceById(run.getId());
        attached.setCurrentPhase("ROUTER");
        attached.setLastProgressAt(OffsetDateTime.now());
        return runRepository.save(attached);
    }

    @Transactional
    public MangaAgentRun recordModelConfig(MangaAgentRun run, Long modelConfigId) {
        MangaAgentRun attached = runRepository.getReferenceById(run.getId());
        if (attached.getModelConfigId() == null) {
            attached.setModelConfigId(modelConfigId);
        } else if (modelConfigId != null && !modelConfigId.equals(attached.getModelConfigId())) {
            throw new BusinessException(409, "Run model configuration is already fixed");
        }
        return runRepository.save(attached);
    }

    @Transactional
    public void recordSkillSelection(Long userId, Long chapterId, UUID requestId,
                                     BusinessSkillSelection selection, String promptVersion) {
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> {
                    Map<String, Object> versions = new LinkedHashMap<>(readMap(run.getSkillVersionsJson()));
                    versions.putAll(selection.skillVersions());
                    run.setSkillVersionsJson(toJson(versions));
                    run.setPromptVersion(promptVersion);
                    runRepository.save(run);
                    if (!selection.isEmpty()) {
                        appendEvent(run, "skill_selected", Map.of(
                                "skillKeys", selection.skillKeys(),
                                "versions", selection.skillVersions(),
                                "primarySkillKey", selection.primarySkillKey() == null ? "" : selection.primarySkillKey(),
                                "promptVersion", promptVersion == null ? "" : promptVersion));
                    }
                });
    }

    @Transactional
    public void recordStepSkillSelection(Long userId, Long chapterId, UUID requestId, String stepId,
                                         BusinessSkillSelection selection) {
        if (stepRepository == null || stepId == null || stepId.isBlank()) return;
        var primarySkill = selection.primarySkill();
        if (primarySkill == null) return;
        int separator = stepId.lastIndexOf(':');
        if (separator <= 0 || separator == stepId.length() - 1) return;
        int sequence;
        try {
            sequence = Integer.parseInt(stepId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return;
        }
        String planId = stepId.substring(0, separator);
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .flatMap(run -> stepRepository.findByRunIdAndPlanIdAndStepSequence(
                        run.getId(), planId, sequence))
                .ifPresent(step -> {
                    step.setSkillKey(primarySkill.skillKey());
                    step.setSkillVersion(primarySkill.semanticVersion());
                    stepRepository.save(step);
                });
    }

    @Transactional
    public void recordKnowledgeSnapshot(Long userId, Long chapterId, UUID requestId,
                                        KnowledgeService.RecallPreview preview) {
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> {
                    run.setKnowledgeSnapshotId(preview.snapshotId());
                    RunContextSnapshot contextSnapshot = withKnowledgeRecallHash(
                            readContextSnapshot(run.getContextSnapshotJson()),
                            preview.contextHash());
                    run.setContextSnapshotJson(toJson(contextSnapshot));
                    runRepository.save(run);
                    appendEvent(run, "rag_retrieved", Map.of(
                            "snapshotId", preview.snapshotId() == null ? "" : preview.snapshotId(),
                            "embeddingSpaceId", preview.embeddingSpaceId(),
                            "contextHash", preview.contextHash(),
                            "items", preview.items().stream().map(item -> Map.of(
                                    "knowledgeUnitId", item.knowledgeUnitId(),
                                    "version", item.version(),
                                    "score", item.score())).toList()));
                });
    }

    @Transactional
    public void recordContextSnapshot(MangaAgentRun run, RunContextSnapshot snapshot) {
        MangaAgentRun attached = runRepository.getReferenceById(run.getId());
        attached.setContextSnapshotJson(toJson(snapshot));
        attached.setLastProgressAt(OffsetDateTime.now());
        runRepository.save(attached);
    }

    @Transactional
    public void updateExecutionPlan(Long userId, Long chapterId, UUID requestId, String executionPlanJson) {
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> {
                    run.setExecutionPlanJson(executionPlanJson);
                    run.setCurrentPhase("AGENT");
                    run.setLastProgressAt(OffsetDateTime.now());
                    runRepository.save(run);
                    persistExecutionSteps(run, executionPlanJson);
                });
    }

    private void persistExecutionSteps(MangaAgentRun run, String executionPlanJson) {
        if (stepRepository == null || executionPlanJson == null || executionPlanJson.isBlank()) {
            return;
        }
        try {
            ExecutionPlan plan = objectMapper.readValue(executionPlanJson, ExecutionPlan.class);
            for (com.artverse.application.workflow.ExecutionStep step : plan.steps()) {
                MangaAgentRunStep record = stepRepository
                        .findByRunIdAndPlanIdAndStepSequence(run.getId(), plan.planId(), step.sequence())
                        .orElseGet(MangaAgentRunStep::new);
                record.setRun(run);
                record.setPlanId(plan.planId());
                record.setStepSequence(step.sequence());
                record.setRoute(step.route());
                record.setStatus(step.status());
                record.setMutating(step.mutating());
                record.setInputSummary(step.inputSummary());
                record.setOutputSummary(step.outputSummary());
                record.setStartedAt(step.startedAt());
                record.setCompletedAt(step.completedAt());
                stepRepository.save(record);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Failed to persist execution plan steps", error);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findRun(Long userId, Long chapterId, UUID requestId) {
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId);
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findRun(MangaAgentConversation conversation, UUID requestId) {
        return runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId);
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findLatestOpenRun(Long userId, Long chapterId) {
        return runRepository.findByUserIdAndChapterIdAndStatusInOrderByUpdatedAtDesc(
                        userId,
                        chapterId,
                        OPEN_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentRun> findLatestOpenRun(MangaAgentConversation conversation) {
        return runRepository.findByConversationIdAndStatusInOrderByUpdatedAtDesc(
                        conversation.getId(),
                        OPEN_STATUSES,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public MangaAgentRun requireWaitingRun(Long userId, Long chapterId, UUID requestId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "No waiting agent run found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(404, "No waiting agent run found");
        }
        return run;
    }

    @Transactional(readOnly = true)
    public MangaAgentRun requireWaitingRun(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "No waiting agent run found"));
        if (run.getStatus() != MangaAgentRunStatus.WAITING_USER) {
            throw new BusinessException(404, "No waiting agent run found");
        }
        return run;
    }

    @Transactional(readOnly = true)
    public AgentUserInputRequest waitingInput(MangaAgentRun run) {
        return snapshotReader.waitingInput(run);
    }

    @Transactional(readOnly = true)
    public RoutingDecision routingDecision(MangaAgentRun run) {
        return snapshotReader.routingDecision(run);
    }

    @Transactional(readOnly = true)
    public ExecutionPlan executionPlan(MangaAgentRun run) {
        return snapshotReader.executionPlan(run);
    }

    @Transactional
    public void markWaiting(UUID requestId, Long userId, Long chapterId, AgentUserInputRequest request) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setUserInputRequestJson(toJson(request));
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markWaiting(MangaAgentConversation conversation, UUID requestId, AgentUserInputRequest request) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.WAITING_USER);
        run.setUserInputRequestJson(toJson(request));
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunning(UUID requestId, Long userId, Long chapterId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setUserInputRequestJson(null);
        run.setErrorMessage(null);
        run.setCurrentPhase("MODEL");
        run.setLastProgressAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markRunning(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        run.setStatus(MangaAgentRunStatus.RUNNING);
        run.setUserInputRequestJson(null);
        run.setErrorMessage(null);
        run.setCurrentPhase("MODEL");
        run.setLastProgressAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    @Transactional
    public void markSucceeded(UUID requestId, Long userId, Long chapterId, String reply) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    @Transactional
    public void markSucceeded(MangaAgentConversation conversation, UUID requestId, String reply) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.SUCCEEDED, reply, null);
    }

    /**
     * Acquires the terminal-transition row lock before an assistant reply is
     * persisted. A cancellation that acquired the lock first remains
     * authoritative and prevents a late success reply from being stored.
     */
    @Transactional
    public MangaAgentRun requireFinalizable(MangaAgentConversation conversation, UUID requestId) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            throw new BusinessException(409, "Agent run is already terminal: " + run.getStatus());
        }
        return run;
    }

    /**
     * Persists verified business facts and terminal status in the transaction
     * opened by ResultFinalizer together with the assistant message.
     */
    @Transactional
    public MangaAgentRun completeVerified(MangaAgentConversation conversation, UUID requestId,
                                          String reply, boolean degraded, String resultSchema,
                                          Map<String, Object> verifiedFacts) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            throw new BusinessException(409, "Agent run is already terminal: " + run.getStatus());
        }
        OffsetDateTime now = OffsetDateTime.now();
        run.setResultSchema(resultSchema);
        run.setVerifiedResultJson(toJson(verifiedFacts == null ? Map.of() : verifiedFacts));
        run.setVerifiedAt(now);
        MangaAgentRunStatus status = degraded ? MangaAgentRunStatus.DEGRADED : MangaAgentRunStatus.SUCCEEDED;
        MangaAgentRun completed = applyTerminal(run, status, reply,
                degraded ? "Agent final response degraded after a verified mutation" : null);
        appendEvent(completed, "result_verified", Map.of(
                "type", "result_verified",
                "phase", "result",
                "status", status.name(),
                "resultSchema", resultSchema == null ? "" : resultSchema,
                "facts", verifiedFacts == null ? Map.of() : verifiedFacts
        ));
        return completed;
    }

    /**
     * Closes the short window where the assistant reply is stored before the run becomes terminal.
     * The row lock makes a concurrent cancellation authoritative when it wins the transition first.
     */
    @Transactional
    public Optional<MangaAgentRun> reconcileCachedReply(MangaAgentConversation conversation, UUID requestId,
                                                        String reply) {
        return runRepository.findForUpdate(conversation.getId(), requestId)
                .map(run -> isTerminal(run.getStatus())
                        ? run
                        : applyTerminal(run, MangaAgentRunStatus.SUCCEEDED, reply, null));
    }

    @Transactional
    public void markDegraded(UUID requestId, Long userId, Long chapterId, String reply, String error) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.DEGRADED, reply, error);
    }

    @Transactional
    public void markDegraded(MangaAgentConversation conversation, UUID requestId, String reply, String error) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.DEGRADED, reply, error);
    }

    @Transactional
    public void markFailed(UUID requestId, Long userId, Long chapterId, String error) {
        markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.FAILED, null, error);
    }

    @Transactional
    public void markFailed(MangaAgentConversation conversation, UUID requestId, String error) {
        markTerminal(conversation, requestId, MangaAgentRunStatus.FAILED, null, error);
    }

    @Transactional
    public MangaAgentRun cancel(UUID requestId, Long userId, Long chapterId, String reason) {
        return markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.CANCELLED, null,
                reason == null || reason.isBlank() ? "Agent run cancelled by user" : reason);
    }

    @Transactional
    public MangaAgentRun cancel(MangaAgentConversation conversation, UUID requestId, String reason) {
        return markTerminal(conversation, requestId, MangaAgentRunStatus.CANCELLED, null,
                reason == null || reason.isBlank() ? "Agent run cancelled by user" : reason);
    }

    @Transactional
    public MangaAgentRun markInterrupted(UUID requestId, Long userId, Long chapterId, String reason) {
        return markTerminal(requestId, userId, chapterId, MangaAgentRunStatus.INTERRUPTED, null,
                reason == null || reason.isBlank() ? "Agent run interrupted" : reason);
    }

    @Transactional
    public int interruptStalledRunningRuns(OffsetDateTime modelStaleBefore, OffsetDateTime toolStaleBefore) {
        OffsetDateTime candidateBefore = modelStaleBefore.isAfter(toolStaleBefore)
                ? modelStaleBefore
                : toolStaleBefore;
        List<MangaAgentRun> staleRuns = runRepository.findByStatusAndLastProgressAtBefore(
                MangaAgentRunStatus.RUNNING,
                candidateBefore
        ).stream().filter(run -> "TOOL".equals(run.getCurrentPhase())
                ? run.getLastProgressAt().isBefore(toolStaleBefore)
                : run.getLastProgressAt().isBefore(modelStaleBefore))
                .toList();
        OffsetDateTime now = OffsetDateTime.now();
        for (MangaAgentRun run : staleRuns) {
            run.setStatus(MangaAgentRunStatus.INTERRUPTED);
            run.setErrorMessage("Agent run interrupted because no real progress was recorded in "
                    + run.getCurrentPhase() + " phase");
            run.setUserInputRequestJson(null);
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            runRepository.save(run);
        }
        return staleRuns.size();
    }

    @Transactional
    public void recordProgress(MangaAgentRun run, String phase) {
        MangaAgentRun attachedRun = runRepository.getReferenceById(run.getId());
        attachedRun.setCurrentPhase(normalizePhase(phase));
        attachedRun.setLastProgressAt(OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean isTerminal(UUID requestId, Long userId, Long chapterId) {
        return runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .map(run -> isTerminal(run.getStatus()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isTerminal(MangaAgentConversation conversation, UUID requestId) {
        return runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .map(run -> isTerminal(run.getStatus()))
                .orElse(false);
    }

    @Transactional
    public Long appendEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        MangaAgentRun attachedRun = runRepository.getReferenceById(run.getId());
        if (attachedRun == null) {
            attachedRun = run;
        }
        MangaAgentRunEventRecord event = new MangaAgentRunEventRecord();
        event.setRun(attachedRun);
        event.setEventName(eventName);
        event.setEventType(asString(payload.get("type")));
        event.setPhase(asString(payload.get("phase")));
        event.setLabel(asString(payload.get("label")));
        event.setStatus(asString(payload.get("status")));
        event.setPayloadJson(toJson(payload));
        eventRepository.save(event);
        attachedRun.setUpdatedAt(OffsetDateTime.now());
        return event.getId();
    }

    @Transactional
    public void appendRunEvent(MangaAgentRun run, AgentRunEvent event) {
        appendEvent(run, "run_event", toPayload(event));
    }

    @Transactional
    public void appendToolAuditEvent(Long userId, Long chapterId, UUID requestId, AgentRunToolStatus.ToolEvent event) {
        if (event == null || requestId == null) {
            return;
        }
        runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .ifPresent(run -> appendEvent(run, "tool", toolAuditPayload(run, event)));
    }

    @Transactional
    public void appendToolContractEvent(MangaAgentConversation conversation, UUID requestId,
                                        Map<String, Object> payload) {
        if (conversation == null || requestId == null || payload == null || payload.isEmpty()) {
            return;
        }
        runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .ifPresent(run -> appendEvent(run, "tool_contract", payload));
    }

    @Transactional
    public void mergeRunAttributes(MangaAgentConversation conversation, UUID requestId,
                                   Map<String, Object> attributes) {
        if (conversation == null || requestId == null || attributes == null || attributes.isEmpty()) {
            return;
        }
        runRepository.findForUpdate(conversation.getId(), requestId)
                .ifPresent(run -> {
                    Map<String, Object> merged = deepMerge(readMap(run.getRunAttributesJson()), attributes);
                    run.setRunAttributesJson(toJson(merged));
                    runRepository.save(run);
                });
    }

    @Transactional(readOnly = true)
    public RunSnapshot snapshot(MangaAgentRun run) {
        return snapshotReader.snapshot(run);
    }

    @Transactional(readOnly = true)
    public RunEventReplayPage replayEvents(Long userId, Long chapterId, UUID requestId, long afterEventId) {
        return snapshotReader.replayEvents(userId, chapterId, requestId, afterEventId);
    }

    @Transactional(readOnly = true)
    public ResumeContext resumeContext(MangaAgentRun run) {
        return snapshotReader.resumeContext(run);
    }

    public Map<String, Object> toPayload(AgentRunEvent event) {
        return snapshotReader.toPayload(event);
    }

    private MangaAgentRun markTerminal(UUID requestId, Long userId, Long chapterId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findForUpdate(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        return applyTerminal(run, status, reply, error);
    }

    private MangaAgentRun markTerminal(MangaAgentConversation conversation, UUID requestId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findForUpdate(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        return applyTerminal(run, status, reply, error);
    }

    private MangaAgentRun applyTerminal(MangaAgentRun run, MangaAgentRunStatus status, String reply, String error) {
        run.setStatus(status);
        run.setFinalReply(reply);
        run.setErrorMessage(error);
        run.setUserInputRequestJson(null);
        run.setOwnerInstanceId(null);
        run.setLeaseUntil(null);
        run.setCompletedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        return runRepository.save(run);
    }

    private boolean isTerminal(MangaAgentRunStatus status) {
        return status == MangaAgentRunStatus.SUCCEEDED
                || status == MangaAgentRunStatus.DEGRADED
                || status == MangaAgentRunStatus.FAILED
                || status == MangaAgentRunStatus.CANCELLED
                || status == MangaAgentRunStatus.INTERRUPTED;
    }

    private String normalizePhase(String phase) {
        if (phase == null) {
            return "MODEL";
        }
        return switch (phase.toUpperCase()) {
            case "ROUTER", "AGENT", "TOOL", "MODEL", "PREPARING_CONTEXT", "BUILDING_REQUEST",
                    "WAITING_MODEL", "GENERATING" -> phase.toUpperCase();
            default -> "MODEL";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize manga agent run payload", e);
        }
    }

    private Map<String, Object> readMap(String value) {
        return snapshotReader.readMap(value);
    }

    private RunContextSnapshot readContextSnapshot(String value) {
        return snapshotReader.readContextSnapshot(value);
    }

    private RunContextSnapshot withKnowledgeRecallHash(RunContextSnapshot current, String knowledgeRecallHash) {
        return snapshotReader.withKnowledgeRecallHash(current, knowledgeRecallHash);
    }

    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> updates) {
        return snapshotReader.deepMerge(base, updates);
    }

    private Map<String, Object> toolAuditPayload(MangaAgentRun run, AgentRunToolStatus.ToolEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "tool_audit");
        payload.put("phase", "tool");
        payload.put("label", event.toolName());
        payload.put("runId", run.getId());
        payload.put("requestId", event.requestId() == null ? run.getRequestId() : event.requestId());
        if (event.stepId() != null && !event.stepId().isBlank()) {
            payload.put("stepId", event.stepId());
        }
        payload.put("tool", event.toolName());
        payload.put("toolName", event.toolName());
        payload.put("status", event.status());
        payload.put("succeeded", event.succeeded());
        payload.put("durationMs", event.durationMs());
        if (event.resultHash() != null && !event.resultHash().isBlank()) {
            payload.put("resultHash", event.resultHash());
        }
        if (event.auditId() != null && !event.auditId().isBlank()) {
            payload.put("auditId", event.auditId());
        }
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        payload.put("result", event.result());
        if (event.createdAt() != null) {
            payload.put("createdAt", event.createdAt().toString());
        }
        return Map.copyOf(payload);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
