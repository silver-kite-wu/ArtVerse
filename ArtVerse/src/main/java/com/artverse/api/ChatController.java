package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.ChatService;
import com.artverse.application.CurrentUserService;
import com.artverse.application.StoryChatAgentService;
import com.artverse.application.UserProviderConfig;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final StoryChatAgentService storyChatAgentService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    @PostMapping("/chat")
    public SseEmitter chat(@PathVariable Long chapterId,
                           @RequestBody Map<String, String> body) {
        String content = body.get("message");
        User user = currentUserService.requireCurrentUser();
        UserProviderConfig llmConfig = apiKeyService.requireByokProviderConfig(
                user,
                requestConfig(body),
                configId(body),
                "Please configure an LLM provider API key in Settings before using story chat."
        );
        MangaAgentConversation conversation = chatService.storyConversation(chapterId, user);
        return storyChatAgentService.runAgUiStream(chapterId, conversation.getConversationUuid(),
                content, null, user, llmConfig);
    }

    private UserProviderConfig requestConfig(Map<String, String> body) {
        return new UserProviderConfig(
                ApiKeyService.SLOT_LLM,
                body.get("provider"),
                body.get("label"),
                firstNonBlank(body.get("api_key"), body.get("apiKey")),
                firstNonBlank(body.get("base_url"), body.get("baseUrl")),
                body.get("model")
        );
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private Long configId(Map<String, String> body) {
        String value = body.get("config_id");
        if (value == null || value.isBlank()) return null;
        return Long.valueOf(value);
    }
}
