package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.persistence.ChapterRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChapterAccessServiceTest {

    @Test
    void allowsOwnerToAccessChapter() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        Chapter chapter = chapterWithOwner(7L, 1L);
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));

        ChapterAccessService service = new ChapterAccessService(chapterRepository);

        assertThat(service.requireVisible(7L, 1L)).isSameAs(chapter);
    }

    @Test
    void rejectsDifferentUser() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapterWithOwner(7L, 1L)));

        ChapterAccessService service = new ChapterAccessService(chapterRepository);

        assertThatThrownBy(() -> service.requireVisible(7L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Forbidden");
    }

    @Test
    void allowsLegacyChapterWithoutStoryOwner() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        chapter.setStory(new Story());
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));

        ChapterAccessService service = new ChapterAccessService(chapterRepository);

        assertThat(service.requireVisible(7L, 2L)).isSameAs(chapter);
    }

    @Test
    void throwsNotFoundWhenChapterMissing() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.empty());

        ChapterAccessService service = new ChapterAccessService(chapterRepository);

        assertThatThrownBy(() -> service.requireVisible(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Chapter not found");
    }

    private Chapter chapterWithOwner(Long chapterId, Long ownerId) {
        User user = new User();
        user.setId(ownerId);
        Story story = new Story();
        story.setId(3L);
        story.setUser(user);
        Chapter chapter = new Chapter();
        chapter.setId(chapterId);
        chapter.setStory(story);
        return chapter;
    }
}
