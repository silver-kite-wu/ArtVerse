package com.artverse.application;

import cn.dev33.satoken.stp.StpUtil;
import com.artverse.application.publication.MangaPublicationStrategy;
import com.artverse.application.publication.NovelPublicationStrategy;
import com.artverse.application.publication.PublicationStrategyRegistry;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import com.artverse.persistence.ChapterRepository;
import com.artverse.persistence.StoryRepository;
import com.artverse.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StoryServicePublishFormatTest {

    private final StoryRepository storyRepository = mock(StoryRepository.class);
    private final StoryService service = new StoryService(
            storyRepository,
            mock(ChapterRepository.class),
            mock(UserRepository.class),
            new PublicationStrategyRegistry(List.of(
                    new MangaPublicationStrategy(),
                    new NovelPublicationStrategy())),
            mock(com.artverse.application.knowledge.KnowledgeFactService.class)
    );

    @Test
    void publishingNovelDoesNotChangeMangaPublication() {
        Story story = storyWithTwoChapters();
        story.setIsPublished(true);
        story.getChapters().getFirst().setIsPublished(true);

        stubCurrentStory(story);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.publish(7L, "novel", true, List.of(12L));
        }

        assertThat(story.getNovelIsPublished()).isTrue();
        assertThat(story.getChapters()).extracting(Chapter::getNovelIsPublished)
                .containsExactly(false, true);
        assertThat(story.getIsPublished()).isTrue();
        assertThat(story.getChapters()).extracting(Chapter::getIsPublished)
                .containsExactly(true, false);
    }

    @Test
    void publishingMangaDoesNotChangeNovelPublication() {
        Story story = storyWithTwoChapters();
        story.setNovelIsPublished(true);
        story.getChapters().getFirst().setNovelIsPublished(true);

        stubCurrentStory(story);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.publish(7L, "manga", true, List.of(11L));
        }

        assertThat(story.getIsPublished()).isTrue();
        assertThat(story.getChapters()).extracting(Chapter::getIsPublished)
                .containsExactly(true, false);
        assertThat(story.getNovelIsPublished()).isTrue();
        assertThat(story.getChapters()).extracting(Chapter::getNovelIsPublished)
                .containsExactly(true, false);
    }

    @Test
    void legacyPublishDefaultsToMangaAndClearsPreviouslyPublishedUnselectedChapters() {
        Story story = storyWithTwoChapters();
        story.getChapters().forEach(chapter -> chapter.setIsPublished(true));

        stubCurrentStory(story);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.publish(7L, true, List.of(12L));
        }

        assertThat(story.getIsPublished()).isTrue();
        assertThat(story.getChapters()).extracting(Chapter::getIsPublished)
                .containsExactly(false, true);
        assertThat(story.getChapters().get(1).getDisplayOrder()).isEqualTo(2);
    }

    @Test
    void invalidStringFormatFailsBeforeLoadingStory() {
        BusinessException error = catchThrowableOfType(
                () -> service.publish(7L, "audio", true, null),
                BusinessException.class);

        assertThat(error.getStatus()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo("Invalid publication format");
    }

    private void stubCurrentStory(Story story) {
        when(storyRepository.findByIdAndUserIdWithChaptersAndGroups(7L, 99L))
                .thenReturn(Optional.of(story));
        when(storyRepository.save(story)).thenReturn(story);
    }

    private Story storyWithTwoChapters() {
        Story story = new Story();
        story.setId(7L);
        Chapter first = new Chapter();
        first.setId(11L);
        first.setStory(story);
        first.setChapterNumber(1);
        first.setNovelContent("第一章正文");
        first.getImages().add(image(first, 1));
        Chapter second = new Chapter();
        second.setId(12L);
        second.setStory(story);
        second.setChapterNumber(2);
        second.setNovelContent("第二章正文");
        second.getImages().add(image(second, 1));
        story.getChapters().add(first);
        story.getChapters().add(second);
        return story;
    }

    private MangaImage image(Chapter chapter, int number) {
        MangaImage image = new MangaImage();
        image.setId((long) (100 + chapter.getChapterNumber()));
        image.setChapter(chapter);
        image.setImageNumber(number);
        image.setImagePath("stories/7/" + chapter.getChapterNumber() + ".png");
        return image;
    }
}
