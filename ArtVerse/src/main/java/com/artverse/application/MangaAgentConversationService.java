package com.artverse.application;

import com.artverse.agent.AgentMessage;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentConversationStatus;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import com.artverse.persistence.MangaAgentMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MangaAgentConversationService {

    private static final int HISTORY_LIMIT_FOR_AGENT = 20;

    private final MangaAgentConversationRepository conversationRepository;
    private final MangaAgentMessageRepository mangaAgentMessageRepository;
    private final ChapterAccessService chapterAccessService;

    // 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓 conversation management 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Transactional(readOnly = true)
    public List<MangaAgentConversation> listConversations(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findByUserIdAndChapterIdOrderByUpdatedAtDesc(user.getId(), chapterId);
    }

    @Transactional
    public MangaAgentConversation activeOrCreate(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
                        user.getId(),
                        chapterId,
                        MangaAgentConversationStatus.ACTIVE
                )
                .orElseGet(() -> conversationRepository.save(newConversation(user, chapter)));
    }

    @Transactional
    public MangaAgentConversation createConversation(Long chapterId, User user) {
        Chapter chapter = chapterAccessService.requireVisible(chapterId, user.getId());
        conversationRepository.findFirstByUserIdAndChapterIdAndStatusOrderByUpdatedAtDesc(
                user.getId(),
                chapterId,
                MangaAgentConversationStatus.ACTIVE
        ).ifPresent(this::archiveConversation);
        return conversationRepository.save(newConversation(user, chapter));
    }

    @Transactional(readOnly = true)
    public MangaAgentConversation requireConversation(Long chapterId, User user, UUID conversationId) {
        if (conversationId == null) {
            throw new BusinessException(400, "conversationId is required");
        }
        chapterAccessService.requireVisible(chapterId, user.getId());
        return conversationRepository.findByUserIdAndChapterIdAndConversationUuid(user.getId(), chapterId, conversationId)
                .orElseThrow(() -> new BusinessException(404, "Agent conversation not found"));
    }

    @Transactional
    public MangaAgentConversation archiveConversation(Long chapterId, User user, UUID conversationId) {
        MangaAgentConversation conversation = requireConversation(chapterId, user, conversationId);
        archiveConversation(conversation);
        return conversationRepository.save(conversation);
    }

    @Transactional
    public void deleteConversation(Long chapterId, User user, UUID conversationId) {
        MangaAgentConversation conversation = requireConversation(chapterId, user, conversationId);
        conversationRepository.delete(conversation);
    }

    // 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓 message management 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        chapterAccessService.requireVisible(chapterId, user.getId());
        return mangaAgentMessageRepository.findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);
    }

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(MangaAgentConversation conversation) {
        return mangaAgentMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentMessage> findAssistantReply(Long userId, Long chapterId, UUID requestId) {
        return mangaAgentMessageRepository.findByUserIdAndChapterIdAndRequestIdAndRole(
                userId, chapterId, requestId, MessageRole.ASSISTANT);
    }

    @Transactional(readOnly = true)
    public Optional<MangaAgentMessage> findAssistantReply(MangaAgentConversation conversation, UUID requestId) {
        return mangaAgentMessageRepository.findByConversationIdAndRequestIdAndRole(
                conversation.getId(), requestId, MessageRole.ASSISTANT);
    }

    public List<AgentMessage> buildMessages(Chapter chapter, User user, List<MangaAgentMessage> history,
                                            String currentMessage, UUID currentRequestId) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage("system", buildSystemPrompt(chapter, user)));
        visibleHistory(history, currentRequestId).stream()
                .forEach(item -> messages.add(new AgentMessage(item.getRole().name().toLowerCase(), item.getContent())));
        messages.add(new AgentMessage("user", currentMessage));
        return messages;
    }

    @Transactional
    public void saveMessage(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        if (mangaAgentMessageRepository.findByUserIdAndChapterIdAndRequestIdAndRole(
                user.getId(), chapter.getId(), requestId, role).isPresent()) {
            return;
        }
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(user);
        message.setStory(chapter.getStory());
        message.setChapter(chapter);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        mangaAgentMessageRepository.save(message);
    }

    @Transactional
    public void saveMessage(MangaAgentConversation conversation, MessageRole role, String content, UUID requestId) {
        if (mangaAgentMessageRepository.findByConversationIdAndRequestIdAndRole(
                conversation.getId(), requestId, role).isPresent()) {
            return;
        }
        MangaAgentMessage message = new MangaAgentMessage();
        message.setUser(conversation.getUser());
        message.setStory(conversation.getStory());
        message.setChapter(conversation.getChapter());
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        message.setRequestId(requestId);
        mangaAgentMessageRepository.save(message);
    }

    @Transactional
    public void saveFailureMessage(User user, Chapter chapter, String error, UUID requestId) {
        saveMessage(user, chapter, MessageRole.SYSTEM, failureContent(error), requestId);
    }

    @Transactional
    public void saveFailureMessage(MangaAgentConversation conversation, String error, UUID requestId) {
        saveMessage(conversation, MessageRole.SYSTEM, failureContent(error), requestId);
    }

    public String resumeMessage(String originalInput, AgentUserInputRequest waiting, String answer) {
        String selected = answer == null || answer.isBlank() ? "Continue with default" : answer.trim();
        String question = waiting == null ? "" : waiting.question();
        return """
                Continue from the previously suspended operation.

                Original input: %s
                Question to resolve: %s
                User answer: %s

                Resume execution with the confirmed approach.
                """.formatted(originalInput, question, selected);
    }

    public Map<String, Object> fallbackAfterToolSuccess(MangaAgentConversation conversation, UUID requestId,
                                                        AgentRunToolStatus.RunState toolState, String error) {
        AgentRunToolStatus.ToolEvent event = toolState.lastSuccessfulMutatingEvent();
        String fallbackMessage = fallbackMessage(event);
        saveMessage(conversation, MessageRole.ASSISTANT, fallbackMessage, requestId);
        saveFailureMessage(conversation, error, requestId);
        return Map.of(
                "reply", fallbackMessage,
                "agent_final_response_degraded", true
        );
    }

    // 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓 private helpers 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    private MangaAgentConversation newConversation(User user, Chapter chapter) {
        MangaAgentConversation conversation = new MangaAgentConversation();
        conversation.setUser(user);
        conversation.setStory(chapter.getStory());
        conversation.setChapter(chapter);
        conversation.setTitle("New chat");
        conversation.setStatus(MangaAgentConversationStatus.ACTIVE);
        return conversation;
    }

    private void archiveConversation(MangaAgentConversation conversation) {
        if (conversation.getStatus() == MangaAgentConversationStatus.ARCHIVED) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        conversation.setStatus(MangaAgentConversationStatus.ARCHIVED);
        conversation.setArchivedAt(now);
        conversation.setUpdatedAt(now);
    }

    private static String failureContent(String error) {
        return "[System: agent encountered an error] " + (error == null ? "unknown error" : error);
    }

    private String fallbackMessage(AgentRunToolStatus.ToolEvent event) {
        if (event == null) {
            return "Action has been saved to the current chapter, but the agent failed to produce a final response. Please refresh the storyboard and continue.";
        }
        int scenesCount = event.result().get("scenes_count") instanceof Number n ? n.intValue() : 0;
        String action = switch (event.toolName() == null ? "" : event.toolName()) {
            case "generate_storyboard" -> "Storyboard generated and saved";
            case "save_storyboard", "save_structured_storyboard" -> "Storyboard rewritten and saved";
            default -> "Changes saved";
        };
        return """
                %s to the current chapter (%d pages).
                The agent completed the key save action but the final summary response was not completed in time. You can refresh the storyboard to view results, continue refining, or click Generate Manga to proceed with image generation.
                """.formatted(action, scenesCount).trim();
    }

    private static String buildSystemPrompt(Chapter chapter, User user) {
        return """
                You are ArtVerse Manga Director, an AI workflow assistant for Chinese AI manga creation.
                Always answer in concise Chinese.
                When editing or generating storyboard scenes, write in a way that can be used directly as a manga page production script.
                Do not output poster-like single image descriptions.
                Do not use English, traditional Chinese, or mixed-language dialogue in storyboard content.
                Prefer scene rhythm, panel sequencing, character continuity, and short Chinese dialogue.

                Current user id: %s
                Current story title: %s
                Current display chapter number: %s
                Current display chapter name: Chapter %s
                The selected story and chapter in the left workspace are the only trusted target context.
                If the user mentions another chapter, do not silently switch. Ask the user to switch the workspace first.
                Never use any database id as a visible chapter number. When speaking to the user, only use the current display chapter name.

                You can use tools to inspect chapter context, generate storyboard scenes, save edited storyboard scenes, and ask the user for a decision.
                The current chapter source text is stored in chapters.novel_content and is synced into KNOWLEDGE.md before each run.
                Use get_chapter_context to inspect source content, storyboard scenes, image status, and current chapter metadata.
                Do not use shell, execute, filesystem listing, or source-code search to find story or chapter content.
                Prefer save_structured_storyboard when creating or rewriting storyboard pages: provide pages with 4-6 panels each, using fields like shot, description, dialogue, narration, and sfx.
                Use save_storyboard only when you already have a complete validated text scene list.
                Use ask_user instead of plain text questions when a decision blocks progress, such as choosing between incompatible workflow options, resolving conflicting story direction, choosing whether to overwrite existing storyboard scenes, or deciding how to handle mismatched page counts.
                Rules:
                - First inspect chapter context when the user asks about the manga workflow.
                - Confirm the current chapter in your response before taking costly actions.
                - If source content is missing, tell the user to write chat content or import novel text first.
                - If storyboard scenes are missing and the user asks to continue, generate storyboard scenes.
                - Do not directly claim that images have been generated. Image generation is a long-running SSE task handled by the existing Generate Manga action.
                - After storyboard is ready, clearly tell the user that they can click Generate Manga, or ask you to refine scenes.
                - Keep business actions explicit and summarize what changed.
                """.formatted(
                user.getId(),
                chapter.getStory().getTitle(),
                chapter.getChapterNumber(),
                chapter.getChapterNumber()
        );
    }

    private List<MangaAgentMessage> visibleHistory(List<MangaAgentMessage> history, UUID currentRequestId) {
        return history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .filter(item -> !currentRequestId.equals(item.getRequestId()))
                .skip(Math.max(0, countVisibleHistory(history, currentRequestId) - HISTORY_LIMIT_FOR_AGENT))
                .toList();
    }

    private long countVisibleHistory(List<MangaAgentMessage> history, UUID currentRequestId) {
        return history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .filter(item -> !currentRequestId.equals(item.getRequestId()))
                .count();
    }
}
