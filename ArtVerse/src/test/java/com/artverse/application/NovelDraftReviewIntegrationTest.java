package com.artverse.application;

import com.artverse.api.StoryChatController;
import com.artverse.common.ApiResponseAdvice;
import com.artverse.common.GlobalExceptionHandler;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP mapping, transactions, production migrations and PostgreSQL writes; no model calls. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NovelDraftReviewService.class, NovelContentArtifactService.class, NovelContentService.class,
        StoryChatPreparationService.class, ChapterAccessService.class, AiConversationService.class,
        MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentOutboxService.class,
        NovelDraftReviewIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class NovelDraftReviewIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
        @Bean ArtVerseProperties properties() { return new ArtVerseProperties(); }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NovelDraftReviewService review;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChapterNovelRevisionRepository revisions;
    @MockBean AgentRunLeaseService lease;

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var controller = new StoryChatController(mock(StoryChatAgentService.class), mock(ApiKeyService.class),
                currentUser, mock(MangaAgentEventReplayService.class), review);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void confirmsExactReviewedTextAndRetryDoesNotCreateAnotherRevision() throws Exception {
        Fixture fixture = fixture();
        String finalText = "雨停了。\n少女仍守在门前。";
        mvc.perform(post(url(fixture)).contentType("application/json").content(body(fixture, "confirm", finalText)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.chapter_version").value(fixture.version() + 1));
        mvc.perform(post(url(fixture)).contentType("application/json").content(body(fixture, "confirm", finalText)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false));

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(em.find(Chapter.class, fixture.chapterId()).getNovelContent()).isEqualTo(finalText);
            var history = revisions.findByChapterIdOrderByRevisionNumberDesc(fixture.chapterId());
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().getContent()).isEqualTo(finalText);
            assertThat(history.getFirst().getSource()).isEqualTo(NovelContentRevisionSource.AI);
            var artifact = em.find(MangaAgentRunArtifact.class, fixture.artifactId());
            assertThat(artifact.getStatus()).isEqualTo("COMMITTED");
            assertThat(artifact.getPayload()).contains("reviewed_content_hash", ToolIdempotencyService.sha256(finalText));
            var run = em.find(MangaAgentRun.class, fixture.runId());
            assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.SUCCEEDED);
            assertThat(run.getUserInputRequestJson()).isNull();
        });
        assertThat(jdbc.queryForObject("select count(*) from agent_outbox_events where aggregate_id = ? and event_type = 'CHAPTER_CONTENT_CHANGED'",
                Long.class, fixture.chapterId().toString())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages where request_id = ? and role = 'ASSISTANT'",
                Long.class, fixture.requestId())).isEqualTo(1L);
        verifyNoInteractions(lease);
    }

    @Test
    void staleVersionRollsBackArtifactRunRevisionAndOutbox() throws Exception {
        Fixture fixture = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                em.find(Chapter.class, fixture.chapterId()).setNovelContent("作者已经另存的正文。"));

        mvc.perform(post(url(fixture)).contentType("application/json").content(body(fixture, "confirm", "过期的审阅稿。")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(em.find(Chapter.class, fixture.chapterId()).getNovelContent()).isEqualTo("作者已经另存的正文。");
            var artifact = em.find(MangaAgentRunArtifact.class, fixture.artifactId());
            assertThat(artifact.getStatus()).isEqualTo("VALIDATED");
            assertThat(artifact.getPayload()).doesNotContain("reviewed_content");
            assertThat(em.find(MangaAgentRun.class, fixture.runId()).getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
            assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(fixture.chapterId())).isEmpty();
        });
        assertThat(jdbc.queryForObject("select count(*) from agent_outbox_events where aggregate_id = ?",
                Long.class, fixture.chapterId().toString())).isZero();
    }

    @Test
    void discardIsIdempotentAndDoesNotWriteChapterHistory() throws Exception {
        Fixture fixture = fixture();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(url(fixture)).contentType("application/json").content(body(fixture, "discard", "")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.changed").value(false));
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(em.find(Chapter.class, fixture.chapterId()).getVersion()).isEqualTo(fixture.version());
            assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(fixture.chapterId())).isEmpty();
        });
    }

    private Fixture fixture() {
        Fixture fixture = new TransactionTemplate(transactions).execute(tx -> {
            User user = new User(); user.setUsername("draft_review_" + UUID.randomUUID());
            user.setEmail(user.getUsername() + "@example.test"); user.setPasswordHash("isolated-fixture");
            user.setRole(Role.USER); em.persist(user);
            Story story = new Story(); story.setTitle("Review fixture"); story.setUser(user); em.persist(story);
            Chapter chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1);
            chapter.setNovelContent("雨没有停。\n少女仍守在门前。"); em.persist(chapter);
            MangaAgentConversation conversation = new MangaAgentConversation();
            conversation.setConversationUuid(UUID.randomUUID()); conversation.setTitle("Review fixture");
            conversation.setConversationType(AiConversationType.STORY_CHAT);
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter); em.persist(conversation);
            MangaAgentRun run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter);
            run.setConversation(conversation); run.setRunType(AgentRunType.STORY_CHAT); run.setInputMessage("优化本章");
            run.setStatus(MangaAgentRunStatus.WAITING_USER); run.setUserInputRequestJson("{}"); em.persist(run);
            String draft = "雨停了。\n少女已离开门前。";
            String hash = ToolIdempotencyService.sha256(draft);
            MangaAgentRunArtifact artifact = new MangaAgentRunArtifact(); artifact.setRun(run);
            artifact.setArtifactType(NovelContentArtifactService.ARTIFACT_TYPE); artifact.setStatus("VALIDATED");
            try {
                artifact.setPayload(mapper.writeValueAsString(Map.of("content", draft, "content_hash", hash,
                        "base_version", chapter.getVersion(), "base_content", chapter.getNovelContent())));
            } catch (Exception ex) { throw new IllegalStateException(ex); }
            artifact.setChecksum(ToolIdempotencyService.sha256(artifact.getPayload())); em.persist(artifact);
            em.flush();
            return new Fixture(user.getId(), chapter.getId(), chapter.getVersion(), conversation.getConversationUuid(),
                    run.getId(), run.getRequestId(), artifact.getId(), artifact.getArtifactUuid(), hash);
        });
        User user = new User(); user.setId(fixture.userId()); when(currentUser.requireCurrentUser()).thenReturn(user);
        return fixture;
    }

    private String url(Fixture f) {
        return "/api/chapters/" + f.chapterId() + "/story-chat/conversations/" + f.conversationId()
                + "/runs/" + f.requestId() + "/artifacts/" + f.artifactUuid() + "/decision";
    }

    private String body(Fixture f, String decision, String content) throws Exception {
        return mapper.writeValueAsString(Map.of("decision", decision, "content", content,
                "base_version", f.version(), "expected_content_hash", f.hash()));
    }

    private record Fixture(Long userId, Long chapterId, Long version, UUID conversationId, Long runId,
                           UUID requestId, Long artifactId, UUID artifactUuid, String hash) { }
}
