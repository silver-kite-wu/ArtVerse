package com.artverse.api;

import com.artverse.api.dto.ChapterDto;
import com.artverse.application.NovelService;
import com.artverse.domain.Chapter;
import com.artverse.domain.ChatMessage;
import com.artverse.domain.ContentSource;
import com.artverse.domain.MessageRole;
import com.artverse.domain.Story;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class NovelControllerTest {

    @Test
    void importNovelReturnsChapterDtoWithMessages() {
        Chapter chapter = new Chapter();
        chapter.setId(7L);
        Story story = new Story();
        story.setId(3L);
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setNovelContent("正文");
        chapter.setContentSource(ContentSource.IMPORT);
        chapter.setMessages(new ArrayList<>());
        chapter.setImages(new java.util.LinkedHashSet<>());
        ChatMessage message = new ChatMessage();
        message.setId(9L);
        message.setRole(MessageRole.USER);
        message.setContent("正文");
        message.setChapter(chapter);
        chapter.getMessages().add(message);

        NovelService service = new NovelService(null, null, null, null, null, null) {
            @Override
            public Chapter importNovel(Long chapterId, String content) {
                assertThat(chapterId).isEqualTo(7L);
                assertThat(content).isEqualTo("正文");
                return chapter;
            }
        };
        NovelController controller = new NovelController(service, null, null);

        Object result = controller.importNovel(7L, java.util.Map.of("content", "正文"));

        assertThat(result).isInstanceOf(ChapterDto.class);
        ChapterDto dto = (ChapterDto) result;
        assertThat(dto.novelContent()).isEqualTo("正文");
        assertThat(dto.messages()).hasSize(1);
        assertThat(dto.messages().get(0).content()).isEqualTo("正文");
    }
}
