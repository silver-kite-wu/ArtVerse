package com.artverse.api;

import com.artverse.api.dto.MangaAgentDtos;
import com.artverse.application.CurrentUserService;
import com.artverse.application.MangaAgentService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/chapters/{chapterId}/manga-agent")
@RequiredArgsConstructor
public class MangaAgentController {

    private final MangaAgentService mangaAgentService;
    private final CurrentUserService currentUserService;

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
    public MangaAgentDtos.ConversationDto createConversation(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.ConversationDto.from(mangaAgentService.createConversation(chapterId, user));
    }

    @PostMapping("/conversations/{conversationId}/archive")
    public MangaAgentDtos.ConversationDto archiveConversation(@PathVariable Long chapterId,
                                                             @PathVariable UUID conversationId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.ConversationDto.from(
                mangaAgentService.archiveConversation(chapterId, conversationId, user)
        );
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
    public MangaAgentDtos.RunResponse run(@PathVariable Long chapterId,
                                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.run(chapterId, body.message(), body.requestId(), user);
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/run-stream")
    public SseEmitter runStream(@PathVariable Long chapterId,
                                @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runStream(chapterId, body.message(), body.requestId(), user);
    }

    @PostMapping("/ag-ui/run")
    public SseEmitter runAgUi(@PathVariable Long chapterId,
                              @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runAgUiStream(chapterId, body.message(), body.requestId(), user);
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/run")
    public SseEmitter runConversationAgUi(@PathVariable Long chapterId,
                                          @PathVariable UUID conversationId,
                                          @RequestBody MangaAgentDtos.RunRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.runAgUiStream(chapterId, conversationId, body.message(), body.requestId(), user);
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
    public MangaAgentDtos.RunStateResponse cancelRun(@PathVariable Long chapterId,
                                                     @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(mangaAgentService.cancelRun(chapterId, requestId, user));
    }

    @PostMapping("/conversations/{conversationId}/runs/{requestId}/cancel")
    public MangaAgentDtos.RunStateResponse cancelConversationRun(@PathVariable Long chapterId,
                                                                @PathVariable UUID conversationId,
                                                                @PathVariable UUID requestId) {
        User user = currentUserService.requireCurrentUser();
        return MangaAgentDtos.RunStateResponse.from(
                mangaAgentService.cancelRun(chapterId, conversationId, requestId, user)
        );
    }

    @PostMapping("/runs/{requestId}/resume")
    public MangaAgentDtos.RunResponse resume(@PathVariable Long chapterId,
                                             @PathVariable UUID requestId,
                                             @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        MangaAgentService.RunResult result = mangaAgentService.resume(chapterId, requestId, body.answer(), user);
        return new MangaAgentDtos.RunResponse(result.reply(), result.requestId());
    }

    @PostMapping("/runs/{requestId}/resume-stream")
    public SseEmitter resumeStream(@PathVariable Long chapterId,
                                   @PathVariable UUID requestId,
                                   @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeStream(chapterId, requestId, body.answer(), user);
    }

    @PostMapping("/ag-ui/runs/{requestId}/resume")
    public SseEmitter resumeAgUi(@PathVariable Long chapterId,
                                 @PathVariable UUID requestId,
                                 @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeAgUiStream(chapterId, requestId, body.answer(), user);
    }

    @PostMapping("/conversations/{conversationId}/ag-ui/runs/{requestId}/resume")
    public SseEmitter resumeConversationAgUi(@PathVariable Long chapterId,
                                            @PathVariable UUID conversationId,
                                            @PathVariable UUID requestId,
                                            @RequestBody MangaAgentDtos.ResumeRequest body) {
        User user = currentUserService.requireCurrentUser();
        return mangaAgentService.resumeAgUiStream(chapterId, conversationId, requestId, body.answer(), user);
    }
}
