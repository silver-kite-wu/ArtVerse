package com.artverse.application;

import com.artverse.api.StoryChatController;
import com.artverse.agent.*;
import com.artverse.agent.gateway.*;
import com.artverse.agent.gateway.tools.*;
import com.artverse.application.tools.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.artverse.common.ApiResponseAdvice;
import com.artverse.common.GlobalExceptionHandler;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.*;
import com.artverse.persistence.ChapterNovelRevisionRepository;
import com.artverse.application.automation.*;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP mapping, transactions, production migrations and PostgreSQL writes; no model calls. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NativeNovelDraftService.class, NovelHistoryAgentService.class, AgentArtifactAccessService.class, NovelModelReadService.class, AgentToolEffectService.class, NovelDraftReviewService.class, NovelContentArtifactService.class, NovelContentService.class,
        StoryChatPreparationService.class, ChapterAccessService.class, AiConversationService.class,
        MangaAgentRunService.class, MangaAgentRunSnapshotReader.class, AgentOutboxService.class,
        NovelDraftReviewIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class NovelDraftReviewIntegrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    @Autowired NativeNovelDraftService nativeDrafts;
    @Autowired NovelHistoryAgentService nativeHistory;
    @Autowired AgentArtifactAccessService artifactAccess;
    @Autowired NovelModelReadService novelReads;
    @Autowired ChapterAccessService chapters;
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
            return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }
        @Bean ArtVerseProperties properties() { return new ArtVerseProperties(); }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NovelDraftReviewService review;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChapterNovelRevisionRepository revisions;
    @Autowired MangaAgentRunService runService;
    @Autowired NovelContentArtifactService artifacts;
    @Autowired NovelContentService contents;
    @SpyBean AgentRunLeaseService lease;

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        clearInvocations(lease);
        var controller = new StoryChatController(mock(StoryChatAgentService.class), mock(ApiKeyService.class),
                currentUser, mock(MangaAgentEventReplayService.class), review);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeDraftAuthorEditedSaveAndRebuiltSummaryShareTheExactDomainReceipt(boolean restore) throws Exception {
        var f = fixture();
        jdbc.update("DELETE FROM manga_agent_run_artifacts WHERE id=?", f.artifactId());
        Long historicalRevision = restore ? new TransactionTemplate(transactions).execute(tx -> {
            var revision = new ChapterNovelRevision(); revision.setChapter(em.find(Chapter.class, f.chapterId()));
            revision.setRevisionNumber(1); revision.setContent("历史稿：她已离开门前。");
            revision.setContentHash(ToolIdempotencyService.sha256(revision.getContent()));
            revision.setSource(NovelContentRevisionSource.MANUAL); revision.setCreatedBy(em.find(User.class, f.userId()));
            em.persist(revision); em.flush(); return revision.getId();
        }) : null;
        var conversation = new TransactionTemplate(transactions).execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId());
            run.setRoute(com.artverse.application.workflow.MangaWorkflowRoute.CONVERSATION);
            run.setRouteKey("POLISH"); run.setStatus(MangaAgentRunStatus.RUNNING); run.setUserInputRequestJson(null);
            run.setRunAttributesJson("{\"story_chat_execution\":{\"profile\":\"local-tools-v1\",\"route\":\"POLISH\",\"task_type\":\"STORY_CHAT_WRITE\",\"step_id\":\"story-chat:POLISH\",\"version\":1,\"skills\":[]}}");
            em.flush();
            lease.claim(run);
            org.hibernate.Hibernate.initialize(run.getConversation());
            org.hibernate.Hibernate.initialize(run.getConversation().getStory());
            org.hibernate.Hibernate.initialize(run.getConversation().getChapter());
            org.hibernate.Hibernate.initialize(run.getConversation().getUser());
            return run.getConversation();
        });
        var execution = new TransactionTemplate(transactions).execute(tx -> {
            var run = em.find(MangaAgentRun.class, f.runId());
            return Map.<String,Object>of("step_id", "story-chat:POLISH", "fencing_token", run.getFencingToken(), "execution_owner", run.getOwnerInstanceId());
        });
        var request = new AgentRunRequest(f.userId().toString(), conversation.getStory().getId(), f.chapterId(),
                AgentTaskType.STORY_CHAT_WRITE, List.of(new AgentMessage("user", restore ? "请恢复本章正文到历史版本" : "Polish the chapter")), execution,
                new AgentModelSpec("fixture", "https://fixture.invalid/v1", "fixture", "hash", null,
                        new AgentModelCapabilities(true, false, false, "fixture-v1")), "", f.requestId(), f.conversationId(), BusinessSkillSelection.empty());
        var status = mock(AgentRunToolStatus.class);
        var scope = new MangaToolSupport(status);
        var audit = new AgentToolAuditService(status);
        var hitl = new MangaHitlTools(audit, scope, runService);
        var reads = new StoryChatContextTools(scope, audit, chapters); reads.setNovelReads(novelReads);
        var drafts = new StoryChatNovelContentTools(scope, audit, artifacts); drafts.setNativeDrafts(nativeDrafts);
        var storyboard = mock(MangaStoryboardTools.class);
        var plans = mock(AutomationAgentTools.class);
        var registrations = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        registrations.registerBean(ObjectMapper.class, () -> mapper);
        registrations.registerBean(NovelModelReadService.class, () -> novelReads);
        registrations.registerBean(NativeNovelDraftService.class, () -> nativeDrafts);
        registrations.registerBean(NovelHistoryAgentService.class, () -> nativeHistory);
        registrations.registerBean(AutomationReadService.class, () -> mock(AutomationReadService.class));
        registrations.registerBean(MangaHitlTools.class, () -> hitl);
        registrations.registerBean(MangaContextTools.class, () -> new MangaContextTools(mock(MangaChapterContextReader.class)));
        registrations.registerBean(StoryChatContextTools.class, () -> reads);
        registrations.registerBean(StoryChatNovelContentTools.class, () -> drafts);
        registrations.registerBean(StoryboardAgentTools.class, () -> new StoryboardAgentTools(storyboard, mapper));
        registrations.registerBean(StoryboardCommitAgentTools.class, () -> new StoryboardCommitAgentTools(storyboard));
        registrations.registerBean(AutomationReadAgentTools.class, () -> new AutomationReadAgentTools(plans, scope));
        registrations.registerBean(AutomationManageAgentTools.class, () -> new AutomationManageAgentTools(plans, scope, mapper, audit));
        var storyReads = mock(AgentStoryKnowledgeReadService.class);
        registrations.registerBean(StoryContextReadTools.class, () -> new StoryContextReadTools(storyReads, scope));
        registrations.registerBean(KnowledgeReadTools.class, () -> new KnowledgeReadTools(storyReads, scope));
        registrations.registerBean(ReferenceAssetReadTools.class, () -> new ReferenceAssetReadTools(storyReads, scope));
        registrations.registerBean(KnowledgeCandidateReadTools.class, () -> new KnowledgeCandidateReadTools(storyReads, scope));
        registrations.registerBean(ArtifactReadAgentTools.class, () -> new ArtifactReadAgentTools(scope, audit, artifactAccess));
        registrations.registerBean(ArtifactDraftAgentTools.class, () -> new ArtifactDraftAgentTools(scope, audit, artifactAccess));
        registrations.registerBean(NovelHistoryAgentTools.class, () -> new NovelHistoryAgentTools(scope, audit, nativeHistory));
        registrations.registerBean(SettingsDraftAgentTools.class,
                () -> new SettingsDraftAgentTools(scope, audit, mock(SettingsProposalService.class)));
        registrations.registerBean(KnowledgeDraftAgentTools.class,
                () -> new KnowledgeDraftAgentTools(scope, audit, mock(KnowledgeProposalService.class)));
        registrations.registerBean(ImageProposalAgentTools.class,
                () -> new ImageProposalAgentTools(scope, audit,
                        mock(com.artverse.application.imageproposal.ImageProposalService.class)));
        var scanner = new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(registrations);
        scanner.addExcludeFilter((metadata, factory) -> metadata.getClassMetadata().getClassName().contains("Test$"));
        scanner.scan(AgentToolConfigurationRegistry.class.getPackageName());
        registrations.refresh();
        var registry = registrations.getBean(AgentToolConfigurationRegistry.class);
        var budget = new AgentBudgetService(jdbc, transactions, new ArtVerseProperties());
        var boundary = new AgentToolBoundaryMiddleware(runService, plans, budget); boundary.setNovelReads(novelReads);
        var state = new StrictAgentStateStore(new io.agentscope.core.state.InMemoryAgentStateStore());
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(), state, mock(AgentTurnStateStores.class),
                registry, mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), runService,
                mock(PostgresAgentWorkspaceStore.class), boundary);
        var rounds = new AtomicInteger();
        String finalText = "作者最终稿：雨停了，她推开门走向晨光。";
        Model model = new Model() {
            public String getModelName() { return "native-novel-review-fixture"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    int round = rounds.getAndIncrement();
                    ContentBlock output;
                    if (round == 0) output = nativeCall("novel-read", "get_novel_context", Map.of());
                    else if (round == 1) {
                        assertThat(nativeOutput(messages, "novel-read")).contains("雨没有停");
                        output = restore ? nativeCall("novel-draft", "draft_novel_restore", Map.of("revision_id", historicalRevision))
                                : nativeCall("novel-draft", "draft_novel_content", Map.of("content", "AI 草稿：雨停了，她仍守在门前。"));
                    } else if (round == 2) {
                        assertThat(nativeOutput(messages, "novel-draft")).contains("VALIDATED");
                        output = nativeCall("review-question", "ask_user", Map.of("question", "请审阅正文", "options", List.of("审阅", "放弃")));
                    } else if (round == 3) {
                        assertThat(tools).extracting(ToolSchema::getName).containsExactly("get_novel_context");
                        assertThat(nativeOutput(messages, "review-question")).contains(finalText, "COMMITTED", "reviewed_content");
                        output = TextBlock.builder().text("已保存作者最终稿。她推开门走向晨光。").build();
                    } else throw new AssertionError("Unexpected native round " + round);
                    return Flux.just(ChatResponse.builder().content(List.of(output)).finishReason(output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
                });
            }
        };
        HarnessAgent first = ReflectionTestUtils.invokeMethod(factory, "buildAgent", request, workspace, BusinessSkillSelection.empty(), model);
        var factorySpy = spy(factory); doReturn(first).when(factorySpy).getOrCreate(eq(request), any(AgentSessionTurns.Lease.class));
        var turns = mock(AgentSessionTurns.class);
        when(turns.acquire(any(AgentRunRequest.class))).thenAnswer(ignored -> mock(AgentSessionTurns.Lease.class));
        var gateway = new AgentScopeHarnessAgentGateway(factorySpy, new AgentScopeRuntimeContextFactory(), new ArtVerseProperties(),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(), budget, new AgentSessionHydrator(state), turns);
        ReflectionTestUtils.setField(gateway, "hitlTools", hitl); gateway.init();
        HarnessAgent summaryAgent = null;
        try {
            var events = gateway.streamEvents(request).collectList().block(Duration.ofSeconds(30));
            assertThat(events).anyMatch(io.agentscope.core.event.RequireExternalExecutionEvent.class::isInstance);
            var run = new TransactionTemplate(transactions).execute(tx -> em.find(MangaAgentRun.class, f.runId()));
            runService.markWaiting(conversation, f.requestId(), runService.waitingInput(run), run.getFencingToken());
            var draft = artifacts.list(f.userId(), f.chapterId(), f.requestId()).getFirst();
            var saved = review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(),
                    draft.artifactId(), "confirm", finalText, f.version(), draft.payload().get("content_hash").toString());
            assertThat(saved.status()).isEqualTo("COMMITTED");
            first.close();
            var claimed = runService.claimReviewedContinuation(conversation, f.requestId(), draft.artifactId());
            var variables = new java.util.LinkedHashMap<>(execution);
            variables.put("review_completed", true); variables.put("fencing_token", claimed.getFencingToken());
            variables.put("execution_owner", claimed.getOwnerInstanceId());
            var summary = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(), request.messages(),
                    Map.copyOf(variables), request.modelSpec(), "", request.requestId(), request.conversationId(), BusinessSkillSelection.empty());
            assertThatThrownBy(() -> runService.captureToolExecutionScope(request)).hasMessageContaining("TOOL_NOT_ALLOWED");
            summaryAgent = ReflectionTestUtils.invokeMethod(factory, "buildAgent", summary, workspace, BusinessSkillSelection.empty(), model);
            doReturn(summaryAgent).when(factorySpy).getOrCreate(eq(summary), any(AgentSessionTurns.Lease.class));
            var receipt = artifacts.reviewReceipt(f.userId(), f.chapterId(), f.requestId(), draft.artifactId());
            var receiptEncoder = mock(StoryChatAgentService.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(receiptEncoder, "objectMapper", mapper);
            var results = receiptEncoder.reviewedToolResults(List.of(ToolResultBlock.builder().id("review-question").name("ask_user").build()), receipt, finalText);
            var completed = gateway.streamEvents(summary, results).collectList().block(Duration.ofSeconds(30));
            assertThat(completed).anyMatch(io.agentscope.core.event.AgentResultEvent.class::isInstance);
            runService.markReviewSummarySucceeded(conversation, f.requestId(), draft.artifactId(), claimed.getFencingToken(), "已保存作者最终稿");
            var history = revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId());
            assertThat(history).hasSize(restore ? 2 : 1);
            assertThat(history.getFirst().getContent()).isEqualTo(finalText);
            assertThat(history.getFirst().getSource()).isEqualTo(restore ? NovelContentRevisionSource.RESTORE : NovelContentRevisionSource.AI);
            var metadata = jdbc.queryForObject("SELECT metadata::text FROM agent_usage_ledger WHERE request_id=? AND usage_kind='TOOL_CALL' AND metadata->>'tool'=?", String.class, f.requestId(), restore ? "draft_novel_restore" : "draft_novel_content");
            assertThat(metadata).contains("domain_effect", "author_review_receipt", "COMMITTED", draft.artifactId().toString());
            assertThat(rounds).hasValue(4);
            verifyNoInteractions(plans, storyboard);
        } finally { if (summaryAgent != null) summaryAgent.close(); first.close(); registrations.close(); }
    }

    private ToolUseBlock nativeCall(String id, String name, Map<String,Object> input) {
        try { return new ToolUseBlock(id, name, input, mapper.writeValueAsString(input), Map.of()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private String nativeOutput(List<Msg> messages, String id) {
        return messages.stream().flatMap(value -> value.getContentBlocks(ToolResultBlock.class).stream())
                .filter(value -> id.equals(value.getId())).flatMap(value -> value.getOutput().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
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
    void confirmedReviewCommitsExactTextBeforeHarnessResumesAndRetryOnlyContinuesSummary() throws Exception {
        Fixture fixture = fixture();
        String finalText = "雨终于停了。\n少女仍守在门前，握紧那封旧信。";
        var request = Map.of("decision", "confirm", "content", finalText, "base_version", fixture.version(),
                "expected_content_hash", fixture.hash(), "continue_agent", true);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(url(fixture)).contentType("application/json").content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"));
        }
        var conversation = new TransactionTemplate(transactions).execute(tx -> {
            assertThat(em.find(Chapter.class, fixture.chapterId()).getNovelContent()).isEqualTo(finalText);
            assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(fixture.chapterId())).hasSize(1);
            var run = em.find(MangaAgentRun.class, fixture.runId());
            assertThat(run.getStatus()).isEqualTo(MangaAgentRunStatus.WAITING_USER);
            assertThat(run.getRunAttributesJson()).contains("review_result", ToolIdempotencyService.sha256(finalText));
            return run.getConversation();
        });
        Map<?, ?> receipt = (Map<?, ?>) mapper.readValue(jdbc.queryForObject(
                "select run_attributes_json::text from manga_agent_runs where id = ?", String.class, fixture.runId()), Map.class).get("review_result");
        assertThat(receipt.get("word_count")).isEqualTo(finalText.length());
        assertThat(String.valueOf(receipt.get("artifact_id"))).isEqualTo(fixture.artifactUuid().toString());
        assertThat(jdbc.queryForObject("select count(*) from agent_outbox_events where aggregate_id = ? and event_type = 'CHAPTER_CONTENT_CHANGED'",
                Long.class, fixture.chapterId().toString())).isEqualTo(1L);

        var firstClaim = runService.claimReviewedContinuation(conversation, fixture.requestId(), fixture.artifactUuid());
        assertThat(firstClaim.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(firstClaim.getOwnerInstanceId()).isNotBlank();
        assertThat(firstClaim.getLeaseUntil()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                runService.claimReviewedContinuation(conversation, fixture.requestId(), fixture.artifactUuid()))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        runService.markFailed(conversation, fixture.requestId(), "simulated summary provider error");
        var retriedClaim = runService.claimReviewedContinuation(conversation, fixture.requestId(), fixture.artifactUuid());
        assertThat(retriedClaim.getStatus()).isEqualTo(MangaAgentRunStatus.RUNNING);
        assertThat(retriedClaim.getFencingToken()).isGreaterThan(firstClaim.getFencingToken());
        runService.markReviewSummarySucceeded(conversation, fixture.requestId(), fixture.artifactUuid(),
                retriedClaim.getFencingToken(), "已按人工审核保存并完成总结。");
        assertThat(runService.claimReviewedContinuation(conversation, fixture.requestId(), fixture.artifactUuid()).getFinalReply())
                .isEqualTo("已按人工审核保存并完成总结。");
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(fixture.chapterId())).hasSize(1);
        verify(lease, times(2)).claim(any(MangaAgentRun.class));
    }

    @Test
    void reviewedReceiptAndSummaryContentKeepTheOriginalVersionAfterLaterEditorSave() {
        Fixture f = fixture();
        var first = review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(),
                f.artifactUuid(), "confirm", "作者选定的最终稿", f.version(), f.hash());
        contents.save(f.chapterId(), f.userId(), "后来手动编辑的正文", first.chapterVersion(), NovelContentRevisionSource.MANUAL);
        var replay = review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(),
                f.artifactUuid(), "confirm", "作者选定的最终稿", f.version(), f.hash());
        assertThat(replay.chapterVersion()).isEqualTo(first.chapterVersion());
        assertThat(replay.revisionId()).isEqualTo(first.revisionId());
        assertThat(replay.changed()).isFalse();
        assertThat(artifacts.reviewedContent(f.userId(), f.chapterId(), f.requestId(), f.artifactUuid(), first.contentHash()))
                .isEqualTo("作者选定的最终稿");
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manga_agent_run_events WHERE run_id=? AND event_type='review_saved'",
                Long.class, f.runId())).isEqualTo(1);
    }

    @Test
    void noOpReviewHasItsOwnImmutableReceiptWithoutBorrowingALaterRevision() {
        Fixture f = fixture();
        String original = "雨没有停。\n少女仍守在门前。";
        var first = review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(),
                f.artifactUuid(), "confirm", original, f.version(), f.hash());
        assertThat(first.changed()).isFalse();
        assertThat(first.chapterVersion()).isEqualTo(f.version());
        assertThat(first.revisionId()).isNull();
        contents.save(f.chapterId(), f.userId(), "后来手动编辑的正文", f.version(), NovelContentRevisionSource.MANUAL);
        var receipt = review.reconcile(f.userId(), f.chapterId(), f.conversationId(), f.requestId(), f.artifactUuid(), true);
        assertThat(receipt).containsEntry("chapter_version", f.version()).containsEntry("changed", false).doesNotContainKey("revision_id");
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
    }

    @Test
    void completedAndFailedRunsKeepARealReceiptAndAMissingProjectionIsRepairedWithoutAnotherSave() {
        for (var status : new MangaAgentRunStatus[]{MangaAgentRunStatus.SUCCEEDED, MangaAgentRunStatus.FAILED}) {
            Fixture f = fixture();
            jdbc.update("UPDATE manga_agent_runs SET status=? WHERE id=?", status.name(), f.runId());
            review.decide(f.userId(), f.chapterId(), f.conversationId(), f.requestId(), f.artifactUuid(),
                    "confirm", "明确审核后的正文", f.version(), f.hash());
            assertThat(jdbc.queryForObject("SELECT run_attributes_json->'review_result'->>'status' FROM manga_agent_runs WHERE id=?",
                    String.class, f.runId())).isEqualTo("COMMITTED");
            jdbc.update("UPDATE manga_agent_runs SET run_attributes_json='{}'::jsonb WHERE id=?", f.runId());
            var repaired = review.reconcile(f.userId(), f.chapterId(), f.conversationId(), f.requestId(), f.artifactUuid(), true);
            assertThat(repaired).containsEntry("status", "COMMITTED").containsEntry("chapter_version", f.version() + 1);
            assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
        }
    }

    @Test
    void backgroundReviewedRejectsUnreviewedOrForeignArtifactBeforeQueueingAndCanRepairALostProjection() {
        Fixture f = fixture();
        var background = background(f);
        var response = mapper.createObjectNode().put("decision", "reviewed").put("artifactId", f.artifactUuid().toString());
        assertThatThrownBy(() -> tx().execute(tx -> background.service().respond(f.userId(), f.requestId(), "unreviewed", response)))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        assertThat(background.store().run(f.requestId()).state()).isEqualTo("WAITING_USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_run_action WHERE run_id=?", Long.class, f.requestId())).isZero();
        Fixture foreign = fixture();
        var other = mapper.createObjectNode().put("decision", "reviewed").put("artifactId", foreign.artifactUuid().toString());
        assertThatThrownBy(() -> tx().execute(tx -> background.service().respond(f.userId(), f.requestId(), "foreign", other)))
                .isInstanceOf(com.artverse.common.BusinessException.class);
        review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(), f.artifactUuid(),
                "confirm", "后台作者最终稿", f.version(), f.hash());
        jdbc.update("UPDATE manga_agent_runs SET run_attributes_json='{}'::jsonb WHERE id=?", f.runId());
        assertThat(tx().execute(tx -> background.service().respond(f.userId(), f.requestId(), "reviewed", response)).state())
                .isEqualTo("QUEUED");
        assertThat(artifacts.reviewReceipt(f.userId(), f.chapterId(), f.requestId(), f.artifactUuid()))
                .containsEntry("content_hash", ToolIdempotencyService.sha256("后台作者最终稿"));
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
    }

    @Test
    void backgroundRetryAfterSavedTextOnlyQueuesReviewSummaryAndTaskCenterUsesTheDraftContentHash() {
        Fixture f = fixture();
        var background = background(f);
        Map<?, ?> detail = background.service().runDetail(f.userId(), f.requestId());
        var item = (Map<?, ?>) ((java.util.List<?>) detail.get("artifacts")).getFirst();
        assertThat(((Map<?, ?>) item.get("review")).get("contentHash")).isEqualTo(f.hash());
        review.decideForContinuation(f.userId(), f.chapterId(), f.conversationId(), f.requestId(), f.artifactUuid(),
                "confirm", "后台已保存的最终稿", f.version(), f.hash());
        jdbc.update("UPDATE manga_agent_runs SET status='FAILED',run_attributes_json='{}'::jsonb WHERE id=?", f.runId());
        jdbc.update("UPDATE automation_run SET state='FAILED',waiting_reason=NULL WHERE id=?", f.requestId());
        var retried = tx().execute(tx -> background.service().retry(f.userId(), f.requestId(), "retry-summary"));
        assertThat(retried.response().path("decision").asText()).isEqualTo("reviewed");
        assertThat(retried.response().path("artifactId").asText()).isEqualTo(f.artifactUuid().toString());
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
    }

    @Test
    void completedBackgroundSummaryConsumesOnlyTheBoundDomainReceipt() {
        Fixture f=fixture();var background=background(f);
        review.decideForContinuation(f.userId(),f.chapterId(),f.conversationId(),f.requestId(),f.artifactUuid(),
                "confirm","后台精确最终稿",f.version(),f.hash());
        var response=mapper.createObjectNode().put("decision","reviewed").put("artifactId",f.artifactUuid().toString());
        tx().execute(s->background.service().respond(f.userId(),f.requestId(),"reviewed",response));
        jdbc.update("UPDATE automation_run SET state='RUNNING',lease_owner='review-worker',lease_until=clock_timestamp()+interval '90 seconds',fencing_token=fencing_token+1 WHERE id=?",f.requestId());
        var claimed=background.store().run(f.requestId());
        assertThat(tx().<Boolean>execute(s->background.store().consumeResponse(claimed))).isFalse();
        var conversation=tx().execute(s->em.find(MangaAgentRun.class,f.runId()).getConversation());
        var continuation=runService.claimReviewedContinuation(conversation,f.requestId(),f.artifactUuid());
        runService.markReviewSummarySucceeded(conversation,f.requestId(),f.artifactUuid(),continuation.getFencingToken(),"已保存作者最终稿");
        assertThat(tx().<Boolean>execute(s->background.store().consumeResponse(claimed))).isTrue();
        assertThat(background.store().responseConsumed(claimed)).isTrue();
        assertThat(background.store().action(f.requestId(),"reviewed").orElseThrow().evidence().path("kind").asText())
                .isEqualTo("REVIEW_SUMMARY_COMPLETED");
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
    }

    @Test
    void retryRetainsTheOriginalResponseAndKeyReplayDoesNotRebindToTheNewStage() {
        Fixture f=fixture();var background=background(f);
        review.decideForContinuation(f.userId(),f.chapterId(),f.conversationId(),f.requestId(),f.artifactUuid(),
                "confirm","作者最终稿供失败恢复",f.version(),f.hash());
        var response=mapper.createObjectNode().put("decision","reviewed").put("artifactId",f.artifactUuid().toString());
        tx().execute(s->background.service().respond(f.userId(),f.requestId(),"original-response",response));
        String responseHash=background.store().run(f.requestId()).responseHash();
        jdbc.update("UPDATE automation_run SET state='FAILED',attempt_count=2 WHERE id=?",f.requestId());
        jdbc.update("UPDATE manga_agent_runs SET status='FAILED' WHERE id=?",f.runId());
        var retried=tx().execute(s->background.service().retry(f.userId(),f.requestId(),"retry-stage"));
        assertThat(retried.responseKey()).isEqualTo("original-response");
        assertThat(retried.responseHash()).isEqualTo(responseHash);
        var action=background.store().action(f.requestId(),"retry-stage").orElseThrow();
        assertThat(action.target().path("retained_response_key").asText()).isEqualTo("original-response");
        jdbc.update("UPDATE automation_run SET state='SUCCEEDED',attempt_count=3 WHERE id=?",f.requestId());
        assertThat(tx().execute(s->background.service().retry(f.userId(),f.requestId(),"retry-stage")).state()).isEqualTo("SUCCEEDED");
        assertThat(background.store().action(f.requestId(),"retry-stage").orElseThrow().requestHash()).isEqualTo(action.requestHash());
        assertThatThrownBy(()->tx().execute(s->background.service().respond(f.userId(),f.requestId(),"retry-stage",response)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同一幂等键");
        jdbc.update("UPDATE automation_run SET state='FAILED',attempt_count=4,response=NULL,response_key=NULL,response_hash=NULL WHERE id=?",f.requestId());
        tx().execute(s->background.service().retry(f.userId(),f.requestId(),"retry-stage-2"));
        assertThat(background.store().action(f.requestId(),"retry-stage-2").orElseThrow().requestHash()).isNotEqualTo(action.requestHash());
        assertThat(background.store().action(f.requestId(),"retry-stage").orElseThrow().requestHash()).isEqualTo(action.requestHash());
        assertThat(revisions.findByChapterIdOrderByRevisionNumberDesc(f.chapterId())).hasSize(1);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    private Background background(Fixture f) {
        var store = new AutomationStore(jdbc, mapper);
        var service = new AutomationService(store, new AutomationScheduleCalculator(), mock(AutomationInputService.class), review, runService);
        var payload = mapper.createObjectNode().put("chapterId", f.chapterId());
        long storyId = jdbc.queryForObject("SELECT story_id FROM chapters WHERE id=?", Long.class, f.chapterId());
        payload.put("storyId", storyId);
        var definition = new AutomationDefinition("Review fixture", "AGENT", "STORY_CHAT", "Prepare a reviewable draft",
                new AutomationDefinition.Schedule("ONCE", "2026-11-06T09:00:00", null, "Asia/Shanghai", java.util.List.of()),
                payload, new AutomationDefinition.Limits(0, 8, 600), "RUN_LATEST", "SKIP", null, null, "TASK_CENTER", null);
        UUID plan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,idempotency_key,request_hash)
                VALUES (?,?,?,'AGENT','STORY_CHAT',?::jsonb,'Asia/Shanghai',?,?)
                """, plan, f.userId(), "Review fixture", store.json(definition), plan.toString(), "fixture");
        jdbc.update("""
                INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,
                    definition_snapshot,state,waiting_reason)
                VALUES (?,?,?,0,'MANUAL',now(),?,?::jsonb,'WAITING_USER','AGENT_INPUT')
                """, f.requestId(), plan, f.userId(), f.requestId().toString(), store.json(definition));
        jdbc.update("""
                INSERT INTO automation_run_execution(run_id,target_key,execution_kind,agent_run_id,conversation_uuid,request_id)
                VALUES (?,'agent','STORY_CHAT',?,?,?)
                """, f.requestId(), f.runId(), f.conversationId(), f.requestId());
        return new Background(store, service);
    }

    private record Background(AutomationStore store, AutomationService service) { }

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
