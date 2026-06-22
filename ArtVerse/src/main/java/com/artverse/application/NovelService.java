package com.artverse.application;

import com.artverse.agents.*;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NovelService {

    private final ChapterRepository chapterRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MangaImageRepository mangaImageRepository;
    private final HarnessAgentGateway harnessAgentGateway;
    private final AgentModelSpecFactory agentModelSpecFactory;
    private final ArtVerseProperties properties;

    @Transactional(readOnly = true)
    public String generateNovel(Long chapterId) {
        return generateNovel(chapterId, null, null);
    }

    @Transactional(readOnly = true)
    public String generateNovel(Long chapterId, Long userId, String userApiKey) {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        List<ChatMessage> messages = chatMessageRepository.findByChapterIdOrderByCreatedAtAsc(chapterId);
        if (messages.isEmpty()) {
            throw new BusinessException(400, "No chat messages to generate novel from");
        }

        List<AgentMessage> agentMessages = new ArrayList<>();
        agentMessages.add(new AgentMessage("system", buildNovelSystemPrompt()));
        for (ChatMessage m : messages) {
            agentMessages.add(new AgentMessage(m.getRole().name().toLowerCase(), m.getContent()));
        }

        AgentRunRequest request = new AgentRunRequest(
                userId == null ? "default" : String.valueOf(userId),
                chapter.getStory().getId(),
                chapterId,
                AgentTaskType.NOVEL,
                agentMessages,
                Map.of(),
                agentModelSpecFactory.deepSeek(userApiKey),
                userApiKey
        );

        String novelContent;
        try {
            novelContent = harnessAgentGateway.generateText(request).block();
        } catch (Exception e) {
            throw new BusinessException(502, "AI 服务不可用: " + e.getMessage());
        }
        if (novelContent == null || novelContent.isBlank()) {
            throw new BusinessException(502, "AI returned empty novel content");
        }

        chapter.setNovelContent(novelContent);
        chapterRepository.save(chapter);

        return novelContent;
    }

    @Transactional
    public Chapter importNovel(Long chapterId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "Content cannot be empty");
        }
        if (content.length() > properties.getImportConfig().getMaxNovelChars()) {
            throw new BusinessException(400, "Content exceeds max length of " + properties.getImportConfig().getMaxNovelChars());
        }

        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));

        // Clear existing scenes
        chapter.setScenesText(null);

        // Delete existing chat messages to replace with imported content
        chatMessageRepository.deleteByChapterId(chapterId);

        // Save imported content as user message
        ChatMessage userMsg = new ChatMessage();
        userMsg.setChapter(chapter);
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(content);
        chatMessageRepository.save(userMsg);

        chapter.setNovelContent(content);
        chapter.setContentSource(ContentSource.IMPORT);
        return chapterRepository.save(chapter);
    }

    private String buildNovelSystemPrompt() {
        return """
                你是一位专业的中文网络小说作家。请根据用户的对话内容，整理成完整的章节小说正文。

                要求：
                - 中文网络小说风格
                - 目标 4000-6000 中文字，不低于 3500 字
                - 包含 3-5 个完整场景
                - 强化环境描写、对话、心理活动、微表情、肢体动作
                - 直接输出正文，不输出解释或字数统计
                """;
    }
}
