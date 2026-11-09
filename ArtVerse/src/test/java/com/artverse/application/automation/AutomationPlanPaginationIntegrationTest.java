package com.artverse.application.automation;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentTaskType;
import com.artverse.domain.*;
import com.artverse.application.tools.AutomationReadAgentTools;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production SQL, transaction proxy and native Toolkit; isolated database and no provider calls. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AutomationStore.class, AutomationPlanPaginationIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AutomationPlanPaginationIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE); }
    }
    @Autowired AutomationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    long actor;
    @BeforeEach void createActor() {
        String name = "page_" + UUID.randomUUID().toString().substring(0, 12);
        actor = jdbc.queryForObject("INSERT INTO users(username,email,password_hash,role,created_at) VALUES (?,?,?,'USER',now()) RETURNING id",
                Long.class, name, name + "@example.test", "fixture-only");
    }

    @Test void readsEveryPlanPastOneHundredWithStableEqualTimestampOrderingAndStoryIsolation() {
        for (int i = 0; i < 121; i++) insert(actor, 101, "ACTIVE", "AGENT");
        for (int i = 0; i < 130; i++) insert(actor, 202, "ACTIVE", "AGENT");
        var expected = jdbc.queryForList("SELECT id FROM automation WHERE user_id=? AND definition->'payload'->>'storyId'='101' ORDER BY created_at DESC,id DESC", UUID.class, actor);
        List<UUID> actual = new ArrayList<>();
        String cursor = null, snapshot = null;
        int pages = 0;
        do {
            var page = store.planPageForStory(actor, 101, null, null, cursor, 20);
            if (snapshot == null) snapshot = page.snapshot();
            assertThat(page.snapshot()).isEqualTo(snapshot);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(20);
            actual.addAll(page.items().stream().map(AutomationStore.Plan::id).toList());
            cursor = page.nextCursor();
            assertThat(page.hasMore()).isEqualTo(cursor != null);
            assertThat(++pages).isLessThan(10);
        } while (cursor != null);
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(7);
    }

    @Test void cursorCannotChangeActorStoryOrFiltersAndLegacyAnchorAlsoChecksStory() {
        insert(actor, 101, "ACTIVE", "AGENT"); insert(actor, 101, "ACTIVE", "AGENT");
        UUID otherStory = insert(actor, 202, "ACTIVE", "AGENT");
        String cursor = store.planPageForStory(actor, 101, "ACTIVE", "AGENT", null, 1).nextCursor();
        assertThatThrownBy(() -> store.planPageForStory(actor, 202, "ACTIVE", "AGENT", cursor, 1)).hasMessageContaining("不一致");
        assertThatThrownBy(() -> store.planPageForStory(actor + 1, 101, "ACTIVE", "AGENT", cursor, 1)).hasMessageContaining("不一致");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, "AGENT", cursor, 1)).hasMessageContaining("不一致");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, "ACTIVE", "IMAGE", cursor, 1)).hasMessageContaining("不一致");
        assertThatThrownBy(() -> store.plansForStory(actor, 101, otherStory, 20)).hasMessageContaining("不属于当前作品");
    }

    @Test void changesToAnExistingPlanOrResultSetRequireFreshSnapshot() {
        UUID first = insert(actor, 101, "ACTIVE", "AGENT"); insert(actor, 101, "ACTIVE", "AGENT");
        var page = store.planPageForStory(actor, 101, null, null, null, 1);
        jdbc.update("UPDATE automation SET next_run_at=clock_timestamp() WHERE id=?", first);
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, page.nextCursor(), 1))
                .hasMessageContaining("STALE_VERSION");
        var fresh = store.planPageForStory(actor, 101, null, null, null, 1);
        insert(actor, 101, "ACTIVE", "AGENT");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, fresh.nextCursor(), 1))
                .hasMessageContaining("STALE_VERSION");
    }

    @Test void filteredPagingDoesNotDiscardMatchingPlansAndOtherStoriesDoNotInvalidateSnapshot() {
        for (int i = 0; i < 3; i++) insert(actor, 101, "PAUSED", "IMAGE");
        insert(actor, 101, "ACTIVE", "AGENT");
        var page = store.planPageForStory(actor, 101, "PAUSED", "IMAGE", null, 2);
        assertThat(page.items()).hasSize(2).allMatch(p -> p.status().equals("PAUSED") && p.definition().taskType().equals("IMAGE"));
        insert(actor, 202, "ACTIVE", "AGENT");
        var last = store.planPageForStory(actor, 101, "PAUSED", "IMAGE", page.nextCursor(), 2);
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse(); assertThat(last.nextCursor()).isNull();
        assertThat(last.snapshot()).isEqualTo(page.snapshot());
    }

    @Test void malformedExpiredAndOutOfRangeCursorsAreRejected() throws Exception {
        insert(actor, 101, "ACTIVE", "AGENT"); insert(actor, 101, "ACTIVE", "AGENT");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, "bad cursor", 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, null, 0)).hasMessageContaining("1–50");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, null, 51)).hasMessageContaining("1–50");
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, "UNKNOWN", null, null, 20)).hasMessageContaining("筛选无效");
        var page = store.planPageForStory(actor, 101, null, null, null, 1);
        var encoded = mapper.readTree(Base64.getUrlDecoder().decode(page.nextCursor()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).put("expires_at", Instant.now().minusSeconds(60).toString());
        String expired = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(encoded));
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, expired, 1)).hasMessageContaining("STALE_VERSION");
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).put("expires_at", Instant.now().plusSeconds(60).toString())
                .put("last_id", UUID.randomUUID().toString());
        String noAnchor = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(encoded));
        assertThatThrownBy(() -> store.planPageForStory(actor, 101, null, null, noAnchor, 1)).hasMessageContaining("末项无效");
    }

    @Test void nativeSchemaExposesOnlyPagingInputsAndNativeCallsReachCompletePages() throws Exception {
        var context = interactiveContext();
        insert(actor, context.storyId(), "ACTIVE", "AGENT"); insert(actor, context.storyId(), "ACTIVE", "AGENT");
        var scope = mock(MangaToolSupport.class);
        when(scope.resolveContext(any())).thenReturn(context);
        var commands = new AutomationAgentTools(mock(AutomationService.class), store, mock(AutomationInputService.class), mapper);
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new AutomationReadAgentTools(commands, scope)).apply();
        var schema = mapper.valueToTree(toolkit.getTool("list_automations").getParameters());
        List<String> fields = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("status", "task_type", "cursor", "limit");
        assertThat(schema.path("required").isMissingNode() || schema.path("required").isEmpty()).isTrue();
        assertThat(schema.toString()).contains("ACTIVE", "PAUSED", "AGENT", "IMAGE").doesNotContain("user_id", "story_id");
        String cursor = null;
        for (int i = 0; i < 2; i++) {
            var input = new java.util.LinkedHashMap<String, Object>();
            input.put("status", "ACTIVE"); input.put("task_type", "AGENT"); input.put("limit", 1);
            if (cursor != null) input.put("cursor", cursor);
            var call = new ToolUseBlock("page-" + i, "list_automations", input, mapper.writeValueAsString(input), Map.of());
            var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(input)
                    .runtimeContext(RuntimeContext.empty()).build()).block(Duration.ofSeconds(5));
            assertThat(result).isNotNull();
            String text = result.getOutput().stream().filter(io.agentscope.core.message.TextBlock.class::isInstance)
                    .map(io.agentscope.core.message.TextBlock.class::cast).map(io.agentscope.core.message.TextBlock::getText)
                    .reduce("", String::concat);
            var body = mapper.readTree(text);
            assertThat(body.path("items").size()).isEqualTo(1);
            assertThat(body.path("has_more").asBoolean()).isEqualTo(i == 0);
            cursor = body.path("next_cursor").isNull() ? null : body.path("next_cursor").asText();
        }
        assertThat(cursor).isNull();
    }

    private MangaAgentRuntimeContext interactiveContext() {
        return new TransactionTemplate(transactions).execute(tx -> {
            User user = em.find(User.class, actor);
            var story = new Story(); story.setUser(user); story.setTitle("Pagination fixture"); em.persist(story);
            var chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); em.persist(chapter);
            var conversation = new MangaAgentConversation(); conversation.setUser(user); conversation.setStory(story);
            conversation.setChapter(chapter); conversation.setTitle("Pagination fixture");
            conversation.setConversationType(AiConversationType.STORY_CHAT); em.persist(conversation);
            var run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter);
            run.setConversation(conversation); run.setRunType(AgentRunType.STORY_CHAT); run.setInputMessage("List plans");
            run.setFencingToken(1L); run.setOwnerInstanceId("pagination-fixture");
            run.setLeaseUntil(java.time.OffsetDateTime.now().plusMinutes(5)); em.persist(run); em.flush();
            return new MangaAgentRuntimeContext(actor, story.getId(), chapter.getId(), conversation.getConversationUuid(),
                    run.getRequestId(), null, AgentTaskType.STORY_CHAT_READ, "read", 1L);
        });
    }

    private UUID insert(long userId, long storyId, String status, String taskType) {
        UUID id = UUID.randomUUID();
        var definition = new AutomationDefinition("fixture", taskType, taskType.equals("AGENT") ? "STORY_CHAT" : "MANGA_IMAGE", "fixture",
                new AutomationDefinition.Schedule("DAILY", null, "09:00", "Asia/Shanghai", List.of()),
                mapper.createObjectNode().put("storyId", storyId).put("chapterId", 1),
                new AutomationDefinition.Limits(1, 2, 60), "RUN_LATEST", "SKIP", null, null, "CONVERSATION", null);
        jdbc.update("""
                INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,status,idempotency_key,request_hash,created_at,updated_at)
                VALUES (?,?,?,?,?,?::jsonb,'Asia/Shanghai',?,?,?,?::timestamptz,?::timestamptz)
                """, id, userId, definition.name(), taskType, definition.executorKey(), store.json(definition), status,
                id.toString(), "0".repeat(64), "2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z");
        return id;
    }
}
