package com.artverse.application.automation;

import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Resolve actual persisted source, including partial attachments; never rely on equal unrelated UUIDs. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AutomationStore.class, AutomationPlanPaginationIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AutomationInteractiveSourceIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired AutomationStore store;
    @Autowired ObjectMapper mapper;

    @Test void ownedInteractiveReadWriteAndMangaConversationAreAllowed() {
        for (var type : new AgentTaskType[]{AgentTaskType.STORY_CHAT_READ, AgentTaskType.STORY_CHAT_WRITE, AgentTaskType.MANGA_CONVERSATION}) {
            var fixture = fixture(type);
            assertThatCode(() -> tools().requireInteractive(fixture.context())).doesNotThrowAnyException();
        }
    }

    @Test void realBackgroundAttachmentIsRejectedEvenWhenAllThreeUuidsDiffer() {
        var fixture = fixture(AgentTaskType.STORY_CHAT_WRITE);
        UUID parent = parent(fixture, UUID.randomUUID());
        assertThat(parent).isNotEqualTo(fixture.context().requestId()).isNotEqualTo(fixture.context().conversationId());
        attach(parent, fixture.run(), fixture.context().requestId(), fixture.context().conversationId());
        assertThatThrownBy(() -> tools().requireInteractive(fixture.context())).hasMessageContaining("后台定时任务");
    }

    @Test void partialAndInconsistentBackgroundAttachmentsCannotFallBackToInteractive() {
        var pendingChild = fixture(AgentTaskType.STORY_CHAT_READ);
        attach(parent(pendingChild, UUID.randomUUID()), null, pendingChild.context().requestId(), UUID.randomUUID());
        assertThatThrownBy(() -> tools().requireInteractive(pendingChild.context())).hasMessageContaining("后台定时任务");
        var corruptIdentifiers = fixture(AgentTaskType.STORY_CHAT_READ);
        attach(parent(corruptIdentifiers, UUID.randomUUID()), corruptIdentifiers.run(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> tools().requireInteractive(corruptIdentifiers.context())).hasMessageContaining("后台定时任务");
        var conversationOnly = fixture(AgentTaskType.STORY_CHAT_READ);
        attach(parent(conversationOnly, UUID.randomUUID()), null, null, conversationOnly.context().conversationId());
        assertThatThrownBy(() -> tools().requireInteractive(conversationOnly.context())).hasMessageContaining("后台定时任务");
    }

    @Test void persistedAutomationConversationMarkerRemainsDeniedWithoutAttachment() {
        var fixture = fixture(AgentTaskType.STORY_CHAT_READ);
        jdbc.update("UPDATE ai_conversations SET legacy_import_key=? WHERE conversation_uuid=?",
                "automation:" + UUID.randomUUID(), fixture.context().conversationId());
        assertThatThrownBy(() -> tools().requireInteractive(fixture.context())).hasMessageContaining("后台定时任务");
    }

    @Test void coincidentParentUuidDoesNotClassifyAnUnattachedInteractiveConversationAsBackground() {
        var fixture = fixture(AgentTaskType.STORY_CHAT_READ);
        parent(fixture, fixture.context().conversationId());
        assertThatCode(() -> tools().requireInteractive(fixture.context())).doesNotThrowAnyException();
    }

    @Test void wrongScopeMissingRunAndStoppedOrTransferredLeaseAreRejected() {
        var fixture = fixture(AgentTaskType.STORY_CHAT_READ);
        var context = fixture.context();
        var foreignScopes = new MangaAgentRuntimeContext[]{
                new MangaAgentRuntimeContext(context.userId() + 100000, context.storyId(), context.chapterId(),
                        context.conversationId(), context.requestId(), null, context.taskType(), context.stepId(), context.fencingToken()),
                new MangaAgentRuntimeContext(context.userId(), context.storyId() + 100000, context.chapterId(),
                        context.conversationId(), context.requestId(), null, context.taskType(), context.stepId(), context.fencingToken()),
                new MangaAgentRuntimeContext(context.userId(), context.storyId(), context.chapterId() + 100000,
                        context.conversationId(), context.requestId(), null, context.taskType(), context.stepId(), context.fencingToken()),
                new MangaAgentRuntimeContext(context.userId(), context.storyId(), context.chapterId(),
                        UUID.randomUUID(), context.requestId(), null, context.taskType(), context.stepId(), context.fencingToken()),
                new MangaAgentRuntimeContext(context.userId(), context.storyId(), context.chapterId(),
                        context.conversationId(), UUID.randomUUID(), null, context.taskType(), context.stepId(), context.fencingToken()),
                new MangaAgentRuntimeContext(context.userId(), context.storyId(), context.chapterId(),
                        context.conversationId(), context.requestId(), null, AgentTaskType.MANGA_CONVERSATION, context.stepId(), context.fencingToken())
        };
        for (var foreign : foreignScopes)
            assertThatThrownBy(() -> tools().requireInteractive(foreign)).hasMessageContaining("归属不完整");
        jdbc.update("UPDATE manga_agent_runs SET fencing_token=fencing_token+1 WHERE id=?", fixture.run());
        assertThatThrownBy(() -> tools().requireInteractive(context)).hasMessageContaining("RUN_NOT_ACTIVE");
        jdbc.update("UPDATE manga_agent_runs SET fencing_token=?,lease_until=clock_timestamp()-interval '1 second' WHERE id=?", context.fencingToken(), fixture.run());
        assertThatThrownBy(() -> tools().requireInteractive(context)).hasMessageContaining("RUN_NOT_ACTIVE");
        jdbc.update("UPDATE manga_agent_runs SET lease_until=clock_timestamp()+interval '1 minute',status='CANCELLED' WHERE id=?", fixture.run());
        assertThatThrownBy(() -> tools().requireInteractive(context)).hasMessageContaining("RUN_NOT_ACTIVE");
        jdbc.update("UPDATE manga_agent_runs SET status='RUNNING',owner_instance_id=NULL WHERE id=?", fixture.run());
        assertThatThrownBy(() -> tools().requireInteractive(context)).hasMessageContaining("RUN_NOT_ACTIVE");
    }

    private AutomationAgentTools tools() {
        return new AutomationAgentTools(mock(AutomationService.class), store, mock(AutomationInputService.class), mapper);
    }
    private Fixture fixture(AgentTaskType taskType) {
        return new TransactionTemplate(transactions).execute(tx -> {
            String name = "scope_" + UUID.randomUUID().toString().substring(0, 12);
            var user = new User(); user.setUsername(name); user.setEmail(name + "@example.test");
            user.setPasswordHash("isolated-fixture"); user.setRole(Role.USER); em.persist(user);
            var story = new Story(); story.setUser(user); story.setTitle("Interactive source fixture"); em.persist(story);
            var chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); em.persist(chapter);
            boolean manga = taskType == AgentTaskType.MANGA_CONVERSATION;
            var conversation = new MangaAgentConversation(); conversation.setUser(user); conversation.setStory(story);
            conversation.setChapter(chapter); conversation.setTitle("Interactive source fixture");
            conversation.setConversationType(manga ? AiConversationType.MANGA_AGENT : AiConversationType.STORY_CHAT); em.persist(conversation);
            var run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter);
            run.setConversation(conversation); run.setRunType(manga ? AgentRunType.MANGA_AGENT : AgentRunType.STORY_CHAT);
            run.setInputMessage("List plans"); run.setFencingToken(1L); run.setOwnerInstanceId("source-fixture");
            run.setLeaseUntil(OffsetDateTime.now().plusMinutes(5)); em.persist(run); em.flush();
            var context = new MangaAgentRuntimeContext(user.getId(), story.getId(), chapter.getId(), conversation.getConversationUuid(),
                    run.getRequestId(), null, taskType, "source-step", 1L);
            return new Fixture(run.getId(), context);
        });
    }
    private UUID parent(Fixture fixture, UUID parentId) {
        UUID plan = UUID.randomUUID();
        String definition = "{}";
        jdbc.update("INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash) VALUES (?,?,'Source fixture','AGENT','STORY_CHAT',?::jsonb,'Asia/Shanghai',?,'fixture')",
                plan, fixture.context().userId(), definition, plan.toString());
        jdbc.update("INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,definition_snapshot,state) VALUES (?,?,?,0,'MANUAL',now(),?,?::jsonb,'RUNNING')",
                parentId, plan, fixture.context().userId(), parentId.toString(), definition);
        return parentId;
    }
    private void attach(UUID parent, Long child, UUID request, UUID conversation) {
        jdbc.update("INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,request_id,conversation_uuid) VALUES (?,'agent','STORY_CHAT',?,?,?)",
                parent, child, request, conversation);
    }
    private record Fixture(long run, MangaAgentRuntimeContext context) {}
}
