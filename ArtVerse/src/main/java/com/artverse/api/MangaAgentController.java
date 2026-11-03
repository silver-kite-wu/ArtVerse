package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.api.dto.MangaAgentDtos;
import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaAgentService;
import com.artverse.application.MangaAgentEventReplayService;
import com.artverse.application.StoryboardArtifactService;
import com.artverse.application.UserProviderConfig;
import com.artverse.common.aspect.RateLimit;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/chapters/{chapterId}/manga-agent")
@RequiredArgsConstructor
public class MangaAgentController {

    private final ApiKeyService apiKeyService;
    private final MangaAgentService mangaAgentService;
    private final CurrentUserService currentUserService;
    private final StoryboardArtifactService storyboardArtifactService;
    private final MangaAgentEventReplayService eventReplayService;

    @GetMapping("/messages")
    public MangaAgentDtos.MessagesResponse messages(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.MessagesResponse(
                mangaAgentService.listMessages(chapterId, user).stream()
                        .map(MangaAgentDtos.MessageDto::from)
                        .toList()
        );
    }

    @GetMapping("/conversations")
    public MangaAgentDtos.ConversationsResponse conversations(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.ConversationsResponse(
                mangaAgentService.listConversations(chapterId, user).stream()
                        .map(MangaAgentDtos.ConversationDto::from)
                        .toList()
        );
    }

