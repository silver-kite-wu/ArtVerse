package com.artverse.api;

import com.artverse.api.dto.ReaderCharacterChatDtos;
import com.artverse.application.CurrentUserService;
import com.artverse.application.ReaderCharacterChatService;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reader")
@RequiredArgsConstructor
public class ReaderCharacterChatController {
    private final ReaderCharacterChatService service;
    private final CurrentUserService currentUserService;

    @GetMapping("/stories/{storyId}/chapters/{chapterId}/characters")
    public ReaderCharacterChatDtos.CharacterListResponse characters(@PathVariable Long storyId, @PathVariable Long chapterId) {
        return service.listCharacters(storyId, chapterId, currentUserService.requireCurrentUser());
    }

    @GetMapping("/stories/{storyId}/chapters/{chapterId}/friends")
    public ReaderCharacterChatDtos.CharacterListResponse friends(
            @PathVariable Long storyId,
            @PathVariable Long chapterId,
            @RequestParam(required = false) String q) {
        return service.listFriends(storyId, chapterId, q, currentUserService.requireCurrentUser());
    }

    @GetMapping("/stories/{storyId}/chapters/{chapterId}/conversations")
    public ReaderCharacterChatDtos.ConversationListResponse conversations(
            @PathVariable Long storyId,
            @PathVariable Long chapterId) {
        return service.listConversations(storyId, chapterId, currentUserService.requireCurrentUser());
    }

    @PostMapping("/character-friends")
    public ReaderCharacterChatDtos.ConversationView addFriend(@RequestBody ReaderCharacterChatDtos.AddFriendRequest request) {
        return service.addFriend(request, currentUserService.requireCurrentUser());
    }

    @PostMapping("/character-groups")
    public ReaderCharacterChatDtos.ConversationView createGroup(@RequestBody ReaderCharacterChatDtos.CreateGroupRequest request) {
        return service.createGroup(request, currentUserService.requireCurrentUser());
    }

    @PostMapping("/character-conversations")
    public ReaderCharacterChatDtos.ConversationView create(@RequestBody ReaderCharacterChatDtos.CreateConversationRequest request) {
        return service.createConversation(request, currentUserService.requireCurrentUser());
    }

    @GetMapping("/character-conversations/{conversationId}/messages")
    public List<ReaderCharacterChatDtos.MessageView> messages(@PathVariable UUID conversationId) {
        return service.listMessages(conversationId, currentUserService.requireCurrentUser());
    }

    @DeleteMapping("/character-messages/{messageId}")
    public void deleteMessage(@PathVariable Long messageId) {
        service.deleteMessage(messageId, currentUserService.requireCurrentUser());
    }

    @PostMapping("/character-conversations/{conversationId}/messages")
    public ReaderCharacterChatDtos.SendMessageResponse send(
            @PathVariable UUID conversationId,
            @RequestBody ReaderCharacterChatDtos.SendMessageRequest request) {
        return service.send(conversationId, request, currentUserService.requireCurrentUser());
    }
}
