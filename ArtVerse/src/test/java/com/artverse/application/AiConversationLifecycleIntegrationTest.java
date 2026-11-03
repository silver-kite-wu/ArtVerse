package com.artverse.application;

import com.artverse.api.AiConversationController;
import com.artverse.ai.ConversationTitleClient;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.artverse.persistence.ChatMessageRepository;
import com.artverse.persistence.MangaAgentConversationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AiConversationService.class, ChapterAccessService.class, StoryChatPreparationService.class,
        ConversationTitleGenerator.class, AiConversationLifecycleIntegrationTest.TitleWorkers.class})
@Testcontainers(disabledWithoutDocker = true)
class AiConversationLifecycleIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired EntityManager em;
    @Autowired AiConversationService service;
    @Autowired StoryChatPreparationService preparation;
    @Autowired MangaAgentConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired PendingTitles pendingTitles;
    @MockBean ApiKeyService apiKeys;
    @MockBean ConversationTitleClient titleClient;
    private User user;
    private Chapter chapter;
    private MockMvc api;

    @BeforeEach
    void fixture() {
        pendingTitles.tasks.clear();
        user = new User();
        user.setUsername("conversation_" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.test");
        user.setPasswordHash("isolated-fixture"); user.setRole(Role.USER); em.persist(user);
        Story story = new Story(); story.setTitle("Conversation lifecycle fixture"); story.setUser(user); em.persist(story);
        chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1);
        chapter.setNovelContent("正文保持不变"); em.persist(chapter); em.flush();
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireCurrentUser()).thenReturn(user);
        api = MockMvcBuilders.standaloneSetup(new AiConversationController(service, currentUser)).build();
    }

    @TestConfiguration
    static class TitleWorkers {
        @Bean(name = "conversationTitleExecutor")
        PendingTitles titleExecutor() { return new PendingTitles(); }
    }

    static class PendingTitles implements java.util.concurrent.Executor {
        final java.util.Queue<Runnable> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        public void execute(Runnable task) { tasks.add(task); }
    }

    @Test
    void repeatedCreateReusesEmptyConversationWithoutChangingItsTitle() throws Exception {
        var a = create("会话A");
        api.perform(post("/api/ai/conversations").contentType("application/json")
                        .content("{\"type\":\"STORY_CHAT\",\"chapterId\":" + chapter.getId() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("conversationId").value(a.getConversationUuid().toString()));
        assertThat(create("另一个标题").getConversationUuid()).isEqualTo(a.getConversationUuid());
        assertThat(service.list(user, AiConversationType.STORY_CHAT, chapter.getId(), false)).hasSize(1);
        assertThat(service.require(user, a.getConversationUuid()).getTitle()).isEqualTo("会话A");
        saveMessage(a, "已发送的消息");
        var b = create("会话B");
        assertThat(b.getConversationUuid()).isNotEqualTo(a.getConversationUuid());
        assertThat(create("会话C").getConversationUuid()).isEqualTo(b.getConversationUuid());
        assertThat(service.list(user, AiConversationType.STORY_CHAT, chapter.getId(), false)).hasSize(2);
    }

    @Test
    void concurrentCreateRequestsReturnTheSameEmptyConversation() throws Exception {
        Long userId = user.getId(); Long storyId = chapter.getStory().getId(); Long chapterId = chapter.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.Callable<UUID> create = () -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return service.create(user, AiConversationType.STORY_CHAT, chapterId, null).getConversationUuid();
                };
                var first = callers.submit(create); var second = callers.submit(create);
                try { assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                finally { start.countDown(); }
                assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(second.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(service.list(user, AiConversationType.STORY_CHAT, chapterId, false)).hasSize(1);
        } finally {
            jdbc.update("DELETE FROM stories WHERE id=?", storyId);
            jdbc.update("DELETE FROM users WHERE id=?", userId);
        }
    }

    @Test
    void emptyConversationIsNeverReusedAcrossChapters() {
        var a = create("会话A");
        Chapter other = new Chapter(); other.setStory(chapter.getStory()); other.setChapterNumber(2); em.persist(other); em.flush();
        var b = service.create(user, AiConversationType.STORY_CHAT, other.getId(), null);
        assertThat(b.getConversationUuid()).isNotEqualTo(a.getConversationUuid());
        assertThat(b.getChapter().getId()).isEqualTo(other.getId());
    }

    @Test
    void conversationWithTaskButNoMessageIsNotAnEmptyDraft() {
        var a = create("会话A");
        MangaAgentRun run = new MangaAgentRun(); run.setUser(user); run.setStory(chapter.getStory());
        run.setChapter(chapter); run.setConversation(a); run.setRequestId(UUID.randomUUID());
        run.setInputMessage("fixture"); run.setRunType(AgentRunType.STORY_CHAT); em.persist(run); em.flush();
        assertThat(create("会话B").getConversationUuid()).isNotEqualTo(a.getConversationUuid());
    }

    @Test
    void createSecondConversationThroughApiPreservesHistoryAndMessageIsolation() throws Exception {
        var a = create("会话A");
        saveMessage(a, "A的问题");
        api.perform(post("/api/ai/conversations").contentType("application/json")
                        .content("{\"type\":\"STORY_CHAT\",\"chapterId\":" + chapter.getId() + ",\"title\":\"会话B\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("title").value("会话B"));
        em.flush(); em.clear();
        var items = service.list(user, AiConversationType.STORY_CHAT, chapter.getId(), false);
        assertThat(items).extracting(MangaAgentConversation::getTitle).containsExactly("会话B", "会话A");
        var b = items.getFirst();
        assertThat(service.storyConversation(user, chapter.getId()).getConversationUuid()).isEqualTo(b.getConversationUuid());
        assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(b.getId())).isEmpty();
        assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(a.getId()))
                .extracting(ChatMessage::getContent).containsExactly("A的问题");
        saveMessage(b, "B的问题");
        assertThat(preparation.snapshot(preparation.resolve(user.getId(), chapter.getId(), a.getConversationUuid()),
                UUID.randomUUID(), "新问题").history()).extracting(com.artverse.agent.AgentMessage::content)
                .containsExactly("A的问题");
    }

    @Test
    void deletingAThroughApiCascadesItsMessagesAndProposalsAndPreservesBAndNovel() throws Exception {
        var a = create("会话A"); var message = saveMessage(a, "A的问题");
        var b = create("会话B"); saveMessage(b, "B的问题");
        jdbc.update("INSERT INTO novel_content_proposals (id,chapter_id,conversation_id,through_message_id," +
                        "base_version,generated_content,generated_content_hash,draft_content,draft_content_hash,model,prompt_version) " +
                        "VALUES (?,?,?,?,0,'draft','hash','draft','hash','fixture','fixture')",
                UUID.randomUUID(), chapter.getId(), a.getId(), message.getId());
        api.perform(delete("/api/ai/conversations/" + a.getConversationUuid())).andExpect(status().isNoContent());
        em.clear();
        assertThat(conversations.findById(a.getId())).isEmpty();
        assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(a.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_content_proposals WHERE conversation_id=?", Long.class, a.getId())).isZero();
        assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(b.getId()))
                .extracting(ChatMessage::getContent).containsExactly("B的问题");
        assertThat(em.find(Chapter.class, chapter.getId()).getNovelContent()).isEqualTo("正文保持不变");
        api.perform(get("/api/ai/conversations").param("type", "STORY_CHAT").param("chapterId", chapter.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("会话B"));
    }

    @Test
    void deletingLastConversationAllowsCreatingANewOne() {
        var a = create("会话A"); service.delete(user, a.getConversationUuid());
        assertThat(service.list(user, AiConversationType.STORY_CHAT, chapter.getId(), false)).isEmpty();
        var next = service.create(user, AiConversationType.STORY_CHAT, chapter.getId(), null);
        assertThat(next.getConversationUuid()).isNotEqualTo(a.getConversationUuid());
        assertThat(next.getTitle()).isEqualTo(AiConversationService.DEFAULT_TITLE);
    }

    @Test
    void anotherUserCannotReadOrDeleteConversation() {
        var a = create("会话A");
        User stranger = new User(); stranger.setId(user.getId() + 999999);
        assertThatThrownBy(() -> service.delete(stranger, a.getConversationUuid()))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.list(stranger, AiConversationType.STORY_CHAT, chapter.getId(), false))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getStatus()).isEqualTo(403));
        assertThat(conversations.findById(a.getId())).isPresent();
    }

    @Test
    void openRunsBlockDeletionAndCompletedRunsAreCleanedUp() {
        var a = create("会话A");
        MangaAgentRun run = new MangaAgentRun(); run.setUser(user); run.setStory(chapter.getStory());
        run.setChapter(chapter); run.setConversation(a); run.setRequestId(UUID.randomUUID());
        run.setInputMessage("fixture"); run.setRunType(AgentRunType.STORY_CHAT); em.persist(run); em.flush();
        for (var state : java.util.List.of(MangaAgentRunStatus.RUNNING, MangaAgentRunStatus.WAITING_USER)) {
            run.setStatus(state); em.flush();
            assertThatThrownBy(() -> service.delete(user, a.getConversationUuid()))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getStatus()).isEqualTo(409));
        }
        run.setStatus(MangaAgentRunStatus.SUCCEEDED); em.flush(); service.delete(user, a.getConversationUuid());
        em.clear(); assertThat(em.find(MangaAgentRun.class, run.getId())).isNull();
    }

    private MangaAgentConversation create(String title) {
        return service.create(user, AiConversationType.STORY_CHAT, chapter.getId(), title);
    }

    @Test
    void imageConversationsUseIndependentBoundedPromptTitles() {
        var first = service.create(user, AiConversationType.IMAGE_GEN, null, null);
        var second = service.create(user, AiConversationType.IMAGE_GEN, null, null);
        assertThat(first.getTitleSource()).isEqualTo(AiConversationTitleSource.DEFAULT);
        assertThat(first.getConversationUuid()).isNotEqualTo(second.getConversationUuid());
        service.autoTitle(first, "😀".repeat(30)); service.touch(first);
        service.autoTitle(second, "画一座雨夜城市"); service.touch(second);
        em.flush(); em.clear();
        var titled = service.require(user, first.getConversationUuid());
        assertThat(titled.getTitle()).isEqualTo("😀".repeat(24));
        assertThat(titled.getTitleSource()).isEqualTo(AiConversationTitleSource.FALLBACK);
        assertThat(titled.getChapter()).isNull();
        assertThat(conversations.finalizeGeneratedTitle(first.getId(), "城市插画", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isEqualTo(1);
        em.clear();
        assertThat(service.require(user, first.getConversationUuid()).getTitle()).isEqualTo("城市插画");
        assertThat(service.require(user, second.getConversationUuid()).getTitle()).isEqualTo("画一座雨夜城市");
    }

    @Test
    void imageConversationWithExplicitUserTitleDoesNotAutoRename() {
        var c = service.create(user, AiConversationType.IMAGE_GEN, null, "我的插画集");
        service.autoTitle(c, "发送新的图片提示词"); service.touch(c); em.flush(); em.clear();
        var current = service.require(user, c.getConversationUuid());
        assertThat(current.getTitle()).isEqualTo("我的插画集");
        assertThat(current.getTitleSource()).isEqualTo(AiConversationTitleSource.USER);
        assertThat(conversations.finalizeGeneratedTitle(c.getId(), "自动标题", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isZero();
    }

    @Test
    void fallbackSurvivesTouchAndReloadAndLaterMessages() {
        var c = create(null);
        var scope = preparation.resolve(user.getId(), chapter.getId(), c.getConversationUuid());
        preparation.touch(scope, "你好");
        em.flush(); em.clear();
        var titled = service.require(user, c.getConversationUuid());
        assertThat(titled.getTitle()).isEqualTo("你好");
        assertThat(titled.getTitleSource()).isEqualTo(AiConversationTitleSource.FALLBACK);
        assertThat(titled.getTitleState()).isEqualTo(AiConversationTitleState.GENERATING);
        preparation.touch(scope, "另一个完全不同的问题");
        em.flush(); em.clear();
        assertThat(service.require(user, c.getConversationUuid()).getTitle()).isEqualTo("你好");
    }

    @Test
    void generationRunsAfterCommitAndProviderFailureOrManualRenameKeepUsefulTitles() {
        var success = create(null); saveMessage(success, "帮我设计雨夜悬疑故事");
        service.autoTitle(success, "帮我设计雨夜悬疑故事");
        var manual = create(null); saveMessage(manual, "第二条主题"); service.autoTitle(manual, "第二条主题");
        var failure = create(null); service.autoTitle(failure, "失败时使用这条消息");
        assertThat(pendingTitles.tasks).isEmpty();
        verifyNoInteractions(titleClient);
        var config = new UserProviderConfig("llm", "fixture", "fixture", "test-key", "https://example.test", "fixture");
        when(apiKeys.requireActiveUserProviderConfig(any(), eq("llm"), anyString())).thenReturn(config);
        when(titleClient.generate(eq(config), anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String input = call.getArgument(1);
            if (input.startsWith("失败")) throw new IllegalStateException("provider failed");
            return "雨夜悬疑构思";
        });
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            assertThat(pendingTitles.tasks).hasSize(3);
            service.rename(user, manual.getConversationUuid(), "手动设置的主题");
            pendingTitles.tasks.forEach(Runnable::run);
            var result = service.require(user, success.getConversationUuid());
            assertThat(result.getTitle()).isEqualTo("雨夜悬疑构思");
            assertThat(result.getTitleSource()).isEqualTo(AiConversationTitleSource.AI);
            assertThat(result.getTitleState()).isEqualTo(AiConversationTitleState.FINALIZED);
            assertThat(service.require(user, manual.getConversationUuid()).getTitle()).isEqualTo("手动设置的主题");
            var fallback = service.require(user, failure.getConversationUuid());
            assertThat(fallback.getTitle()).isEqualTo("失败时使用这条消息");
            assertThat(fallback.getTitleSource()).isEqualTo(AiConversationTitleSource.FALLBACK);
            assertThat(fallback.getTitleState()).isEqualTo(AiConversationTitleState.FINALIZED);
        } finally {
            jdbc.update("DELETE FROM stories WHERE id=?", chapter.getStory().getId());
            jdbc.update("DELETE FROM users WHERE id=?", user.getId());
        }
    }

    @Test
    void rolledBackMessagesNeverScheduleGeneration() {
        var c = create(null); service.autoTitle(c, "回滚消息");
        TestTransaction.flagForRollback(); TestTransaction.end();
        assertThat(pendingTitles.tasks).isEmpty();
        verifyNoInteractions(titleClient);
    }

    @Test
    void concurrentFirstMessagesScheduleOnlyOneTitleEvenWithStaleLoadedEntities() throws Exception {
        var c = create(null);
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var calls = java.util.List.of("第一个主题", "第二个主题").stream().map(input -> workers.submit(() ->
                        new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                            var loaded = service.require(user, c.getConversationUuid());
                            ready.countDown();
                            try {
                                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                            service.autoTitle(loaded, input); service.touch(loaded);
                            return true;
                        }))).toList();
                try { assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                finally { start.countDown(); }
                for (var call : calls) assertThat(call.get(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            assertThat(pendingTitles.tasks).hasSize(1);
            assertThat(service.require(user, c.getConversationUuid()).getTitle()).isIn("第一个主题", "第二个主题");
        } finally {
            jdbc.update("DELETE FROM stories WHERE id=?", chapter.getStory().getId());
            jdbc.update("DELETE FROM users WHERE id=?", user.getId());
        }
    }

    @Test
    void stalePersistenceContextCannotOverwriteDatabaseManualTitle() {
        var c = create(null); em.flush();
        jdbc.update("UPDATE ai_conversations SET title='用户标题',title_source='USER',title_state='FINALIZED' WHERE id=?", c.getId());
        assertThat(c.getTitleSource()).isEqualTo(AiConversationTitleSource.DEFAULT);
        service.autoTitle(c, "迟到消息"); service.touch(c); em.flush(); em.clear();
        assertThat(service.require(user, c.getConversationUuid()).getTitle()).isEqualTo("用户标题");
    }

    @Test
    void unrelatedEntityUpdateDoesNotRestoreTitleFromBeforeAiCompletion() {
        var c = create(null); service.autoTitle(c, "兜底输入"); em.flush();
        jdbc.update("UPDATE ai_conversations SET title='AI主题',title_source='AI',title_state='FINALIZED',title_generation_started_at=null WHERE id=?", c.getId());
        c.setStatus(MangaAgentConversationStatus.ARCHIVED); em.flush(); em.clear();
        var current = service.require(user, c.getConversationUuid());
        assertThat(current.getStatus()).isEqualTo(MangaAgentConversationStatus.ARCHIVED);
        assertThat(current.getTitle()).isEqualTo("AI主题");
        assertThat(current.getTitleSource()).isEqualTo(AiConversationTitleSource.AI);
        assertThat(current.getTitleState()).isEqualTo(AiConversationTitleState.FINALIZED);
    }

    @Test
    void lateAiCompletionCannotOverwriteManualRenameOrRunTwice() {
        var c = create(null);
        service.autoTitle(c, "帮我设计一个奇幻故事"); em.flush(); em.clear();
        assertThat(conversations.finalizeGeneratedTitle(c.getId(), "奇幻故事设计", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isEqualTo(1);
        assertThat(conversations.finalizeGeneratedTitle(c.getId(), "重复结果", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isZero();
        em.clear();
        assertThat(service.require(user, c.getConversationUuid()).getTitle()).isEqualTo("奇幻故事设计");
        service.rename(user, c.getConversationUuid(), "我的手动标题"); em.flush(); em.clear();
        assertThat(conversations.finalizeGeneratedTitle(c.getId(), "过期结果", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isZero();
        service.autoTitle(c, "后续消息"); service.touch(c); em.flush(); em.clear();
        assertThat(service.require(user, c.getConversationUuid()).getTitle()).isEqualTo("我的手动标题");
    }

    @Test
    void manualRenameWhileGeneratingWinsAndExpiredTasksKeepFallback() {
        var c = create(null); service.autoTitle(c, "第一次消息");
        service.rename(user, c.getConversationUuid(), "用户改名"); em.flush(); em.clear();
        assertThat(conversations.finalizeGeneratedTitle(c.getId(), "迟到的AI标题", AiConversationTitleSource.AI,
                AiConversationTitleState.FINALIZED)).isZero();
        var current = service.require(user, c.getConversationUuid());
        current.setTitle("兜底消息"); current.setTitleSource(AiConversationTitleSource.FALLBACK);
        current.setTitleState(AiConversationTitleState.GENERATING);
        current.setTitleGenerationStartedAt(java.time.OffsetDateTime.now().minusMinutes(3)); em.flush();
        assertThat(conversations.finalizeExpiredTitles(java.time.OffsetDateTime.now().minusMinutes(2))).isEqualTo(1);
        em.clear();
        var recovered = service.require(user, c.getConversationUuid());
        assertThat(recovered.getTitle()).isEqualTo("兜底消息");
        assertThat(recovered.getTitleState()).isEqualTo(AiConversationTitleState.FINALIZED);
        assertThat(recovered.getTitleGenerationStartedAt()).isNull();
    }

    @Test
    void mangaAgentStillAllowsOnlyOneActiveConversationPerChapter() {
        var first = new MangaAgentConversation();
        first.setUser(user); first.setStory(chapter.getStory()); first.setChapter(chapter);
        first.setTitle("Manga A"); first.setConversationType(AiConversationType.MANGA_AGENT);
        conversations.saveAndFlush(first);
        var second = new MangaAgentConversation();
        second.setUser(user); second.setStory(chapter.getStory()); second.setChapter(chapter);
        second.setTitle("Manga B"); second.setConversationType(AiConversationType.MANGA_AGENT);
        assertThatThrownBy(() -> conversations.saveAndFlush(second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private ChatMessage saveMessage(MangaAgentConversation conversation, String text) {
        return preparation.saveMessage(preparation.resolve(user.getId(), chapter.getId(), conversation.getConversationUuid()),
                MessageRole.USER, text, UUID.randomUUID(), null);
    }
}