    @PostMapping("/conversations")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "manga-agent-write")
    public MangaAgentDtos.ConversationDto createConversation(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.ConversationDto.from(mangaAgentService.createConversation(chapterId, user));
    }

    @PostMapping("/conversations/{conversationId}/archive")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "manga-agent-write")
    public MangaAgentDtos.ConversationDto archiveConversation(@PathVariable Long chapterId,
                                                             @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.ConversationDto.from(
                mangaAgentService.archiveConversation(chapterId, conversationId, user)
        );
    }

    @DeleteMapping("/conversations/{conversationId}")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "manga-agent-write")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long chapterId,
                                   @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        mangaAgentService.deleteConversation(chapterId, conversationId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MangaAgentDtos.MessagesResponse conversationMessages(@PathVariable Long chapterId,
                                                               @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.MessagesResponse(
                mangaAgentService.listMessages(chapterId, conversationId, user).stream()
                        .map(MangaAgentDtos.MessageDto::from)
                        .toList()
        );
    }

    @PostMapping("/run")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public MangaAgentDtos.RunResponse run(@PathVariable Long chapterId,
                                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.run(
                chapterId, body.message(), body.requestId(), user, requireLlmConfig(user, body)
        );
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/ag-ui/run")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public SseEmitter runAgUi(@PathVariable Long chapterId,
                              @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runAgUiStream(
                chapterId, body.message(), body.requestId(), user, requireLlmConfig(user, body)
        );
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/run")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public SseEmitter runConversationAgUi(@PathVariable Long chapterId,
                                          @PathVariable UUID conversationId,
                                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runAgUiStream(
                chapterId, conversationId, body.message(), body.requestId(), user,
                requireLlmConfig(user, body)
        );
    }

    @GetMapping("/runs/open")
    public MangaAgentDtos.OpenRunResponse openRun(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.OpenRunResponse(
                mangaAgentService.latestOpenRun(chapterId, user)
                        .map(MangaAgentDtos.RunStateResponse::from)
                        .orElse(null)
        );
    }

    @GetMapping("/conversations/{conversationId}/runs/open")
    public MangaAgentDtos.OpenRunResponse conversationOpenRun(@PathVariable Long chapterId,
                                                             @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        return new MangaAgentDtos.OpenRunResponse(
                mangaAgentService.latestOpenRun(chapterId, conversationId, user)
                        .map(MangaAgentDtos.RunStateResponse::from)
                        .orElse(null)
        );
    }

    @GetMapping("/runs/{requestId}")
    public MangaAgentDtos.RunStateResponse runState(@PathVariable Long chapterId,
                                                    @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(mangaAgentService.getRun(chapterId, requestId, user));
    }

    @GetMapping("/runs/{requestId}/artifacts")
    public List<StoryboardArtifactService.ArtifactView> runArtifacts(
            @PathVariable Long chapterId,
            @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return storyboardArtifactService.list(user.getId(), chapterId, requestId);
    }

    @GetMapping(value = "/runs/{requestId}/events", produces = "text/event-stream")
    public SseEmitter replayRunEvents(
            @PathVariable Long chapterId,
            @PathVariable UUID requestId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        User user = currentUserService.requireCurrentUser();
        return eventReplayService.replay(user.getId(), chapterId, requestId, parseEventId(lastEventId));
    }

    @GetMapping("/conversations/{conversationId}/runs/{requestId}")
    public MangaAgentDtos.RunStateResponse conversationRunState(@PathVariable Long chapterId,
                                                               @PathVariable UUID conversationId,
                                                               @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(
                mangaAgentService.getRun(chapterId, conversationId, requestId, user)
        );
    }

    @PostMapping("/runs/{requestId}/cancel")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "manga-agent-write")
    public MangaAgentDtos.RunStateResponse cancelRun(@PathVariable Long chapterId,
                                                     @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(mangaAgentService.cancelRun(chapterId, requestId, user));
    }

    @PostMapping("/conversations/{conversationId}/runs/{requestId}/cancel")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "manga-agent-write")
    public MangaAgentDtos.RunStateResponse cancelConversationRun(@PathVariable Long chapterId,
                                                                @PathVariable UUID conversationId,
                                                                @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(
                mangaAgentService.cancelRun(chapterId, conversationId, requestId, user)
        );
    }

    @PostMapping("/runs/{requestId}/resume")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public MangaAgentDtos.RunResponse resume(@PathVariable Long chapterId,
                                             @PathVariable UUID requestId,
                                             @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.resume(
                chapterId, requestId, body.answer(), user, requireLlmConfig(user, body)
        );
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/ag-ui/runs/{requestId}/resume")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public SseEmitter resumeAgUi(@PathVariable Long chapterId,
                                 @PathVariable UUID requestId,
                                 @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeAgUiStream(
                chapterId, requestId, body.answer(), user, requireLlmConfig(user, body)
        );
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/runs/{requestId}/resume")
    @RateLimit(windowSeconds = 60, maxRequests = 20, key = "manga-agent-run")
    public SseEmitter resumeConversationAgUi(@PathVariable Long chapterId,
                                            @PathVariable UUID conversationId,
                                            @PathVariable UUID requestId,
                                            @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeAgUiStream(
                chapterId, conversationId, requestId, body.answer(), user,
                requireLlmConfig(user, body)
        );
    }

    private UserProviderConfig requireLlmConfig(User user) {
        return apiKeyService.requireActiveUserProviderConfig(
                user,
                ApiKeyService.SLOT_LLM,
                "Please configure an LLM provider API key in Settings before using the manga agent."
        );
    }

    private UserProviderConfig requireLlmConfig(User user, MangaAgentDtos.RunRequest body) {
        return apiKeyService.requireByokProviderConfig(
                user,
                new UserProviderConfig(
                        ApiKeyService.SLOT_LLM,
                        body.provider(),
                        body.label(),
                        body.apiKey(),
                        body.baseUrl(),
                        body.model()
                ),
                body.configId(),
                "Please configure an LLM provider API key in Settings before using the manga agent."
        );
    }

    private UserProviderConfig requireLlmConfig(User user, MangaAgentDtos.ResumeRequest body) {
        return apiKeyService.requireByokProviderConfig(
                user,
                new UserProviderConfig(
                        ApiKeyService.SLOT_LLM,
                        body.provider(),
                        body.label(),
                        body.apiKey(),
                        body.baseUrl(),
                        body.model()
                ),
                body.configId(),
                "Please configure an LLM provider API key in Settings before using the manga agent."
        );
    }

    private long parseEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative event id");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new com.artverse.common.BusinessException(400, "Invalid Last-Event-ID");
        }
    }
}
