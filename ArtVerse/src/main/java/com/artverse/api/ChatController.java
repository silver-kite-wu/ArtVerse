package com.artverse.api;

import com.artverse.application.ApiKeyService;
import com.artverse.application.ChatService;
import com.artverse.application.CurrentUserService;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ApiKeyService apiKeyService;
    private final CurrentUserService currentUserService;

    @PostMapping("/chat")
    public SseEmitter chat(@PathVariable Long chapterId,
                           @RequestBody Map<String, String> body) {
        String content = body.get("message");
        chatService.saveUserMessage(chapterId, content);

        User user = currentUserService.requireCurrentUser();
        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        return chatService.streamChat(chapterId, content, user.getId(), deepseekApiKey);
    }

    @GetMapping("/messages")
    public List<ChatMessage> getMessages(@PathVariable Long chapterId) {
        return chatService.getMessages(chapterId);
    }
}
