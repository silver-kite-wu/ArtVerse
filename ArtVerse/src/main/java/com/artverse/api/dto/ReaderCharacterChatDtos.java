package com.artverse.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ReaderCharacterChatDtos {
    private ReaderCharacterChatDtos() { }

    public record CharacterOption(
            Long id,
            String name,
            String description,
            @JsonProperty("anchor_chapter_number") Integer anchorChapterNumber,
            @JsonProperty("chat_ready") boolean chatReady,
            boolean friend,
            @JsonProperty("lifecycle_status") String lifecycleStatus,
            @JsonProperty("terminal_chapter_number") Integer terminalChapterNumber,
            @JsonProperty("can_chat") boolean canChat,
            @JsonProperty("conversation_id") UUID conversationId,
            @JsonProperty("last_message") String lastMessage) { }

    public record ConversationView(
            UUID id,
            @JsonProperty("story_id") Long storyId,
            @JsonProperty("character_id") Long characterId,
            @JsonProperty("character_name") String characterName,
            @JsonProperty("anchor_chapter_id") Long anchorChapterId,
            @JsonProperty("anchor_chapter_number") Integer anchorChapterNumber,
            @JsonProperty("snapshot_status") String snapshotStatus,
            @JsonProperty("conversation_type") String conversationType,
            @JsonProperty("member_ids") List<Long> memberIds) { }

    public record ConversationSummary(
            UUID id,
            @JsonProperty("conversation_type") String conversationType,
            String title,
            @JsonProperty("character_id") Long characterId,
            @JsonProperty("character_name") String characterName,
            @JsonProperty("anchor_chapter_number") Integer anchorChapterNumber,
            @JsonProperty("last_message") String lastMessage,
            @JsonProperty("last_message_at") OffsetDateTime lastMessageAt,
            @JsonProperty("unread_count") int unreadCount,
            @JsonProperty("member_count") int memberCount) { }

    public record MessageView(
            Long id,
            String role,
            String content,
            @JsonProperty("request_id") UUID requestId,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("sender_character_id") Long senderCharacterId,
            @JsonProperty("sender_character_name") String senderCharacterName,
            boolean deleted) { }

    public record CreateConversationRequest(
            @JsonProperty("story_id") Long storyId,
            @JsonProperty("character_id") Long characterId,
            @JsonProperty("chapter_id") Long chapterId) { }

    public record SendMessageRequest(
            String message,
            @JsonProperty("client_message_id") String clientMessageId) { }

    public record SendMessageResponse(
            @JsonProperty("request_id") UUID requestId,
            MessageView userMessage,
            MessageView assistantMessage) { }

    public record CharacterListResponse(List<CharacterOption> characters) { }

    public record ConversationListResponse(List<ConversationSummary> conversations) { }

    public record AddFriendRequest(
            @JsonProperty("story_id") Long storyId,
            @JsonProperty("character_id") Long characterId,
            @JsonProperty("chapter_id") Long chapterId) { }

    public record CreateGroupRequest(
            @JsonProperty("story_id") Long storyId,
            @JsonProperty("chapter_id") Long chapterId,
            @JsonProperty("character_ids") List<Long> characterIds,
            String title) { }
}
