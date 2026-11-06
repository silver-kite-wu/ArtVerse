package com.artverse.application;

import com.artverse.agent.*;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Isolated PostgreSQL, production migrations and real draft/review transactions; no provider or user DB. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NovelModelReadService.class, NovelHistoryAgentService.class, AgentArtifactAccessService.class,
        NovelContentArtifactService.class, NovelContentService.class, ChapterAccessService.class,
        MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentRunLeaseService.class,
        AgentOutboxService.class, AgentToolEffectService.class, AgentArtifactHistoryIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AgentArtifactHistoryIntegrationTest {
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
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean ArtVerseProperties properties() { return new ArtVerseProperties(); }
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AgentRunLeaseService leases;
    @Autowired MangaAgentRunService runService;
    @Autowired NovelModelReadService reads;
    @Autowired NovelHistoryAgentService history;
    @Autowired AgentArtifactAccessService access;
    @Autowired NovelContentArtifactService drafts;
    @Autowired ChapterNovelRevisionRepository revisions;

    @Test void onlyCompleteNativePagesHandedToAModelUnlockServerOwnedHistoricalRestoreAndAuthorReview() {
        var f = fixture("当前正文。".repeat(1900), "历史正文。".repeat(2100));
        assertThatThrownBy(() -> history.restore(f.revision(), f.context())).hasMessageContaining("INCOMPLETE_READ");
        var first = reads.readCurrent(f.context(), null, null, 8000);
        assertThat(first.get("novel_content").toString()).hasSize(8000);
        assertThat(first).containsEntry("has_more", true);
        reads.recordReadResult(f.context(), "page-1", first);
        assertThatThrownBy(() -> history.restore(f.revision(), f.context())).hasMessageContaining("INCOMPLETE_READ");
        handoff(f.context(), "page-1", first);
        assertThatThrownBy(() -> history.restore(f.revision(), f.context())).hasMessageContaining("INCOMPLETE_READ");
        var second = reads.readCurrent(f.context(), null, first.get("next_cursor").toString(), 8000);
        reads.recordReadResult(f.context(), "page-2", second); handoff(f.context(), "page-2", second);
        assertThat(reads.requireCurrentBaseline(f.context())).isEqualTo(f.version());
        var draft = history.restore(f.revision(), f.context());
        assertThat(draft.payload()).containsEntry("content", f.oldText()).containsEntry("base_content", f.current())
                .containsEntry("restore_revision_id", f.revision().intValue());
        assertThat(chapter(f).getNovelContent()).isEqualTo(f.current());
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.context().chapterId())).hasSize(1);
        jdbc.update("UPDATE manga_agent_runs SET status='WAITING_USER' WHERE id=?", f.runId());
        var saved = drafts.review(f.context().userId(), f.context().chapterId(), f.context().requestId(), draft.artifactId(),
                "confirm", f.oldText(), f.version(), draft.payload().get("content_hash").toString());
        assertThat(saved.status()).isEqualTo("COMMITTED");
        assertThat(chapter(f).getNovelContent()).isEqualTo(f.oldText());
        var versions = revisions.findByChapterIdOrderByRevisionNumberDesc(f.context().chapterId());
        assertThat(versions).hasSize(2); assertThat(versions.getFirst().getSource()).isEqualTo(NovelContentRevisionSource.RESTORE);
        assertThat(versions.getLast().getContent()).isEqualTo(f.oldText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_outbox_events WHERE aggregate_id=? AND event_type='CHAPTER_CONTENT_CHANGED'",
                Long.class, f.context().chapterId().toString())).isEqualTo(1);
    }

    @Test void progressUpdatesCannotOverwriteNativeReadEvidence() {
        var f = fixture("当前正文", "历史正文");
        jdbc.update("""
                UPDATE manga_agent_runs
                SET run_attributes_json='{"novel_reads":{"read-ref":{"phase":"RESULT_FORMED"}}}'::jsonb
                WHERE id=?
                """, f.runId());
        MangaAgentRun detached = tx().execute(status -> em.find(MangaAgentRun.class, f.runId()));
        runService.recordProgress(detached, "TOOL");
        var row = jdbc.queryForMap("""
                SELECT current_phase, run_attributes_json::text AS attributes
                FROM manga_agent_runs WHERE id=?
                """, f.runId());
        assertThat(row.get("current_phase")).isEqualTo("TOOL");
        assertThat(row.get("attributes").toString()).contains("novel_reads", "RESULT_FORMED");
    }

    @Test void alteredNativeResultsMissingPagesAndChangedCanonicalTextNeverProduceADraft() {
        var f = fixture("当前正文", "旧稿");
        var result = reads.readCurrent(f.context()); reads.recordReadResult(f.context(), "read", result);
        var forged = new LinkedHashMap<>(result); forged.put("novel_content", "伪装全文");
        assertThatThrownBy(() -> handoff(f.context(), "read", forged)).hasMessageContaining("READ_RECEIPT_UNAVAILABLE");
        assertThatThrownBy(() -> history.draft("新稿", f.context())).hasMessageContaining("INCOMPLETE_READ");
        handoff(f.context(), "read", result);
        tx().executeWithoutResult(status -> em.find(Chapter.class, f.context().chapterId()).setNovelContent("作者已另存"));
        assertThatThrownBy(() -> history.restore(f.revision(), f.context())).hasMessageContaining("INCOMPLETE_READ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_artifacts WHERE run_id=?", Long.class, f.runId())).isZero();
    }

    @Test void volatileAuditEnvelopeDoesNotBreakTheNativeReadHandoff() {
        var f = fixture("当前正文", "旧稿");
        var result = reads.readCurrent(f.context());
        var formed = new LinkedHashMap<>(result);
        formed.putAll(Map.of("success", true, "errorCode", "", "retryable", false,
                "auditId", UUID.randomUUID().toString(), "resultHash", "first-audit-hash"));
        reads.recordReadResult(f.context(), "read", formed);
        var handed = new LinkedHashMap<>(result);
        handed.putAll(Map.of("success", true, "errorCode", "", "retryable", false,
                "auditId", UUID.randomUUID().toString(), "resultHash", "later-audit-hash"));
        assertThatCode(() -> handoff(f.context(), "read", handed)).doesNotThrowAnyException();
        assertThat(reads.requireCurrentBaseline(f.context())).isEqualTo(f.version());
    }

    @Test void modelReachableNativeReadCanFormEvidenceWhenTheEventRegistryMissesIt() {
        var f = fixture("当前正文", "旧稿");
        var result = reads.readCurrent(f.context());
        var input = List.of(new StoryboardArtifactService.ModelReadInput("native-read", result));
        reads.validateModelReadInput(f.context(), input);
        assertThat(jdbc.queryForObject("""
                SELECT (run_attributes_json->'novel_reads')::text
                FROM manga_agent_runs WHERE id=?
                """, String.class, f.runId())).contains("native-read", "RESULT_FORMED");
        reads.recordModelReadInput(f.context(), UUID.randomUUID(), "b".repeat(64), input);
        assertThat(reads.requireCurrentBaseline(f.context())).isEqualTo(f.version());
    }

    @Test void earlierNativeReadDoesNotBlockALaterModelRequest() {
        var f = fixture("当前正文", "旧稿");
        var earlier = reads.readCurrent(f.context());
        reads.recordReadResult(f.context(), "earlier-read", earlier);
        var next = nextRun(f);
        var historicalInput = List.of(new StoryboardArtifactService.ModelReadInput("earlier-read", earlier));

        assertThatCode(() -> reads.validateModelReadInput(next, historicalInput)).doesNotThrowAnyException();
        assertThatCode(() -> reads.recordModelReadInput(next, UUID.randomUUID(), "c".repeat(64), historicalInput))
                .doesNotThrowAnyException();
    }

    @Test void explicitSameStoryChapterReadCannotUnlockCurrentChapterAndOtherStoriesAreRejected() {
        var f = fixture("当前", "历史");
        long other = tx().execute(status -> {
            var chapter = new Chapter(); chapter.setStory(em.find(Story.class, f.context().storyId()));
            chapter.setChapterNumber(2); chapter.setNovelContent("另一章正文"); em.persist(chapter); em.flush(); return chapter.getId();
        });
        var page = reads.readCurrent(f.context(), other, null, null);
        assertThat(page).containsEntry("chapter_id", other).containsEntry("historical_target", true);
        reads.recordReadResult(f.context(), "other-chapter", page); handoff(f.context(), "other-chapter", page);
        assertThatThrownBy(() -> reads.requireCurrentBaseline(f.context())).hasMessageContaining("INCOMPLETE_READ");
        var foreign = fixture("另一个故事", "旧稿");
        assertThatThrownBy(() -> reads.readCurrent(f.context(), foreign.context().chapterId(), null, null)).hasMessageContaining("READ_RECEIPT_UNAVAILABLE");
        assertThatThrownBy(() -> history.get(f.context(), foreign.revision(), null, null)).hasMessageContaining("not found");
    }

    @Test void artifactPaginationExcludesCredentialsAndDraftDiscardUsesVersionCasAndIsIdempotent() {
        var f = fixture("当前", "旧稿"); prove(f.context());
        var a = history.draft("第一份草稿", f.context()); var b = history.draft("第二份草稿", f.context());
        var page = access.list(f.context(), null, null, null, 1);
        assertThat(page).containsEntry("has_more", true);
        var next = access.list(f.context(), null, null, page.get("next_cursor").toString(), 1);
        assertThat(next).containsEntry("has_more", false);
        assertThat(mapper.valueToTree(page).toString()).doesNotContain("base_content", "execution_owner", "read_ref", "overwrite_authorization");
        var view = access.get(f.context(), a.artifactId(), null, 3);
        assertThat(view.get("content").toString()).hasSize(3); assertThat(view).containsEntry("has_more", true);
        assertThatThrownBy(() -> access.discard(f.context(), a.artifactId(), "stale")).hasMessageContaining("版本");
        String token = view.get("artifact_version").toString();
        assertThat(access.discard(f.context(), a.artifactId(), token)).containsEntry("status", "DISCARDED");
        assertThat(access.discard(f.context(), a.artifactId(), token)).containsEntry("status", "DISCARDED");
        assertThatThrownBy(() -> access.get(f.context(), a.artifactId(), view.get("next_cursor").toString(), 3)).hasMessageContaining("版本");
        jdbc.update("UPDATE manga_agent_runs SET user_input_request_json='{}' WHERE id=?", f.runId());
        var second = access.get(f.context(), b.artifactId(), null, null);
        assertThatThrownBy(() -> access.discard(f.context(), b.artifactId(), second.get("artifact_version").toString())).hasMessageContaining("SCOPE_DENIED");
        assertThat(chapter(f).getNovelContent()).isEqualTo(f.current());
    }

    @Test void conversationScopeAndOriginalOwnerPreventHistoricalArtifactAndDraftAccess() {
        var f = fixture("当前", "旧稿"); prove(f.context()); var a = history.draft("草稿", f.context());
        var c = f.context();
        var wrongOwner = new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), c.conversationId(), c.requestId(), null,
                c.taskType(), c.stepId(), c.fencingToken(), null, "replacement");
        assertThatThrownBy(() -> access.get(wrongOwner, a.artifactId(), null, null)).hasMessageContaining("owner");
        var wrongConversation = new MangaAgentRuntimeContext(c.userId(), c.storyId(), c.chapterId(), UUID.randomUUID(), c.requestId(), null,
                c.taskType(), c.stepId(), c.fencingToken(), null, c.executionOwner());
        assertThatThrownBy(() -> access.get(wrongConversation, a.artifactId(), null, null)).isInstanceOf(com.artverse.common.BusinessException.class);
        assertThatThrownBy(() -> history.restore(f.revision(), wrongOwner)).hasMessageContaining("owner");
    }

    @Test void nativeRestoreAndDiscardCommitOnlyWithTheirReservedAttemptAndKeepImmutableEffects() throws Exception {
        var f = fixture("当前正文", "历史全文"); prove(f.context());
        var budget = new AgentBudgetService(jdbc, transactions, new ArtVerseProperties());
        var request = request(f);
        var restoreCall = new io.agentscope.core.message.ToolUseBlock("restore", "draft_novel_restore", Map.of("revision_id", f.revision()));
        var restoreAttempt = attempt(f, restoreCall);
        assertThatThrownBy(() -> history.restore(f.revision(), f.context(), restoreAttempt)).hasMessageContaining("TOOL_EFFECT_DENIED");
        assertThat(access.list(f.context(), null, null, null, 20).get("items")).isEqualTo(List.of());
        assertThat(budget.reserveToolCalls(request, List.of(restoreCall))).isTrue();
        var draft = history.restore(f.revision(), f.context(), restoreAttempt);
        String version = access.get(f.context(), draft.artifactId(), null, 8000).get("artifact_version").toString();
        var discardCall = new io.agentscope.core.message.ToolUseBlock("discard", "discard_artifact",
                Map.of("artifact_id", draft.artifactId().toString(), "expected_artifact_version", version));
        var discardAttempt = attempt(f, discardCall);
        assertThatThrownBy(() -> access.discard(f.context(), draft.artifactId(), version, discardAttempt)).hasMessageContaining("TOOL_EFFECT_DENIED");
        assertThat(access.get(f.context(), draft.artifactId(), null, 8000)).containsEntry("status", "VALIDATED");
        assertThat(budget.reserveToolCalls(request, List.of(discardCall))).isTrue();
        assertThat(access.discard(f.context(), draft.artifactId(), version, discardAttempt)).containsEntry("status", "DISCARDED");
        assertThat(access.discard(f.context(), draft.artifactId(), version, discardAttempt)).containsEntry("status", "DISCARDED");
        budget.observeToolResult(request, discardCall.getId(), discardCall.getName(), discardAttempt.inputHash(), io.agentscope.core.message.ToolResultState.ERROR);
        var evidence = jdbc.queryForList("SELECT metadata::text FROM agent_usage_ledger WHERE request_id=? AND usage_kind='TOOL_CALL' ORDER BY id", String.class, f.context().requestId());
        assertThat(evidence).hasSize(2).allSatisfy(row -> assertThat(row).contains("domain_effect", draft.artifactId().toString()));
        assertThat(String.join("", evidence)).contains("NOVEL_RESTORE_DRAFT", "ARTIFACT_DISCARD", "DISCARDED");
        var replacement = history.restore(f.revision(), f.context());
        assertThat(replacement.status()).isEqualTo("VALIDATED");
        assertThat(replacement.artifactId()).isNotEqualTo(draft.artifactId());
        assertThat(chapter(f).getNovelContent()).isEqualTo(f.current());
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.context().chapterId())).hasSize(1);
    }

    @Test void artifactListRejectsChangedSnapshotAndQueryWhileHistoryReadsNeverUnlockCurrentBaseline() {
        var f = fixture("当前正文", "历史全文"); prove(f.context());
        history.draft("草稿一", f.context()); history.draft("草稿二", f.context());
        var first = access.list(f.context(), null, null, null, 1);
        String cursor = first.get("next_cursor").toString();
        assertThatThrownBy(() -> access.list(f.context(), null, "VALIDATED", cursor, 1)).hasMessageContaining("分页");
        history.draft("新草稿", f.context());
        assertThatThrownBy(() -> access.list(f.context(), null, null, cursor, 1)).hasMessageContaining("分页");
        var other = fixture("当前正文", "历史全文");
        assertThat(history.get(other.context(), other.revision(), null, 2)).containsEntry("historical", true).containsEntry("has_more", true);
        assertThatThrownBy(() -> history.restore(other.revision(), other.context())).hasMessageContaining("INCOMPLETE_READ");
    }

    private AgentRunRequest request(Fixture f) {
        var c = f.context();
        return new AgentRunRequest(c.userId().toString(), c.storyId(), c.chapterId(), c.taskType(), List.of(),
                Map.of("step_id", c.stepId(), "fencing_token", c.fencingToken(), "execution_owner", c.executionOwner()),
                new AgentModelSpec("fixture", "https://fixture.invalid/v1", "fixture", "hash"), "", c.requestId(), c.conversationId(), BusinessSkillSelection.empty());
    }
    private AgentToolAttempt attempt(Fixture f, io.agentscope.core.message.ToolUseBlock call) {
        return AgentToolAttempt.capture(call, io.agentscope.core.agent.RuntimeContext.builder().put(MangaAgentRuntimeContext.class, f.context()).build());
    }

    private MangaAgentRuntimeContext nextRun(Fixture f) {
        return tx().execute(status -> {
            var previous = f.context();
            var oldRun = em.find(MangaAgentRun.class, f.runId());
            var run = new MangaAgentRun();
            run.setUser(em.find(User.class, previous.userId()));
            run.setStory(em.find(Story.class, previous.storyId()));
            run.setChapter(em.find(Chapter.class, previous.chapterId()));
            run.setConversation(oldRun.getConversation());
            run.setRunType(AgentRunType.STORY_CHAT);
            run.setRoute(com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION);
            run.setInputMessage("继续修改正文");
            run.setRouteKey("POLISH");
            run.setRunAttributesJson(json(Map.of("story_chat_execution", Map.of(
                    "version", 1, "profile", "local-tools-v1", "route", "POLISH",
                    "task_type", "STORY_CHAT_WRITE", "step_id", "story-chat:POLISH"))));
            em.persist(run);
            em.flush();
            leases.claim(run);
            return new MangaAgentRuntimeContext(
                    previous.userId(), previous.storyId(), previous.chapterId(), previous.conversationId(),
                    run.getRequestId(), null, AgentTaskType.STORY_CHAT_WRITE, "story-chat:POLISH",
                    run.getFencingToken(), null, run.getOwnerInstanceId());
        });
    }

    private void prove(MangaAgentRuntimeContext context) {
        var result = reads.readCurrent(context); reads.recordReadResult(context, "current", result); handoff(context, "current", result);
    }
    private void handoff(MangaAgentRuntimeContext context, String call, Map<String,Object> result) {
        var input = List.of(new StoryboardArtifactService.ModelReadInput(call, result));
        reads.validateModelReadInput(context, input); reads.recordModelReadInput(context, UUID.randomUUID(), "a".repeat(64), input);
    }
    private Chapter chapter(Fixture f) { return tx().execute(status -> em.find(Chapter.class, f.context().chapterId())); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private Fixture fixture(String current, String old) {
        return tx().execute(status -> {
            var user = new User(); user.setUsername("history_" + UUID.randomUUID()); user.setEmail(user.getUsername() + "@example.test");
            user.setPasswordHash("isolated-fixture"); user.setRole(Role.USER); em.persist(user);
            var story = new Story(); story.setTitle("History fixture"); story.setUser(user); em.persist(story);
            var chapter = new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); chapter.setNovelContent(current); em.persist(chapter);
            var conversation = new MangaAgentConversation(); conversation.setConversationUuid(UUID.randomUUID()); conversation.setTitle("History fixture");
            conversation.setUser(user); conversation.setStory(story); conversation.setChapter(chapter); conversation.setConversationType(AiConversationType.STORY_CHAT); em.persist(conversation);
            var run = new MangaAgentRun(); run.setUser(user); run.setStory(story); run.setChapter(chapter); run.setConversation(conversation);
            run.setRunType(AgentRunType.STORY_CHAT); run.setRoute(com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION); run.setInputMessage("修改正文"); run.setRouteKey("POLISH");
            run.setRunAttributesJson(json(Map.of("story_chat_execution", Map.of("version", 1, "profile", "local-tools-v1",
                    "route", "POLISH", "task_type", "STORY_CHAT_WRITE", "step_id", "story-chat:POLISH")))); em.persist(run);
            var revision = new ChapterNovelRevision(); revision.setChapter(chapter); revision.setRevisionNumber(1); revision.setContent(old);
            revision.setContentHash(ToolIdempotencyService.sha256(old)); revision.setSource(NovelContentRevisionSource.MANUAL); revision.setCreatedBy(user); em.persist(revision); em.flush();
            leases.claim(run);
            var context = new MangaAgentRuntimeContext(user.getId(), story.getId(), chapter.getId(), conversation.getConversationUuid(), run.getRequestId(), null,
                    AgentTaskType.STORY_CHAT_WRITE, "story-chat:POLISH", run.getFencingToken(), null, run.getOwnerInstanceId());
            return new Fixture(context, run.getId(), chapter.getVersion(), revision.getId(), current, old);
        });
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private record Fixture(MangaAgentRuntimeContext context, Long runId, Long version, Long revision, String current, String oldText) {}
}
