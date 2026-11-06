package com.artverse.application;

import com.artverse.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StoryChatPreparationService.class, ChapterAccessService.class, AiConversationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class StoryChatPreparationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Exercise the real production migrations/schema on an isolated disposable database.
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StoryChatPreparationService preparation;
    @MockBean MangaAgentRunService runLifecycle;
    @Autowired com.artverse.persistence.MangaAgentRunRepository runs;
    @Autowired com.artverse.persistence.MangaAgentRunEventRepository events;

    @Test
    void workerMaterializesSnapshotAndCanAssembleAfterSessionCloses() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String currentInput = "审核本章创作质量";
        Long[] ids = new TransactionTemplate(transactions).execute(status -> {
            User user = new User();
            user.setUsername("snapshot_" + UUID.randomUUID());
            user.setEmail(user.getUsername() + "@example.test");
            user.setPasswordHash("isolated-fixture"); user.setRole(Role.USER);
            em.persist(user);
            Story story = new Story(); story.setTitle("Snapshot fixture"); story.setUser(user); em.persist(story);
            Chapter chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1);
            chapter.setNovelContent("主角在雨夜发现了一封旧信。"); em.persist(chapter);
            MangaAgentConversation conversation = new MangaAgentConversation();
            conversation.setConversationUuid(conversationId); conversation.setTitle("Snapshot fixture");
            conversation.setConversationType(AiConversationType.STORY_CHAT);
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter); em.persist(conversation);
            em.flush();
            return new Long[]{user.getId(), chapter.getId()};
        });
        var scope = preparation.resolve(ids[0], ids[1], conversationId);
        var registered = new TransactionTemplate(transactions).execute(status ->
                new MangaAgentRunService(runs, events, new com.fasterxml.jackson.databind.ObjectMapper())
                        .startOrReuse(scope.reference(), requestId, currentInput, AgentRunType.STORY_CHAT,
                                com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION, "REVIEW"));
        assertThat(registered.getId()).isNotNull();
        preparation.saveMessage(scope, MessageRole.USER, "先前的问题", UUID.randomUUID(), null);
        preparation.saveMessage(scope, MessageRole.USER, currentInput, requestId, null);
        preparation.saveMessage(scope, MessageRole.USER, currentInput, requestId, null);
        assertThat(preparation.snapshot(scope, requestId, currentInput).history()).hasSize(1);

        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var snapshot = worker.submit(() -> preparation.snapshot(scope, requestId, currentInput)).get(10, TimeUnit.SECONDS);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(snapshot.novelContent()).contains("旧信");
            assertThatThrownBy(() -> snapshot.history().clear()).isInstanceOf(UnsupportedOperationException.class);
            KnowledgeService knowledge = mock(KnowledgeService.class);
            when(knowledge.recallForGeneration(anyLong(), anyLong(), anyInt(), anyString(), anyLong()))
                    .thenAnswer(invocation -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        return Optional.empty();
                    });
            var messages = new StoryChatRuntimeContextAssembler(knowledge).assemble(snapshot, currentInput, StoryChatRoute.REVIEW);
            assertThat(messages.getFirst().content()).contains("旧信", "REVIEW");
            assertThat(messages.stream().filter(message -> currentInput.equals(message.content()))).hasSize(1);
            // A resume prompt must retain the original user request even though it shares requestId.
            assertThat(preparation.snapshot(scope, requestId, "用户已确认写入草稿").history()).hasSize(2);
        }
        assertThatThrownBy(() -> preparation.resolve(ids[0] + 1000, ids[1], conversationId))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        assertThatThrownBy(() -> preparation.resolve(ids[0], ids[1] + 1000, conversationId))
                .isInstanceOf(com.artverse.common.BusinessException.class);
    }
}
