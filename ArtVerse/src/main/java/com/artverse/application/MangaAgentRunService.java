package com.artverse.application;

import com.artverse.agents.AgentRunEvent;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentRun;
import com.artverse.domain.MangaAgentRunEventRecord;
import com.artverse.domain.MangaAgentRunStatus;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentRunEventRepository;
import com.artverse.persistence.MangaAgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MangaAgentRunService {

    private static final List<MangaAgentRunStatus> OPEN_STATUSES = List.of(
            MangaAgentRunStatus.RUNNING,
            MangaAgentRunStatus.WAITING_USER
    );

    private final MangaAgentRunRepository runRepository;
    private final MangaAgentRunEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public MangaAgentRun startOrReuse(User user, Chapter chapter, UUID requestId, String inputMessage) {
        return runRepository.findByUserIdAndChapterIdAndRequestId(user.getId(), chapter.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
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
                    return runRepository.save(run);
                });
    }

    @Transactional
    public MangaAgentRun startOrReuse(MangaAgentConversation conversation, UUID requestId, String inputMessage) {
        return runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .map(existing -> {
                    if (existing.getStatus() == MangaAgentRunStatus.WAITING_USER) {
                        existing.setStatus(MangaAgentRunStatus.RUNNING);
                        existing.setUserInputRequestJson(null);
                        existing.setErrorMessage(null);
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
                    run.setStatus(MangaAgentRunStatus.RUNNING);
                    return runRepository.save(run);
                });
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
        if (run.getUserInputRequestJson() == null || run.getUserInputRequestJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(run.getUserInputRequestJson(), AgentUserInputRequest.class);
        } catch (Exception e) {
            throw new BusinessException(500, "Stored user input request is invalid");
        }
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
    public int interruptStaleRunningRuns(OffsetDateTime staleBefore) {
        List<MangaAgentRun> staleRuns = runRepository.findByStatusAndUpdatedAtBefore(
                MangaAgentRunStatus.RUNNING,
                staleBefore
        );
        OffsetDateTime now = OffsetDateTime.now();
        for (MangaAgentRun run : staleRuns) {
            run.setStatus(MangaAgentRunStatus.INTERRUPTED);
            run.setErrorMessage("Agent run interrupted because no progress was recorded before " + staleBefore);
            run.setUserInputRequestJson(null);
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            runRepository.save(run);
        }
        return staleRuns.size();
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
    public void appendEvent(MangaAgentRun run, String eventName, Map<String, Object> payload) {
        MangaAgentRun attachedRun = runRepository.getReferenceById(run.getId());
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
    }

    @Transactional
    public void appendRunEvent(MangaAgentRun run, AgentRunEvent event) {
        appendEvent(run, "run_event", toPayload(event));
    }

    @Transactional(readOnly = true)
    public RunSnapshot snapshot(MangaAgentRun run) {
        List<RunEventSnapshot> events = eventRepository.findByRunIdOrderByIdAsc(run.getId())
                .stream()
                .map(this::toPayload)
                .toList();
        return new RunSnapshot(
                run.getRequestId(),
                run.getStatus(),
                run.getInputMessage(),
                run.getFinalReply(),
                run.getErrorMessage(),
                waitingInput(run),
                events,
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getCompletedAt()
        );
    }

    public Map<String, Object> toPayload(AgentRunEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        payload.put("phase", event.phase());
        payload.put("label", event.label());
        if (event.toolName() != null) {
            payload.put("toolName", event.toolName());
        }
        if (event.status() != null) {
            payload.put("status", event.status());
        }
        if (event.text() != null) {
            payload.put("text", event.text());
        }
        payload.put("data", event.data());
        payload.put("createdAt", event.createdAt().toString());
        return payload;
    }

    private MangaAgentRun markTerminal(UUID requestId, Long userId, Long chapterId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findByUserIdAndChapterIdAndRequestId(userId, chapterId, requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        run.setStatus(status);
        run.setFinalReply(reply);
        run.setErrorMessage(error);
        run.setUserInputRequestJson(null);
        run.setCompletedAt(OffsetDateTime.now());
        run.setUpdatedAt(OffsetDateTime.now());
        return runRepository.save(run);
    }

    private MangaAgentRun markTerminal(MangaAgentConversation conversation, UUID requestId, MangaAgentRunStatus status,
                                       String reply, String error) {
        MangaAgentRun run = runRepository.findByConversationIdAndRequestId(conversation.getId(), requestId)
                .orElseThrow(() -> new BusinessException(404, "Agent run not found"));
        if (isTerminal(run.getStatus())) {
            return run;
        }
        run.setStatus(status);
        run.setFinalReply(reply);
        run.setErrorMessage(error);
        run.setUserInputRequestJson(null);
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

    private RunEventSnapshot toPayload(MangaAgentRunEventRecord event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayloadJson(), new TypeReference<>() {
            });
            return new RunEventSnapshot(event.getEventName(), payload, event.getCreatedAt());
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", event.getEventType());
            fallback.put("phase", event.getPhase());
            fallback.put("label", event.getLabel());
            fallback.put("status", event.getStatus());
            fallback.put("createdAt", event.getCreatedAt().toString());
            fallback.put("data", Map.of());
            return new RunEventSnapshot(event.getEventName(), fallback, event.getCreatedAt());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize manga agent run payload", e);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record RunSnapshot(
            UUID requestId,
            MangaAgentRunStatus status,
            String inputMessage,
            String finalReply,
            String errorMessage,
            AgentUserInputRequest userInputRequest,
            List<RunEventSnapshot> events,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime completedAt
    ) {
    }

    public record RunEventSnapshot(
            String eventName,
            Map<String, Object> data,
            OffsetDateTime createdAt
    ) {
    }
}
