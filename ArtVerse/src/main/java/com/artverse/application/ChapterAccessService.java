package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.persistence.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChapterAccessService {

    private final ChapterRepository chapterRepository;

    @Transactional(readOnly = true)
    public Chapter requireVisible(Long chapterId, Long userId) {
        Chapter chapter = chapterRepository.findByIdForIdempotency(chapterId)
                .orElseThrow(() -> new BusinessException(404, "Chapter not found"));
        ensureVisible(chapter, userId);
        return chapter;
    }

    public void ensureVisible(Chapter chapter, Long userId) {
        if (chapter == null) {
            throw new BusinessException(404, "Chapter not found");
        }
        Story story = chapter.getStory();
        if (story == null || story.getUser() == null) {
            return;
        }
        if (userId == null || !story.getUser().getId().equals(userId)) {
            throw new BusinessException(403, "Forbidden");
        }
    }
}
