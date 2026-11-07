package com.artverse.api;

import com.artverse.api.dto.MangaAgentDtos;
import com.artverse.application.ApiKeyService;
import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaAgentEventReplayService;
import com.artverse.application.StoryChatAgentService;
import com.artverse.application.NovelDraftReviewService;
import com.artverse.application.NovelContentArtifactService;
import com.artverse.application.UserProviderConfig;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chapters/{chapterId}/story-chat")
@RequiredArgsConstructor
public class StoryChatController {

    private final StoryChatAgentService storyChatAgentService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;
    private final MangaAgentEventReplayService eventReplayService;
    private final NovelDraftReviewService novelDraftReviewService;

    public record DraftDecisionRequest(String decision, String content, Long baseVersion, String expectedContentHash,
                                       Boolean continueAgent) { }

    @PostMapping("/conversations/{conversationId}/runs/{requestId}/artifacts/{artifactId}/decision")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "story-chat-write")
    public NovelContentArtifactService.CommitView decideDraft(@PathVariable Long chapterId,
            @PathVariable UUID conversationId, @PathVariable UUID requestId, @PathVariable UUID artifactId,
            @RequestBody DraftDecisionRequest body) {
        User user = currentUserService.requireCurrentUser();
        if (Boolean.TRUE.equals(body.continueAgent())) {
            return novelDraftReviewService.decideForContinuation(user.getId(), chapterId, conversationId, requestId, artifactId,
                    body.decision(), body.content(), body.baseVersion(), body.expectedContentHash());
        }
        return novelDraftReviewService.decide(user.getId(), chapterId, conversationId, requestId, artifactId,
                body.decision(), body.content(), body.baseVersion(), body.expectedContentHash());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<ChatMessage> conversationMessages(@PathVariable Long chapterId,
                                                  @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        return storyChatAgentService.listMessages(chapterId, conversationId, user);
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/run")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "story-chat-run")
    public SseEmitter run(@PathVariable Long chapterId,
                          @PathVariable UUID conversationId,
                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return storyChatAgentService.runAgUiStream(
                chapterId, conversationId, body.message(), body.requestId(), user, requireLlmConfig(user, body));
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/runs/{requestId}/resume")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "story-chat-run")
    public SseEmitter resume(@PathVariable Long chapterId,
                             @PathVariable UUID conversationId,
                             @PathVariable UUID requestId,
                             @RequestBody MangaAgentDtos.StoryChatResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return storyChatAgentService.resumeAgUiStream(
                chapterId, conversationId, requestId, body.decision(), body.artifactId(), body.answer(),
                body.toolCallId(), body.pendingVersion(), body.selectedOptionId(), user, requireLlmConfig(user, body));
    }

    @GetMapping("/conversations/{conversationId}/runs/open")
    public MangaAgentDtos.OpenRunResponse openRun(@PathVariable Long chapterId,
                                                  @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        var open = storyChatAgentService.latestOpenRun(chapterId, conversationId, user).run();
        return new MangaAgentDtos.OpenRunResponse(
                open == null
                        ? null
                        : MangaAgentDtos.RunStateResponse.from(open)
        );
    }

    @GetMapping("/conversations/{conversationId}/runs")
    public List<MangaAgentDtos.RunStateResponse> runs(@PathVariable Long chapterId, @PathVariable UUID conversationId) {
        return storyChatAgentService.conversationRuns(chapterId, conversationId, currentUserService.requireCurrentUser())
                .stream().map(MangaAgentDtos.RunStateResponse::from).toList();
    }

    @GetMapping("/conversations/{conversationId}/runs/{requestId}")
    public MangaAgentDtos.RunStateResponse runState(@PathVariable Long chapterId,
                                                    @PathVariable UUID conversationId,
                                                    @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(
                storyChatAgentService.getRun(chapterId, conversationId, requestId, user));
    }

    @GetMapping(value = "/conversations/{conversationId}/runs/{requestId}/events", produces = "text/event-stream")
    public SseEmitter replayRunEvents(@PathVariable Long chapterId,
                                      @PathVariable UUID conversationId,
                                      @PathVariable UUID requestId,
                                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        User user = currentUserService.requireCurrentUser();
        storyChatAgentService.getRun(chapterId, conversationId, requestId, user);
        return eventReplayService.replay(user.getId(), chapterId, requestId, parseEventId(lastEventId));
    }

    @GetMapping("/conversations/{conversationId}/runs/{requestId}/artifacts")
    public List<?> artifacts(@PathVariable Long chapterId,
                             @PathVariable UUID conversationId,
                             @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return storyChatAgentService.artifacts(chapterId, conversationId, requestId, user);
    }

    @PostMapping("/conversations/{conversationId}/runs/{requestId}/cancel")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "story-chat-write")
    public MangaAgentDtos.RunStateResponse cancel(@PathVariable Long chapterId,
                                                  @PathVariable UUID conversationId,
                                                  @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(
                storyChatAgentService.cancelRun(chapterId, conversationId, requestId, user));
    }

    private UserProviderConfig requireLlmConfig(User user, MangaAgentDtos.RunRequest body) {
        return apiKeyService.requireByokProviderConfig(
                user,
                new UserProviderConfig(ApiKeyService.SLOT_LLM, body.provider(), body.label(),
                        body.apiKey(), body.baseUrl(), body.model()),
                body.configId(),
                "Please configure an LLM provider API key in Settings before using story chat."
        );
    }

    private UserProviderConfig requireLlmConfig(User user, MangaAgentDtos.StoryChatResumeRequest body) {
        return apiKeyService.requireByokProviderConfig(
                user,
                new UserProviderConfig(ApiKeyService.SLOT_LLM, body.provider(), body.label(),
                        body.apiKey(), body.baseUrl(), body.model()),
                body.configId(),
                "Please configure an LLM provider API key in Settings before using story chat."
        );
    }

    private long parseEventId(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException error) {
            throw new com.artverse.common.BusinessException(400, "Invalid Last-Event-ID");
        }
    }
}
