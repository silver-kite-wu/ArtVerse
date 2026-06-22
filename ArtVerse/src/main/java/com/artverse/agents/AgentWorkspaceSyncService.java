package com.artverse.agents;

import com.artverse.application.CharacterProfileService;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.MangaImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceSyncService {

    private static final int SOURCE_EXCERPT_LIMIT = 2000;
    private static final int CHARACTER_EXCERPT_LIMIT = 2000;

    private final ChapterRepository chapterRepository;
    private final MangaImageRepository mangaImageRepository;
    private final CharacterProfileService characterProfileService;
    private final AgentWorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public void syncMangaDirectorKnowledge(Long chapterId, String userId) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        Story story = chapter.getStory();
        List<MangaImage> images = mangaImageRepository.findByChapterIdOrderByImageNumberAsc(chapterId);
        Map<String, Object> profile = characterProfileService.resolveEffective(chapterId);

        String knowledge = buildKnowledge(chapter, story, images, profile);
        workspaceService.writeKnowledge(userId, story.getId(), knowledge);
    }

    String buildKnowledge(Chapter chapter, Story story, List<MangaImage> images, Map<String, Object> profile) {
        String source = chapter.novelContentOrJoinedMessages();
        String characterProfiles = String.valueOf(profile.getOrDefault("content", ""));
        String characterSource = String.valueOf(profile.getOrDefault("source", "none"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Story Knowledge\n\n");
        sb.append("## Story\n");
        appendLine(sb, "Title", story.getTitle());
        appendLine(sb, "Description", story.getDescription());
        appendLine(sb, "Manga Style", story.getMangaStyle());
        sb.append("\n");

        sb.append("## Current Chapter\n");
        appendLine(sb, "Display Name", "第" + chapter.getChapterNumber() + "话");
        appendLine(sb, "Image Count", chapter.getImageCount());
        appendLine(sb, "Color Mode", chapter.getColorMode());
        appendLine(sb, "Content Source", chapter.getContentSource());
        sb.append("\n");

        sb.append("## Source Excerpt\n");
        sb.append(excerpt(source, SOURCE_EXCERPT_LIMIT)).append("\n\n");

        sb.append("## Storyboard Status\n");
        appendLine(sb, "Scenes Count", countScenes(chapter.getScenesText()));
        appendLine(sb, "Has Storyboard", chapter.getScenesText() != null && !chapter.getScenesText().isBlank());
        sb.append("\n");

        sb.append("## Generated Images\n");
        if (images == null || images.isEmpty()) {
            sb.append("No generated images yet.\n\n");
        } else {
            for (MangaImage image : images) {
                sb.append("- Page ").append(image.getImageNumber())
                        .append(": ").append(nullToBlank(image.getImagePath()))
                        .append(image.getPrompt() == null || image.getPrompt().isBlank() ? "" : " (has prompt)")
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Character Profiles\n");
        appendLine(sb, "Source", characterSource);
        sb.append(excerpt(characterProfiles, CHARACTER_EXCERPT_LIMIT)).append("\n");
        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, Object value) {
        sb.append("- ").append(label).append(": ").append(nullToBlank(value)).append("\n");
    }

    private String nullToBlank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String excerpt(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "...";
    }

    private int countScenes(String scenesText) {
        if (scenesText == null || scenesText.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < scenesText.length(); i++) {
            if (scenesText.charAt(i) == '"') {
                count++;
            }
        }
        return Math.max(1, count / 2);
    }
}
