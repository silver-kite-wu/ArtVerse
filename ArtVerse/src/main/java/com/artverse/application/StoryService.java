package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StoryService {

    private final StoryRepository storyRepository;
    private final ChapterRepository chapterRepository;
    private final UserRepository userRepository;

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
        storyRepository.setMangaStyle(id, mangaStyle);
        return mangaStyle;
    }

    @Transactional
    public Story publish(Long id, Boolean isPublished, List<Long> chapterIds) {
        Story story = getRequired(id);
        story.setIsPublished(isPublished);
        if (isPublished) {
            story.setPublishedAt(java.time.OffsetDateTime.now());
            if (chapterIds != null && !chapterIds.isEmpty()) {
                for (Chapter ch : story.getChapters()) {
                    ch.setIsPublished(chapterIds.contains(ch.getId()));
                }
            } else {
                for (Chapter ch : story.getChapters()) {
                    ch.setIsPublished(true);
                    ch.setDisplayOrder(ch.getChapterNumber());
                }
            }
        } else {
            story.setPublishedAt(null);
            for (Chapter ch : story.getChapters()) {
                ch.setIsPublished(false);
            }
        }
        return storyRepository.save(story);
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
