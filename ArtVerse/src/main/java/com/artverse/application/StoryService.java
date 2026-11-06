package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.publication.PublicationFormat;
import com.artverse.application.publication.PublicationStrategyRegistry;
import com.artverse.common.BusinessException;
import com.artverse.application.knowledge.KnowledgeFactService;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final ChapterRepository chapterRepository;
    private final UserRepository userRepository;
    private final PublicationStrategyRegistry publicationStrategies;
    private final KnowledgeFactService knowledgeFacts;

    @Transactional(readOnly = true)
    public List<Story> listAll() {
        Long userId = currentUserId();
        return storyRepository.findByUserIdWithChaptersAndGroups(userId);
    }

    @Transactional(readOnly = true)
    public Story getRequired(Long id) {
        Long userId = currentUserId();
        return storyRepository.findByIdAndUserIdWithChaptersAndGroups(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));
    }

    @Transactional
    public Story create(String title, String description) {
        Long userId = currentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));

        Story story = new Story();
        story.setTitle(title);
        story.setDescription(description);
        story.setUser(user);
        story = storyRepository.save(story);

        Chapter chapter = new Chapter();
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapterRepository.save(chapter);

        return story;
    }

    @Transactional
    public Story update(Long id, String title, String description, String characterProfiles) {
        Story story = getRequired(id);
        if (title != null) story.setTitle(title);
        if (description != null) story.setDescription(description);
        if (characterProfiles != null) story.setCharacterProfiles(characterProfiles);
        return storyRepository.save(story);
    }

    @Transactional
    public void delete(Long id) {
        Story story = getRequired(id);
        knowledgeFacts.deleteForStory(id,story.getUser().getId());
        storyRepository.delete(story);
    }

    @Transactional
    public Story updateCoverImage(Long id, String coverImagePath) {
        Story story = getRequired(id);
        story.setCoverImage(coverImagePath);
        return storyRepository.save(story);
    }

    @Transactional(readOnly = true)
    public String getMangaStyle(Long id) {
        Story story = getRequired(id);
        return story.getMangaStyle() != null ? story.getMangaStyle() : "japanese_manga";
    }

    @Transactional
    public String setMangaStyle(Long id, String mangaStyle) {
        Story story = getRequired(id);
        story.setMangaStyle(mangaStyle);
        storyRepository.saveAndFlush(story);
        return mangaStyle;
    }

    @Transactional
    public Story publish(Long id, Boolean isPublished, List<Long> chapterIds) {
        return publish(id, PublicationFormat.MANGA, isPublished, chapterIds);
    }

    @Deprecated(forRemoval = false)
    @Transactional
    public Story publish(Long id, String format, Boolean isPublished, List<Long> chapterIds) {
        return publish(id, PublicationFormat.fromApiValue(format), isPublished, chapterIds);
    }

    @Transactional
    public Story publish(Long id, PublicationFormat format, Boolean isPublished, List<Long> chapterIds) {
        Story story = getRequired(id);
        Set<Long> selectedChapterIds = chapterIds == null || chapterIds.isEmpty()
                ? Set.of()
                : new HashSet<>(chapterIds);
        validatePublication(story, format, Boolean.TRUE.equals(isPublished), selectedChapterIds);
        publicationStrategies.apply(format, story, Boolean.TRUE.equals(isPublished), selectedChapterIds);
        return storyRepository.save(story);
    }

    private void validatePublication(Story story, PublicationFormat format, boolean published, Set<Long> selectedChapterIds) {
        if (!published) return;
        Set<Long> storyChapterIds = story.getChapters().stream().map(Chapter::getId).collect(java.util.stream.Collectors.toSet());
        if (!storyChapterIds.containsAll(selectedChapterIds)) {
            throw new BusinessException(400, "Selected chapters must belong to the story");
        }
        List<Chapter> chapters = selectedChapterIds.isEmpty()
                ? story.getChapters()
                : story.getChapters().stream().filter(chapter -> selectedChapterIds.contains(chapter.getId())).toList();
        if (chapters.isEmpty()) {
            throw new BusinessException(400, "At least one readable chapter is required before publishing");
        }
        boolean hasInvalidContent = format == PublicationFormat.NOVEL
                ? chapters.stream().anyMatch(chapter -> chapter.getNovelContent() == null || chapter.getNovelContent().isBlank())
                : chapters.stream().anyMatch(chapter -> chapter.getImages() == null || chapter.getImages().isEmpty());
        if (hasInvalidContent) {
            String contentType = format == PublicationFormat.NOVEL ? "non-empty novel content" : "at least one manga image";
            throw new BusinessException(400, "Every published chapter must contain " + contentType);
        }
    }

    @Transactional
    public void updateChapterOrder(Long storyId, List<Map<String, Object>> orders) {
        Story story = getRequired(storyId);
        for (Map<String, Object> entry : orders) {
            Long chapterId = ((Number) entry.get("chapter_id")).longValue();
            Integer order = ((Number) entry.get("display_order")).intValue();
            String title = (String) entry.get("display_title");
            for (Chapter ch : story.getChapters()) {
                if (ch.getId().equals(chapterId)) {
                    ch.setDisplayOrder(order);
                    if (title != null) ch.setDisplayTitle(title);
                    break;
                }
            }
        }
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
