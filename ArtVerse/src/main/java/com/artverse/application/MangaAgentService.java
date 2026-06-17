package com.artverse.application;

import com.artverse.agents.AgentMessage;
import com.artverse.agents.AgentRunRequest;
import com.artverse.agents.AgentTaskType;
import com.artverse.agents.HarnessAgentGateway;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaAgentMessage;
import com.artverse.domain.MessageRole;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaAgentMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MangaAgentService {

    private static final int HISTORY_LIMIT_FOR_AGENT = 20;

    private final ChapterRepository chapterRepository;
    private final MangaAgentMessageRepository mangaAgentMessageRepository;
    private final HarnessAgentGateway harnessAgentGateway;
    private final ApiKeyService apiKeyService;

    @Transactional(readOnly = true)
    public List<MangaAgentMessage> listMessages(Long chapterId, User user) {
        ensureChapterVisible(chapterId, user);
        return mangaAgentMessageRepository.findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);
    }

    public RunResult run(Long chapterId, String message, UUID requestId, User user) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "Message cannot be empty");
        }

        UUID effectiveRequestId = requestId == null ? UUID.randomUUID() : requestId;
        var cached = mangaAgentMessageRepository
                .findByUserIdAndRequestIdAndRole(user.getId(), effectiveRequestId, MessageRole.ASSISTANT);
        if (cached.isPresent()) {
            return new RunResult(cached.get().getContent(), effectiveRequestId);
        }

        Chapter chapter = ensureChapterVisible(chapterId, user);
        List<MangaAgentMessage> history = mangaAgentMessageRepository
                .findByUserIdAndChapterIdOrderByCreatedAtAsc(user.getId(), chapterId);

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage("system", buildSystemPrompt(chapter, user)));
        history.stream()
                .filter(item -> item.getRole() == MessageRole.USER || item.getRole() == MessageRole.ASSISTANT)
                .skip(Math.max(0, history.size() - HISTORY_LIMIT_FOR_AGENT))
                .forEach(item -> messages.add(new AgentMessage(item.getRole().name().toLowerCase(), item.getContent())));
        messages.add(new AgentMessage("user", message));

        String deepseekApiKey = apiKeyService.getDecryptedKey(user, "deepseek");
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            throw new BusinessException(400, "请先在设置中配置 DeepSeek API Key 后再使用漫画智能体");
        }

        saveMessage(user, chapter, MessageRole.USER, message, effectiveRequestId);

        AgentRunRequest request = new AgentRunRequest(
                String.valueOf(user.getId()),
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.MANGA_DIRECTOR,
                messages,
                Map.of(
                        "coze_api_key", nullToBlank(apiKeyService.getDecryptedKey(user, "coze"))
                ),
                deepseekApiKey
        );

        try {
            String result = harnessAgentGateway.generateText(request).block();
            if (result == null || result.isBlank()) {
                throw new BusinessException(502, "Agent returned empty response");
            }
            saveMessage(user, chapter, MessageRole.ASSISTANT, result, effectiveRequestId);
            return new RunResult(result, effectiveRequestId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "Agent service failed: " + (e.getMessage() == null ? "unknown error" : e.getMessage()));
        }
    }

    private Chapter ensureChapterVisible(Long chapterId, User user) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        if (chapter.getStory().getUser() != null && !chapter.getStory().getUser().getId().equals(user.getId())) {
            throw new BusinessException(403, "Forbidden");
        }
        return chapter;
    }

    @Transactional
    protected void saveMessage(User user, Chapter chapter, MessageRole role, String content, UUID requestId) {
        if (mangaAgentMessageRepository.findByUserIdAndRequestIdAndRole(user.getId(), requestId, role).isPresent()) {
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

    private String buildSystemPrompt(Chapter chapter, User user) {
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
                Current display chapter name: 第%s话

                The selected story and chapter in the left workspace are the only trusted target context.
                If the user mentions another chapter, do not silently switch. Ask the user to switch the workspace first.
                Never use any database id as a visible chapter number. When speaking to the user, only use the current display chapter name.

                You can use tools to inspect chapter context, generate storyboard scenes, and save edited storyboard scenes.
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

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record RunResult(String reply, UUID requestId) {
    }
}
