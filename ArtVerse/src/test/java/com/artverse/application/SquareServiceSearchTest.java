package com.artverse.application;

import com.artverse.api.dto.SquareDtos;
import com.artverse.domain.Chapter;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作品广场搜索回归测试。
 * 连接串必须与生产一致使用 stringtype=unspecified：pgjdbc 在该模式下以未定类型发送
 * String 参数，若 SQL 把参数放进 CONCAT()（VARIADIC "any"），PostgreSQL 无法推断类型，
 * 搜索会报 "could not determine data type of parameter" 并返回 500。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/manga_novel?stringtype=unspecified")
@EnabledIf(value = "com.artverse.application.SquareServiceSearchTest#localPostgresAvailable",
        disabledReason = "本地 PostgreSQL（docker-compose）未运行，跳过")
@Transactional
class SquareServiceSearchTest {

    static boolean localPostgresAvailable() {
        try {
            return DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/manga_novel?connectTimeout=2", "postgres", "postgres") != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private SquareService squareService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void searchByExactTitleReturnsPublishedMangaStory() {
        Long storyId = seedMangaStory("星际追光者QX7", "一部关于星际旅行的科幻漫画");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "星际追光者QX7", "manga");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).singleElement().satisfies(card -> {
            assertThat(card.id()).isEqualTo(storyId);
            assertThat(card.format()).isEqualTo("manga");
            assertThat(card.title()).isEqualTo("星际追光者QX7");
            assertThat(card.chapterCount()).isEqualTo(1);
            assertThat(card.contentCount()).isEqualTo(1);
        });
    }

    @Test
    void searchIsCaseInsensitive() {
        Long storyId = seedNovelStory("Regression QX7 Great Migration", "an epic journey");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "REGRESSION qx7", "novel");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).singleElement()
                .satisfies(card -> assertThat(card.id()).isEqualTo(storyId));
    }

    @Test
    void fuzzyPartialKeywordMatches() {
        seedMangaStory("星际追光者QX7", "一部关于星际旅行的科幻漫画");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "追光", "manga");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).singleElement()
                .satisfies(card -> assertThat(card.title()).isEqualTo("星际追光者QX7"));
    }

    @Test
    void searchMatchesDescriptionKeyword() {
        Long storyId = seedNovelStory("Regression QX7 Great Migration", "一段穿越北方冻原的长途旅程");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "北方冻原", "novel");

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).singleElement()
                .satisfies(card -> assertThat(card.id()).isEqualTo(storyId));
    }

    @Test
    void noMatchKeywordReturnsEmptyResultInsteadOfError() {
        seedMangaStory("星际追光者QX7", "一部关于星际旅行的科幻漫画");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "完全不存在的关键词ZZ9", "all");

        assertThat(response.totalElements()).isZero();
        assertThat(response.content()).isEmpty();
        assertThat(response.totalPages()).isZero();
        assertThat(response.facets().values()).allSatisfy(count -> assertThat(count).isZero());
    }

    @Test
    void facetsCountMatchesPerFormat() {
        seedMangaStory("星际追光者QX7", "一部关于星际旅行的科幻漫画");
        seedNovelStory("Regression QX7 Great Migration", "an epic journey");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, "QX7", "all");

        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.facets())
                .containsEntry("all", 2L)
                .containsEntry("novel", 1L)
                .containsEntry("manga", 1L);
    }

    @Test
    void emptyKeywordListsPublishedStories() {
        seedMangaStory("星际追光者QX7", "一部关于星际旅行的科幻漫画");

        SquareDtos.StoryListResponse response = squareService.listPublishedStories(0, 12, null, "manga");

        assertThat(response.totalElements()).isGreaterThanOrEqualTo(1);
        assertThat(response.content())
                .anySatisfy(card -> assertThat(card.title()).isEqualTo("星际追光者QX7"));
    }

    private Long seedMangaStory(String title, String description) {
        Story story = new Story();
        story.setTitle(title);
        story.setDescription(description);
        story.setIsPublished(true);
        story.setPublishedAt(OffsetDateTime.now());
        entityManager.persist(story);

        Chapter published = new Chapter();
        published.setStory(story);
        published.setChapterNumber(1);
        published.setIsPublished(true);
        entityManager.persist(published);

        MangaImage image = new MangaImage();
        image.setChapter(published);
        image.setImageNumber(1);
        image.setImagePath("/square-search-test/page-1.png");
        entityManager.persist(image);

        Chapter unpublished = new Chapter();
        unpublished.setStory(story);
        unpublished.setChapterNumber(2);
        entityManager.persist(unpublished);

        entityManager.flush();
        return story.getId();
    }

    private Long seedNovelStory(String title, String description) {
        Story story = new Story();
        story.setTitle(title);
        story.setDescription(description);
        story.setNovelIsPublished(true);
        story.setNovelPublishedAt(OffsetDateTime.now());
        entityManager.persist(story);

        Chapter chapter = new Chapter();
        chapter.setStory(story);
        chapter.setChapterNumber(1);
        chapter.setNovelIsPublished(true);
        chapter.setNovelContent("第一章：故事从这里开始。");
        entityManager.persist(chapter);

        entityManager.flush();
        return story.getId();
    }
}
