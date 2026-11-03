package com.artverse.api;

import com.artverse.application.AiConversationService;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
public class AiConversationController {
    private final AiConversationService service;
    private final CurrentUserService currentUserService;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam AiConversationType type,
                                           @RequestParam(required = false) Long chapterId,
                                           @RequestParam(defaultValue = "false") boolean includeArchived) {
        User user = currentUserService.requireCurrentUser();
        return service.list(user, type, chapterId, includeArchived).stream().map(this::summary).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        User user = currentUserService.requireCurrentUser();
        AiConversationType type = AiConversationType.valueOf(String.valueOf(body.get("type")));
        Long chapterId = body.get("chapterId") == null ? null : Long.valueOf(String.valueOf(body.get("chapterId")));
        return summary(service.create(user, type, chapterId, body.get("title") == null ? null : String.valueOf(body.get("title"))));
    }

    @PutMapping("/{conversationId}/title")
    public Map<String, Object> rename(@PathVariable UUID conversationId, @RequestBody Map<String, String> body) {
        return summary(service.rename(currentUserService.requireCurrentUser(), conversationId, body.get("title")));
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID conversationId) {
        service.delete(currentUserService.requireCurrentUser(), conversationId);
    }

    @PostMapping("/{conversationId}/archive")
    public Map<String, Object> archive(@PathVariable UUID conversationId) {
        return summary(service.archive(currentUserService.requireCurrentUser(), conversationId));
    }

    private Map<String, Object> summary(MangaAgentConversation c) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("conversationId", c.getConversationUuid()); value.put("type", c.getConversationType());
        value.put("title", c.getTitle()); value.put("titleSource", c.getTitleSource()); value.put("titleState", c.getTitleState());
        value.put("status", c.getStatus()); value.put("storyId", c.getStory() == null ? null : c.getStory().getId());
        value.put("chapterId", c.getChapter() == null ? null : c.getChapter().getId()); value.put("lastActivityAt", c.getLastActivityAt());
        return value;
    }
}
