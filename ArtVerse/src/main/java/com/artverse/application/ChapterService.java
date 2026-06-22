package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.Story;
import com.artverse.domain.StoryAssetGroup;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryAssetGroupRepository;
import com.artverse.persistence.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChapterService {

    private final ChapterRepository chapterRepository;
    private final StoryRepository storyRepository;
    private final StoryAssetGroupRepository storyAssetGroupRepository;
    private static final int MAX_CREATE_RETRIES = 3;
    private static final Set<Integer> ALLOWED_IMAGE_COUNTS = Set.of(4, 6, 8, 10, 12, 15, 20);

    @Transactional(readOnly = true)
    public List<Chapter> listByStory(Long storyId) {
        Long userId = currentUserId();
        return chapterRepository.findByStoryIdWithDetailsAndUserId(storyId, userId);
    }

    @Transactional(readOnly = true)
    public Chapter getRequired(Long id) {
        Long userId = currentUserId();
        return chapterRepository.findByIdWithDetailsAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
    }

    @Transactional
    public Chapter createNext(Long storyId) {
        Long userId = currentUserId();
        Story story = storyRepository.findByIdAndUserIdWithChaptersAndGroups(storyId, userId)
                .orElseThrow(() -> new BusinessException(404, "Story not found"));

        for (int attempt = 0; attempt < MAX_CREATE_RETRIES; attempt++) {
            int maxNumber = chapterRepository.findMaxChapterNumberByStoryIdAndUserId(storyId, userId);
            int nextNumber = maxNumber + 1;

            Chapter chapter = new Chapter();
            chapter.setStory(story);
            chapter.setChapterNumber(nextNumber);
            try {
                return chapterRepository.saveAndFlush(chapter);
            } catch (DataIntegrityViolationException e) {
                if (attempt == MAX_CREATE_RETRIES - 1) {
                    throw new BusinessException(409, "Failed to create chapter after " + MAX_CREATE_RETRIES + " retries");
                }
            }
        }
        throw new BusinessException(409, "Failed to create chapter");
    }

    @Transactional
    public void delete(Long id) {
        Chapter chapter = getRequired(id);
        chapterRepository.delete(chapter);
    }

    @Transactional
    public Chapter updateColorMode(Long id, ColorMode colorMode) {
        Chapter chapter = getRequired(id);
        chapter.setColorMode(colorMode);
        return chapterRepository.save(chapter);
    }

    @Transactional
    public Chapter updateImageCount(Long id, int imageCount) {
        if (!ALLOWED_IMAGE_COUNTS.contains(imageCount)) {
            throw new BusinessException(400, "image_count must be one of: " + ALLOWED_IMAGE_COUNTS);
        }
        Chapter chapter = getRequired(id);
        if (chapter.getImages() != null && !chapter.getImages().isEmpty()) {
            throw new BusinessException(409, "Cannot change image count after manga images exist");
        }
        chapter.setImageCount(imageCount);
        return chapterRepository.save(chapter);
    }

    @Transactional
    public void setAssetGroup(Long chapterId, Long groupId) {
        Chapter chapter = getRequired(chapterId);
        if (groupId == null) {
            chapter.setAssetGroup(null);
        } else {
            StoryAssetGroup group = storyAssetGroupRepository.findById(groupId)
                    .orElseThrow(() -> new BusinessException(404, "Asset group not found"));
            chapter.setAssetGroup(group);
        }
        chapterRepository.save(chapter);
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
