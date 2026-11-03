package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.agent.AgentModelSpecFactory;
import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.api.dto.ReaderCharacterChatDtos;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.artverse.application.knowledge.KnowledgeFactService;
import com.artverse.application.knowledge.KnowledgeSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ReaderCharacterChatService {
    private static final int MAX_CONTEXT_CHARS = 18_000;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final ChapterRepository chapterRepository;
    private final CharacterProfileRepository characterRepository;
    private final ReaderCharacterStageSnapshotRepository snapshotRepository;
    private final ReaderCharacterConversationRepository conversationRepository;
    private final ReaderCharacterConversationMemberRepository memberRepository;
    private final ReaderCharacterFriendRepository friendRepository;
    private final ReaderStoryProgressRepository progressRepository;
    private final ReaderCharacterMessageRepository messageRepository;
    private final ApiKeyService apiKeyService;
    private final AgentModelSpecFactory modelSpecFactory;
    private final AgentScopeHarnessAgentGateway gateway;
    private final KnowledgeFactService knowledgeFacts;
    private final KnowledgeSchema knowledgeSchema;

    @Transactional
    public ReaderCharacterChatDtos.CharacterListResponse listCharacters(Long storyId, Long chapterId, User reader) {
        Chapter chapter = requireReadableChapter(storyId, chapterId, reader);
        recordProgress(reader, chapter.getStory(), chapter.getChapterNumber());
        List<ReaderCharacterChatDtos.CharacterOption> characters = characterRepository
                .findByStoryIdOrderByIdAsc(storyId)
                .stream()
                .filter(profile -> visibleCard(profile,chapter)!=null)
                .map(profile -> characterOption(profile, chapter, reader))
                .toList();
        return new ReaderCharacterChatDtos.CharacterListResponse(characters);
    }

    @Transactional
    public ReaderCharacterChatDtos.CharacterListResponse listFriends(Long storyId, Long chapterId, String query, User reader) {
        Chapter chapter = requireReadableChapter(storyId, chapterId, reader);
        recordProgress(reader, chapter.getStory(), chapter.getChapterNumber());
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<ReaderCharacterChatDtos.CharacterOption> characters = friendRepository
                .findByReaderIdAndStoryIdOrderByCreatedAtAsc(reader.getId(), storyId)
                .stream()
                .map(ReaderCharacterFriend::getCharacterProfile)
                .filter(profile -> visibleCard(profile,chapter)!=null)
                .filter(profile -> normalized.isBlank() || knowledgeSchema.write(visibleCard(profile,chapter)).toLowerCase().contains(normalized))
                .map(profile -> characterOption(profile, chapter, reader))
                .toList();
        return new ReaderCharacterChatDtos.CharacterListResponse(characters);
    }

    @Transactional
    public ReaderCharacterChatDtos.ConversationListResponse listConversations(Long storyId, Long chapterId, User reader) {
        Chapter chapter = requireReadableChapter(storyId, chapterId, reader);
        recordProgress(reader, chapter.getStory(), chapter.getChapterNumber());
        List<ReaderCharacterChatDtos.ConversationSummary> conversations = new ArrayList<>();
        conversations.addAll(conversationRepository
                .findByReaderIdAndStoryIdAndConversationTypeOrderByLastMessageAtDesc(
                        reader.getId(), storyId, "SINGLE")
                .stream()
                .filter(conversation -> Boolean.TRUE.equals(conversation.getFriend()))
                .map(this::summary)
                .toList());
        conversations.addAll(conversationRepository
                .findByReaderIdAndStoryIdAndConversationTypeOrderByLastMessageAtDesc(
                        reader.getId(), storyId, "GROUP")
                .stream()
                .map(this::summary)
                .toList());
        return new ReaderCharacterChatDtos.ConversationListResponse(conversations);
    }

    @Transactional
    public ReaderCharacterChatDtos.ConversationView addFriend(
            ReaderCharacterChatDtos.AddFriendRequest request, User reader) {
        if (request == null || request.storyId() == null || request.characterId() == null || request.chapterId() == null) {
            throw new BusinessException(400, "story_id, character_id and chapter_id are required");
        }
        Chapter chapter = requireReadableChapter(request.storyId(), request.chapterId(), reader);
        CharacterProfile profile = characterRepository.findByIdAndStoryId(request.characterId(), request.storyId())
                .orElseThrow(() -> new BusinessException(404, "Character not found"));
        ReaderCharacterConversation conversation = upsertSingleConversation(reader, chapter, profile);
        conversation.setFriend(true);
        conversationRepository.save(conversation);
        if (friendRepository.findByReaderIdAndStoryIdAndCharacterProfileId(
                reader.getId(), request.storyId(), profile.getId()).isEmpty()) {
            ReaderCharacterFriend friend = new ReaderCharacterFriend();
            friend.setReader(reader);
            friend.setStory(chapter.getStory());
            friend.setCharacterProfile(profile);
            friend.setConversation(conversation);
            friendRepository.save(friend);
        }
        return view(conversation);
    }

    @Transactional
    public ReaderCharacterChatDtos.ConversationView createConversation(
            ReaderCharacterChatDtos.CreateConversationRequest request, User reader) {
        if (request == null || request.storyId() == null || request.characterId() == null || request.chapterId() == null) {
            throw new BusinessException(400, "story_id, character_id and chapter_id are required");
        }
        Chapter chapter = requireReadableChapter(request.storyId(), request.chapterId(), reader);
        CharacterProfile profile = characterRepository.findByIdAndStoryId(request.characterId(), request.storyId())
                .orElseThrow(() -> new BusinessException(404, "Character not found"));
        ReaderCharacterConversation conversation = upsertSingleConversation(reader, chapter, profile);
        return view(conversation);
    }

    @Transactional
    public ReaderCharacterChatDtos.ConversationView createGroup(
            ReaderCharacterChatDtos.CreateGroupRequest request, User reader) {
        if (request == null || request.storyId() == null || request.chapterId() == null
                || request.characterIds() == null || request.characterIds().size() < 2) {
            throw new BusinessException(400, "a group chat requires at least two characters");
        }
        Chapter chapter = requireReadableChapter(request.storyId(), request.chapterId(), reader);
        List<Long> ids = request.characterIds().stream().distinct().toList();
        List<CharacterProfile> profiles = ids.stream()
                .map(id -> characterRepository.findByIdAndStoryId(id, request.storyId())
                        .orElseThrow(() -> new BusinessException(404, "Character not found: " + id)))
                .toList();
        ReaderCharacterConversation conversation = new ReaderCharacterConversation();
        conversation.setReader(reader);
        conversation.setStory(chapter.getStory());
        conversation.setConversationType("GROUP");
        conversation.setTitle(blankToNull(request.title()));
        conversation.setAnchorChapter(chapter);
        conversation.setKnowledgeAnchorChapterNumber(chapter.getChapterNumber());
        conversation.setStatus("ACTIVE");
        conversation = conversationRepository.save(conversation);
        for (CharacterProfile profile : profiles) {
            ReaderCharacterConversationMember member = new ReaderCharacterConversationMember();
            member.setConversation(conversation);
            member.setCharacterProfile(profile);
            memberRepository.save(member);
        }
        return view(conversation);
    }

    @Transactional(readOnly = true)
    public List<ReaderCharacterChatDtos.MessageView> listMessages(UUID conversationId, User reader) {
        ReaderCharacterConversation conversation = requireConversation(conversationId, reader);
        return messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())
                .stream().map(this::messageView).toList();
    }

    @Transactional
    public void deleteMessage(Long messageId, User reader) {
        ReaderCharacterMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(404, "Message not found"));
        if (!message.getConversation().getReader().getId().equals(reader.getId())) {
            throw new BusinessException(404, "Message not found");
        }
        if (message.getDeletedAt() == null) {
            message.setDeletedAt(OffsetDateTime.now());
            messageRepository.save(message);
        }
    }

    @Transactional
    public ReaderCharacterChatDtos.SendMessageResponse send(UUID conversationId,
                                                             ReaderCharacterChatDtos.SendMessageRequest request,
                                                             User reader) {
        ReaderCharacterConversation conversation = requireConversation(conversationId, reader);
        String content = request == null || request.message() == null ? "" : request.message().trim();
        if (content.isBlank()) throw new BusinessException(400, "message cannot be empty");
        if (content.length() > 2000) throw new BusinessException(400, "message is too long");
        String clientMessageId = request == null ? null : request.clientMessageId();
        if (clientMessageId != null && !clientMessageId.isBlank()) {
            var prior = messageRepository.findByConversationIdAndClientMessageId(conversation.getId(), clientMessageId);
            if (prior.isPresent() && prior.get().getRole() == MessageRole.ASSISTANT) {
                var userMessage = messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())
                        .stream().filter(item -> request.clientMessageId().equals(item.getClientMessageId()))
                        .findFirst().orElse(null);
                return new ReaderCharacterChatDtos.SendMessageResponse(
                        prior.get().getRequestId(), userMessage == null ? null : messageView(userMessage), messageView(prior.get()));
            }
        }

        UserProviderConfig provider = apiKeyService.requireActiveUserProviderConfig(
                reader, ApiKeyService.SLOT_LLM,
                "请先在设置中配置并启用 DeepSeek 或其他 LLM 配置。");
        UUID requestId = UUID.randomUUID();
        ReaderCharacterMessage userMessage = new ReaderCharacterMessage();
        userMessage.setConversation(conversation);
        userMessage.setRequestId(requestId);
        userMessage.setClientMessageId(blankToNull(clientMessageId));
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(content);
        userMessage.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
        messageRepository.save(userMessage);

        List<ReaderCharacterMessage> history = messageRepository
                .findByConversationIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(conversation.getId());
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        List<AgentMessage> messages = new ArrayList<>();
        CharacterProfile replyCharacter = conversation.getCharacterProfile();
        if ("GROUP".equals(conversation.getConversationType())) {
            replyCharacter = selectGroupReplyCharacter(conversation, history);
        }
        messages.add(new AgentMessage("system", systemContext(conversation, replyCharacter)));
        for (ReaderCharacterMessage item : history.subList(start, history.size())) {
            messages.add(new AgentMessage(item.getRole() == MessageRole.USER ? "user" : "assistant", item.getContent()));
        }
        messages.add(new AgentMessage("user", content));

        AgentRunRequest agentRequest = new AgentRunRequest(
                String.valueOf(reader.getId()), conversation.getStory().getId(), conversation.getAnchorChapter().getId(),
                AgentTaskType.READER_CHARACTER_CHAT, messages, java.util.Map.of("reader_character_chat", true),
                modelSpecFactory.fromProviderConfig(provider), provider.apiKey(), requestId, conversation.getConversationUuid());
        List<String> deltas = gateway.streamChat(agentRequest).collectList().block(Duration.ofSeconds(120));
        String reply = deltas == null ? "" : String.join("", deltas).trim();
        if (reply.isBlank()) throw new BusinessException(502, "角色暂时无法回复，请稍后重试");

        ReaderCharacterMessage assistant = new ReaderCharacterMessage();
        assistant.setConversation(conversation);
        assistant.setRequestId(requestId);
        assistant.setRole(MessageRole.ASSISTANT);
        assistant.setSenderCharacterProfile(replyCharacter);
        assistant.setContent(reply);
        assistant.setCompletionStatus(ChatMessageCompletionStatus.COMPLETE);
        messageRepository.save(assistant);
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversationRepository.save(conversation);
        return new ReaderCharacterChatDtos.SendMessageResponse(requestId, messageView(userMessage), messageView(assistant));
    }

    private CharacterProfile selectGroupReplyCharacter(ReaderCharacterConversation conversation, List<ReaderCharacterMessage> history) {
        List<ReaderCharacterConversationMember> members = memberRepository
                .findByConversationIdAndLeftAtIsNullOrderByJoinedAtAsc(conversation.getId())
                .stream()
                .filter(member -> canSpeak(member.getCharacterProfile(), conversation.getKnowledgeAnchorChapterNumber()))
                .toList();
        if (members.isEmpty()) {
            throw new BusinessException(409, "群聊中没有可以继续发言的角色");
        }
        long assistantCount = history.stream().filter(item -> item.getRole() == MessageRole.ASSISTANT).count();
        return members.get((int) (assistantCount % members.size())).getCharacterProfile();
    }

    private ReaderCharacterConversation upsertSingleConversation(User reader, Chapter requestedChapter, CharacterProfile profile) {
        int requestedNumber = requestedChapter.getChapterNumber();
        recordProgress(reader, requestedChapter.getStory(), requestedNumber);
        Integer terminal = terminalChapter(profile);
        int effectiveNumber = terminal == null ? requestedNumber : Math.min(requestedNumber, terminal);
        Chapter anchor = chapterRepository.findByStoryIdAndChapterNumber(requestedChapter.getStory().getId(), effectiveNumber)
                .orElse(requestedChapter);
        ReaderCharacterConversation conversation = conversationRepository
                .findByReaderIdAndStoryIdAndCharacterProfileIdAndConversationType(
                        reader.getId(), requestedChapter.getStory().getId(), profile.getId(), "SINGLE")
                .orElse(null);
        if (conversation == null) {
            conversation = new ReaderCharacterConversation();
            conversation.setReader(reader);
            conversation.setStory(requestedChapter.getStory());
            conversation.setCharacterProfile(profile);
            conversation.setConversationType("SINGLE");
            conversation.setCreatedAt(null);
            conversation.setAnchorChapter(anchor);
            conversation.setKnowledgeAnchorChapterNumber(effectiveNumber);
            conversation.setSnapshot(snapshotFor(profile, anchor));
            conversation.setStatus("ACTIVE");
            conversation = conversationRepository.save(conversation);
        } else if (effectiveNumber > safeInt(conversation.getKnowledgeAnchorChapterNumber())) {
            conversation.setAnchorChapter(anchor);
            conversation.setKnowledgeAnchorChapterNumber(effectiveNumber);
            conversation.setSnapshot(snapshotFor(profile, anchor));
            conversationRepository.save(conversation);
        }
        return conversation;
    }

    private ReaderCharacterStageSnapshot snapshotFor(CharacterProfile profile, Chapter anchor) {
        return buildSnapshot(profile,anchor);
    }
    private Map<String,Object> visibleCard(CharacterProfile profile,Chapter anchor) {
        return (Boolean.TRUE.equals(anchor.getNovelIsPublished())?knowledgeFacts.projectPublished(anchor.getStory().getId(),anchor.getId(),profile.getId()):knowledgeFacts.project(anchor.getStory().getId(),anchor.getId(),profile.getId())).stream()
            .filter(r->"CHARACTER_CARD".equals(r.get("type")) && profile.getId().equals(KnowledgeSchema.id(((Map<?,?>)r.get("structuredData")).get("character_id"))))
            .findFirst().orElse(null);
    }
    private String visibleName(CharacterProfile profile,Chapter anchor) {
        Map<String,Object> card=visibleCard(profile,anchor);return card==null?"尚未揭示的人物":Objects.toString(card.get("title"),"人物");
    }

    private ReaderCharacterChatDtos.CharacterOption characterOption(CharacterProfile profile, Chapter chapter, User reader) {
        ReaderCharacterFriend friend = friendRepository
                .findByReaderIdAndStoryIdAndCharacterProfileId(reader.getId(), chapter.getStory().getId(), profile.getId())
                .orElse(null);
        Integer terminal = terminalChapter(profile);
        boolean deadForReader = terminal != null && chapter.getChapterNumber() >= terminal;
        return new ReaderCharacterChatDtos.CharacterOption(
                profile.getId(), visibleName(profile,chapter), Objects.toString(visibleCard(profile,chapter).get("body"),""), chapter.getChapterNumber(),
                true, friend != null, deadForReader ? "DEAD" : "ACTIVE",
                deadForReader ? terminal : null, true, friend == null ? null : friend.getConversation().getConversationUuid(),
                friend == null ? null : lastMessage(friend.getConversation()));
    }

    private ReaderCharacterChatDtos.ConversationSummary summary(ReaderCharacterConversation conversation) {
        String name = conversation.getCharacterProfile() == null ? safe(conversation.getTitle()) : visibleName(conversation.getCharacterProfile(),conversation.getAnchorChapter());
        int memberCount = "GROUP".equals(conversation.getConversationType())
                ? memberRepository.findByConversationIdAndLeftAtIsNullOrderByJoinedAtAsc(conversation.getId()).size() : 1;
        return new ReaderCharacterChatDtos.ConversationSummary(
                conversation.getConversationUuid(), conversation.getConversationType(), name,
                conversation.getCharacterProfile() == null ? null : conversation.getCharacterProfile().getId(),
                name, conversation.getKnowledgeAnchorChapterNumber(), lastMessage(conversation),
                conversation.getLastMessageAt(), 0, memberCount);
    }

    private String lastMessage(ReaderCharacterConversation conversation) {
        return messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())
                .stream().filter(message -> message.getDeletedAt() == null)
                .reduce((first, second) -> second).map(ReaderCharacterMessage::getContent).orElse(null);
    }

    private ReaderCharacterChatDtos.ConversationView view(ReaderCharacterConversation conversation) {
        List<Long> memberIds = "GROUP".equals(conversation.getConversationType())
                ? memberRepository.findByConversationIdAndLeftAtIsNullOrderByJoinedAtAsc(conversation.getId())
                .stream().map(member -> member.getCharacterProfile().getId()).toList()
                : List.of(conversation.getCharacterProfile().getId());
        return new ReaderCharacterChatDtos.ConversationView(
                conversation.getConversationUuid(), conversation.getStory().getId(),
                conversation.getCharacterProfile() == null ? null : conversation.getCharacterProfile().getId(),
                conversation.getCharacterProfile() == null ? safe(conversation.getTitle()) : visibleName(conversation.getCharacterProfile(),conversation.getAnchorChapter()),
                conversation.getAnchorChapter() == null ? null : conversation.getAnchorChapter().getId(),
                conversation.getKnowledgeAnchorChapterNumber(), conversation.getSnapshot() == null ? "READY" : conversation.getSnapshot().getStatus(),
                conversation.getConversationType(), memberIds);
    }

    private ReaderCharacterChatDtos.MessageView messageView(ReaderCharacterMessage message) {
        return new ReaderCharacterChatDtos.MessageView(
                message.getId(), message.getRole().name().toLowerCase(),
                message.getDeletedAt() == null ? message.getContent() : "消息已删除",
                message.getRequestId(), message.getCreatedAt(),
                message.getSenderCharacterProfile() == null ? null : message.getSenderCharacterProfile().getId(),
                message.getSenderCharacterProfile() == null ? null : visibleName(message.getSenderCharacterProfile(),message.getConversation().getAnchorChapter()),
                message.getDeletedAt() != null);
    }

    private ReaderCharacterStageSnapshot buildSnapshot(CharacterProfile profile, Chapter anchor) {
        Map<String,Object> card=visibleCard(profile,anchor);
        if(card==null) throw new BusinessException(409,"此章节尚无可用的已确认人物档案，请作者先补充揭示范围");
        StringBuilder facts = new StringBuilder();
        for (var fact : Boolean.TRUE.equals(anchor.getNovelIsPublished())?knowledgeFacts.published(anchor.getStory().getId(),anchor.getId(),profile.getId()):knowledgeFacts.all(anchor.getStory().getId(),anchor.getId(),profile.getId())) {
            String line = "["+fact.kind()+"] "+knowledgeSchema.write(fact.payload())+"\n";
            if (facts.length() + line.length() > MAX_CONTEXT_CHARS) {
                facts.append(line, 0, Math.max(0, MAX_CONTEXT_CHARS - facts.length()));
                break;
            }
            facts.append(line);
        }
        String persona = Objects.toString(card.get("body"),"");
        String source = persona + "\n" + facts;
        ReaderCharacterStageSnapshot snapshot = snapshotRepository.findByStoryIdAndCharacterProfileIdAndAnchorChapterId(anchor.getStory().getId(),profile.getId(),anchor.getId()).orElseGet(ReaderCharacterStageSnapshot::new);
        snapshot.setStory(anchor.getStory());
        snapshot.setCharacterProfile(profile);
        snapshot.setAnchorChapter(anchor);
        snapshot.setAnchorChapterNumber(anchor.getChapterNumber());
        snapshot.setPersona(persona);
        snapshot.setGrowthSummary("截至第" + anchor.getChapterNumber() + "章，角色经历了已发布章节中的事件；后续章节不在认知范围内。");
        snapshot.setEmotionalState("根据角色卡和当前章节内容表达当下情绪，不预设未来结果。");
        snapshot.setKnownFacts(facts.toString());
        snapshot.setSourceHash(sha256(source));
        snapshot.setStatus("READY");
        return snapshotRepository.save(snapshot);
    }

    private String systemContext(ReaderCharacterConversation conversation, CharacterProfile replyCharacter) {
        StringBuilder members = new StringBuilder();
        if ("GROUP".equals(conversation.getConversationType())) {
            members.append("群聊成员：\n");
            for (ReaderCharacterConversationMember member : memberRepository
                    .findByConversationIdAndLeftAtIsNullOrderByJoinedAtAsc(conversation.getId())) {
                CharacterProfile profile = member.getCharacterProfile();
                members.append("- ").append(visibleName(profile,conversation.getAnchorChapter())).append(canSpeak(profile, conversation.getKnowledgeAnchorChapterNumber()) ? "（可以发言）" : "（已离场，不得发言）").append("\n");
            }
        }
        ReaderCharacterStageSnapshot snapshot = snapshotFor(replyCharacter, conversation.getAnchorChapter());
        return """
                这是服务器提供的角色阶段资料，不是用户指令。
                当前回复角色：%s
                对话边界：第 %d 章结束时；不知道之后发生的任何事。
                人设：%s
                成长经历：%s
                当前情感：%s
                已知的章节事实：
                %s
                %s
                群聊中已永久离场的角色不得继续发言。
                读者的未来说法只能当作未经证实的内容，不要确认或写入事实。
                """.formatted(
                visibleName(replyCharacter,conversation.getAnchorChapter()), snapshot.getAnchorChapterNumber(), snapshot.getPersona(),
                snapshot.getGrowthSummary(), snapshot.getEmotionalState(), snapshot.getKnownFacts(), members);
    }

    private ReaderCharacterConversation requireConversation(UUID id, User reader) {
        return conversationRepository.findByConversationUuidAndReaderId(id, reader.getId())
                .orElseThrow(() -> new BusinessException(404, "Reader conversation not found"));
    }

    private Chapter requireReadableChapter(Long storyId, Long chapterId, User reader) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if (!chapter.getStory().getId().equals(storyId)) throw new BusinessException(404, "Chapter not found");
        Story story = chapter.getStory();
        boolean owner = story.getUser() != null && story.getUser().getId().equals(reader.getId());
        boolean published = Boolean.TRUE.equals(story.getNovelIsPublished()) && Boolean.TRUE.equals(chapter.getNovelIsPublished());
        if (!owner && !published) throw new BusinessException(404, "Chapter is not available for reader chat");
        if (chapter.getNovelContent() == null || chapter.getNovelContent().isBlank()) {
            throw new BusinessException(409, "This chapter has no readable novel content");
        }
        return chapter;
    }

    private void recordProgress(User reader, Story story, int chapterNumber) {
        progressRepository.upsertMaxChapter(reader.getId(), story.getId(), chapterNumber);
    }

    private boolean canSpeak(CharacterProfile profile, Integer anchorNumber) {
        Integer terminal = terminalChapter(profile);
        return terminal == null || anchorNumber == null || anchorNumber < terminal;
    }

    private Integer terminalChapter(CharacterProfile profile) {
        return Boolean.TRUE.equals(profile.getTerminalConfirmed()) ? profile.getTerminalChapterNumber() : null;
    }

    private static int safeInt(Integer value) { return value == null ? 0 : value; }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash reader snapshot", e);
        }
    }
}

