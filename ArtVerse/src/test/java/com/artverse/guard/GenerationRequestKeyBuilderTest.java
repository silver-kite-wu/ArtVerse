package com.artverse.guard;

import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.artverse.persistence.ChapterRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationRequestKeyBuilderTest {

    @Test
    void mangaAgentRunPayloadUsesModelSummaryWithoutApiKey() {
        ChapterRepository chapterRepository = mock(ChapterRepository.class);
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        Story story = new Story();
        story.setId(3L);
        chapter.setStory(story);
        when(chapterRepository.findByIdForIdempotency(7L)).thenReturn(Optional.of(chapter));

        GenerationRequestKeyBuilder builder = new GenerationRequestKeyBuilder(
                chapterRepository,
                new RequestCanonicalizer(),
                new ArtVerseProperties()
        );

        Map<String, Object> payload = builder.mangaAgentRun(
                1L,
                7L,
                "request-1",
                "  hello   world  ",
                "deepseek",
                "deepseek-chat",
                "abc123"
        );

        assertThat(payload).containsEntry("action", "manga-agent-run")
                .containsEntry("userId", 1L)
                .containsEntry("chapterId", 7L)
                .containsEntry("storyId", 3L)
                .containsEntry("requestId", "request-1")
                .containsEntry("message", "hello world")
                .containsEntry("provider", "deepseek")
                .containsEntry("model", "deepseek-chat")
                .containsEntry("baseUrlHash", "abc123");
        assertThat(payload.toString()).doesNotContain("api-key", "https://api.deepseek.com");
    }
}
