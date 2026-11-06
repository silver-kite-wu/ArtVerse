package com.artverse.application;

import com.artverse.agent.AgentRunEvent;
import com.artverse.application.workflow.ExecutionPlan;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.domain.AgentRunType;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.common.BusinessException;
import com.artverse.persistence.MangaAgentRunArtifactRepository;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.artverse.persistence.MangaAgentRunStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.artverse.application.MangaAgentRunSnapshots.*;

/** Reads persisted run state and assembles stable API-facing run projections. */
@Slf4j
@Service
public class MangaAgentRunSnapshotReader {

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunEventRepository eventRepository;
    private final MangaAgentRunStepRepository stepRepository;
    private final MangaAgentRunArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public MangaAgentRunSnapshotReader(MangaAgentRunRepository runRepository,
                                       MangaAgentRunEventRepository eventRepository,
                                       MangaAgentRunStepRepository stepRepository,
                                       MangaAgentRunArtifactRepository artifactRepository,
                                       ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.stepRepository = stepRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RunSnapshot snapshot(MangaAgentRun run) {
        List<RunEventSnapshot> events = eventRepository.findByRunIdOrderByIdAsc(run.getId())
                .stream().map(this::toPayload).toList();
        List<RunStepSnapshot> steps = stepRepository == null ? List.of()
                : stepRepository.findByRunIdOrderByStepSequenceAsc(run.getId()).stream()
                .map(step -> new RunStepSnapshot(step.getPlanId(), step.getStepSequence(), step.getRoute(),
                        step.getStatus(), step.isMutating(), step.getSkillKey(), step.getSkillVersion(),
                        step.getInputSummary(), step.getOutputSummary(), step.getStartedAt(), step.getCompletedAt()))
                .toList();
        List<RunArtifactSnapshot> artifacts = artifactRepository == null ? List.of()
                : artifactRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(artifact -> new RunArtifactSnapshot(artifact.getArtifactUuid(), artifact.getArtifactType(),
                        artifact.getStatus(), artifact.getSchemaVersion(), publicEvaluation(artifact),
                        artifact.getChecksum()))
                .toList();
        MangaWorkflowRoute route = run.getRoute() == null ? MangaWorkflowRoute.DIRECTOR : run.getRoute();
        return new RunSnapshot(
                run.getRequestId(),
                run.getRunType() == null ? AgentRunType.MANGA_AGENT : run.getRunType(),
                run.getStatus(), run.getInputMessage(), run.getFinalReply(), run.getErrorMessage(), route,
                run.getRouteKey() == null || run.getRouteKey().isBlank() ? route.name() : run.getRouteKey(),
                waitingInput(run), events, run.getCreatedAt(), run.getUpdatedAt(), run.getCompletedAt(),
                run.getLastProgressAt(), run.getCurrentPhase(), run.getRouteSource(), run.getRouteConfidence(),
                run.getRouterVersion(), run.getWorkflowVersion(), run.getTraceId(), readMap(run.getSkillVersionsJson()),
                run.getModelConfigId(), run.getKnowledgeSnapshotId(), readMap(run.getBudgetUsageJson()),
                readMap(run.getRunAttributesJson()), readContextSnapshot(run.getContextSnapshotJson()), steps, artifacts);
    }

    private Map<String,Object> publicEvaluation(com.artverse.domain.MangaAgentRunArtifact artifact) {
        var evaluation=new java.util.LinkedHashMap<>(readMap(artifact.getEvaluation()));
        if(CreativeProposalArtifacts.TYPES.contains(artifact.getArtifactType()))evaluation.remove("commands");
        return evaluation;
    }

    /** Reads replay data from the durable log; Redis remains only the live notification path. */
    @Transactional(readOnly = true)
    public RunEventReplayPage replayEvents(Long userId, Long chapterId, java.util.UUID requestId, long afterEventId) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        List<RunEventSnapshot> events = eventRepository
                .findTop200ByRunIdAndIdGreaterThanOrderByIdAsc(run.getId(), Math.max(0L, afterEventId))
                .stream().map(this::toPayload).toList();
        long lastEventId = events.isEmpty() ? Math.max(0L, afterEventId) : events.get(events.size() - 1).eventId();
        return new RunEventReplayPage(run.getStatus(), lastEventId, events);
    }

    @Transactional(readOnly = true)
    public ResumeContext resumeContext(MangaAgentRun run) {
        return new ResumeContext(run.getStatus(),
                run.getRoute() == null ? MangaWorkflowRoute.DIRECTOR : run.getRoute(), waitingInput(run));
    }

