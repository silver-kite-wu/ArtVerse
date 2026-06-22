package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final MangaAgentMessageRepository mangaAgentMessageRepository;
    private final ChapterAccessService chapterAccessService;

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
        String selected = answer == null || answer.isBlank() ? "继续默认方案" : answer.trim();
        String question = waiting == null ? "" : waiting.question();
        return """
                继续之前暂停的漫画智能体任务。

                原始用户任务：
                %s

                暂停时需要用户决策：
                %s

                用户选择：
                %s

                请基于用户选择继续完成原始任务，不要重复询问同一个问题。
                """.formatted(
                originalInput == null || originalInput.isBlank() ? "继续当前漫画创作任务" : originalInput.trim(),
                question == null || question.isBlank() ? "未记录具体问题" : question.trim(),
                selected
        ).trim();
    }

    public Map<String, Object> fallbackAfterToolSuccess(User user, Chapter chapter, UUID requestId,
                                                        AgentRunToolStatus.RunState toolState, String error) {
        String reply = fallbackReply(chapter, toolState);
        saveMessage(user, chapter, MessageRole.ASSISTANT, reply, requestId);
        saveMessage(user, chapter, MessageRole.SYSTEM, fallbackFailureContent(error, toolState), requestId);
        return Map.of(
                "reply", reply,
                "agent_final_response_degraded", true
        );
    }

    public Map<String, Object> fallbackAfterToolSuccess(MangaAgentConversation conversation, UUID requestId,
                                                        AgentRunToolStatus.RunState toolState, String error) {
        String reply = fallbackReply(conversation.getChapter(), toolState);
        saveMessage(conversation, MessageRole.ASSISTANT, reply, requestId);
        saveMessage(conversation, MessageRole.SYSTEM, fallbackFailureContent(error, toolState), requestId);
        return Map.of(
                "reply", reply,
                "agent_final_response_degraded", true
        );
    }

    private String failureContent(String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "agent_run_failed");
        payload.put("message", error == null || error.isBlank() ? "unknown error" : error);
        return payload.toString();
    }

    private String fallbackFailureContent(String error, AgentRunToolStatus.RunState toolState) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "agent_run_degraded_after_tool_success");
        payload.put("message", error == null || error.isBlank() ? "unknown error" : error);
        AgentRunToolStatus.ToolEvent event = toolState.lastSuccessfulMutatingEvent();
        if (event != null) {
            payload.put("tool", event.toolName());
            payload.put("scenes_count", event.result().getOrDefault("scenes_count", ""));
        }
        return payload.toString();
    }

    private String fallbackReply(Chapter chapter, AgentRunToolStatus.RunState toolState) {
        AgentRunToolStatus.ToolEvent event = toolState.lastSuccessfulMutatingEvent();
        Object scenesCount = event == null
                ? chapter.getImageCount()
                : event.result().getOrDefault("scenes_count", chapter.getImageCount());
        String action = switch (event == null ? "" : event.toolName()) {
            case "generate_storyboard" -> "分镜已经生成并保存";
            case "save_storyboard", "save_structured_storyboard" -> "分镜已经重写并保存";
            default -> "本次修改已经保存";
        };
        return """
                %s到当前章节，共%s页。
                智能体已经完成了关键保存动作，但最终总结回复没有及时完成。你可以刷新分镜查看结果，继续让我润色，或者点击 Generate Manga 继续生成图片。
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
                Current display chapter name: 第%s章
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