    public Map<String, Object> toPayload(AgentRunEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        payload.put("phase", event.phase());
        payload.put("label", event.label());
        if (event.toolName() != null) payload.put("toolName", event.toolName());
        if (event.status() != null) payload.put("status", event.status());
        if (event.text() != null) payload.put("text", event.text());
        payload.put("data", event.data());
        payload.put("createdAt", event.createdAt().toString());
        return payload;
    }

    public AgentUserInputRequest waitingInput(MangaAgentRun run) {
        if (run.getUserInputRequestJson() == null || run.getUserInputRequestJson().isBlank()) return null;
        try {
            return objectMapper.readValue(run.getUserInputRequestJson(), AgentUserInputRequest.class);
        } catch (Exception error) {
            log.warn("Failed to parse stored user input request JSON for run {}; treating as absent", run.getId(), error);
            return null;
        }
    }

    public RoutingDecision routingDecision(MangaAgentRun run) {
        if (run.getRoutingDecisionJson() == null || run.getRoutingDecisionJson().isBlank()) return null;
        try {
            return objectMapper.readValue(run.getRoutingDecisionJson(), RoutingDecision.class);
        } catch (Exception error) {
            log.warn("Failed to parse stored routing decision JSON for run {}; treating as absent", run.getId(), error);
            return null;
        }
    }

    public ExecutionPlan executionPlan(MangaAgentRun run) {
        if (run == null || run.getExecutionPlanJson() == null || run.getExecutionPlanJson().isBlank()) return null;
        try {
            return objectMapper.readValue(run.getExecutionPlanJson(), ExecutionPlan.class);
        } catch (Exception error) {
            log.warn("Failed to parse stored execution plan for run {}", run.getId(), error);
            return null;
        }
    }

    public Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception error) {
            log.warn("Failed to parse run metadata JSON", error);
            return Map.of();
        }
    }

    public RunContextSnapshot readContextSnapshot(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            RunContextSnapshot snapshot = objectMapper.readValue(value, RunContextSnapshot.class);
            return snapshot.isEmpty() ? null : snapshot;
        } catch (Exception error) {
            log.warn("Failed to parse run context snapshot JSON", error);
            return null;
        }
    }

    public RunContextSnapshot withKnowledgeRecallHash(RunContextSnapshot current, String knowledgeRecallHash) {
        RunContextSnapshot base = current == null ? new RunContextSnapshot(
                null, null, null, null, 0, 0, null, null, List.of(), List.of("knowledge_recall_missing")) : current;
        java.util.LinkedHashSet<String> warnings = new java.util.LinkedHashSet<>(base.warnings());
        if (knowledgeRecallHash == null || knowledgeRecallHash.isBlank()) warnings.add("knowledge_recall_missing");
        else warnings.remove("knowledge_recall_missing");
        return new RunContextSnapshot(base.storyId(), base.chapterId(), base.storyTitle(), base.chapterDisplayName(),
                base.sceneCount(), base.imageCount(), base.contextHash(), knowledgeRecallHash,
                base.requiredFields(), List.copyOf(warnings));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> updates) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(base == null ? Map.of() : base);
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            Object current = merged.get(entry.getKey());
            Object incoming = entry.getValue();
            if (current instanceof Map<?, ?> currentMap && incoming instanceof Map<?, ?> incomingMap) {
                merged.put(entry.getKey(), deepMerge((Map<String, Object>) currentMap, (Map<String, Object>) incomingMap));
            } else if ("tool_contract_status".equals(entry.getKey())) {
                merged.put(entry.getKey(), strongerStatus(asString(current), asString(incoming)));
            } else if (current instanceof List<?> currentList && incoming instanceof List<?> incomingList) {
                ArrayList<Object> combined = new ArrayList<>(currentList);
                combined.addAll(incomingList);
                merged.put(entry.getKey(), List.copyOf(combined));
            } else {
                merged.put(entry.getKey(), incoming);
            }
        }
        return Map.copyOf(merged);
    }

    private RunEventSnapshot toPayload(MangaAgentRunEventRecord event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayloadJson(), new TypeReference<>() { });
            return new RunEventSnapshot(event.getId(), event.getEventName(), payload, event.getCreatedAt());
        } catch (Exception error) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", event.getEventType());
            fallback.put("phase", event.getPhase());
            fallback.put("label", event.getLabel());
            fallback.put("status", event.getStatus());
            fallback.put("createdAt", event.getCreatedAt().toString());
            fallback.put("data", Map.of());
            return new RunEventSnapshot(event.getId(), event.getEventName(), fallback, event.getCreatedAt());
        }
    }

    private String strongerStatus(String current, String incoming) {
        if (current == null || current.isBlank()) return incoming;
        if (incoming == null || incoming.isBlank()) return current;
        List<String> order = List.of("PASSED", "DEGRADED", "FAILED");
        return order.indexOf(incoming) > order.indexOf(current) ? incoming : current;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
