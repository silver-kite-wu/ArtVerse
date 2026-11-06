package com.artverse.agent.gateway.tools;

import com.artverse.agent.*;
import com.artverse.agent.gateway.*;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.application.tools.*;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Harness, registry, wrappers, validator and pending recovery; no provider calls. */
class NativeToolRoundTripTest {
    @TempDir Path workspace;

    @Test void modelReadsDraftsPausesAndConsumesTheOriginalExternalResult() {
        var statuses = mock(AgentRunToolStatus.class);
        var scope = new MangaToolSupport(statuses);
        var audit = new AgentToolAuditService(statuses);
        var chapters = mock(ChapterAccessService.class);
        var drafts = mock(NovelContentArtifactService.class);
        var novelReads = mock(NovelModelReadService.class);
        var history = mock(NovelHistoryAgentService.class);
        var readService = mock(AgentStoryKnowledgeReadService.class);
        var artifactAccess = mock(AgentArtifactAccessService.class);
        var novelContext = new StoryChatContextTools(scope, audit, chapters);
        novelContext.setNovelReads(novelReads);
        var novelDraft = new StoryChatNovelContentTools(scope, audit, drafts);
        novelDraft.setHistory(history);
        var nativeDrafts = mock(NativeNovelDraftService.class);
        novelDraft.setNativeDrafts(nativeDrafts);
        var storyboard = mock(MangaStoryboardTools.class);
        var plans = mock(AutomationAgentTools.class);
        var mapper = new ObjectMapper();
        var runs = mock(MangaAgentRunService.class);
        ToolCapabilityFixtures.mockScopes(runs);
        when(runs.recordExternalQuestion(any(), anyString(), any())).thenAnswer(invocation -> {
            AgentUserInputRequest question = invocation.getArgument(2);
            return new MangaAgentRunService.ExternalQuestion(new AgentUserInputRequest(question.question(),
                    question.options(), question.allowFreeText(), question.reason(), question.purpose(),
                    invocation.getArgument(1), 1L), true);
        });
        var hitl = new MangaHitlTools(audit, scope, runs);
        var groups = new AgentToolGroupSupport(hitl,
                new MangaContextTools(mock(MangaChapterContextReader.class)),
                novelContext,
                new StoryboardAgentTools(storyboard, mapper), new StoryboardCommitAgentTools(storyboard),
                novelDraft,
                new AutomationReadAgentTools(plans, scope), new AutomationManageAgentTools(plans, scope, mapper, audit),
                new StoryContextReadTools(readService, scope), new KnowledgeReadTools(readService, scope),
                new ReferenceAssetReadTools(readService, scope), new KnowledgeCandidateReadTools(readService, scope),
                new ArtifactReadAgentTools(scope, audit, artifactAccess), new ArtifactDraftAgentTools(scope, audit, artifactAccess),
                new NovelHistoryAgentTools(scope, audit, history),
                new SettingsDraftAgentTools(scope,audit,mock(SettingsProposalService.class)),
                new KnowledgeDraftAgentTools(scope,audit,mock(KnowledgeProposalService.class)),
                new ImageProposalAgentTools(scope,audit,mock(com.artverse.application.imageproposal.ImageProposalService.class)));
        var registry = new AgentToolConfigurationRegistry(List.of(
                new MangaContextToolsStrategy(groups), new MangaConversationToolsStrategy(groups),
                new MangaDirectorToolsStrategy(groups), new MangaStoryboardToolsStrategy(groups),
                new StoryChatReadToolsStrategy(groups), new StoryChatWriteToolsStrategy(groups), new NoAgentToolsStrategy()));
        var budget = mock(AgentBudgetService.class);
        when(budget.reserveModelCall(any(), any(), anyString(), any())).thenReturn(true);
        when(budget.modelTimeAllowance(any(), any())).thenReturn(java.time.Duration.ofMinutes(3));
        when(budget.reserveToolCalls(any(), anyList())).thenReturn(true);
        var state = new StrictAgentStateStore(new InMemoryAgentStateStore());
        var boundary = new AgentToolBoundaryMiddleware(runs, plans, budget);
        boundary.setNovelReads(novelReads);
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(),
                state, mock(AgentTurnStateStores.class), registry, mock(ArtVerseSkillRegistry.class),
                mock(ArtVerseSkillRepository.class), runs, mock(PostgresAgentWorkspaceStore.class),
                boundary);
        UUID requestId = UUID.randomUUID(), conversation = UUID.randomUUID(), artifactId = UUID.randomUUID();
        var request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.STORY_CHAT_WRITE,
                List.of(new AgentMessage("user", "Polish the current chapter")),
                Map.of("chapter_version", 7L, "fencing_token", 1L, "execution_owner", "fixture-worker"), null, "", requestId, conversation);
        var story = new Story(); story.setId(2L);
        var chapter = new Chapter(); chapter.setId(3L); chapter.setStory(story);
        chapter.setChapterNumber(1); chapter.setVersion(7L); chapter.setNovelContent("Original prose.");
        when(novelReads.readCurrent(any(), nullable(Long.class), nullable(String.class), nullable(Integer.class))).thenReturn(Map.of("story_id", 2L, "chapter_id", 3L,
                "chapter_version", 7L, "novel_content", "Original prose."));
        when(nativeDrafts.draft(eq("Polished prose."), any(), any())).thenReturn(
                new NovelContentArtifactService.ArtifactView(artifactId, requestId, "NOVEL_CONTENT_DRAFT",
                        "VALIDATED", "1", Map.of("content", "Polished prose.", "base_version", 7L,
                        "base_content", "Original prose."), Map.of(), "draft-hash"));
        AtomicInteger round = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "scripted-tool-roundtrip"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    int n = round.getAndIncrement();
                    if (n < 3) assertThat(tools).extracting(ToolSchema::getName).containsExactlyInAnyOrder(
                            "get_novel_context", "get_story_context", "list_chapters", "draft_novel_content", "ask_user",
                            "get_artifact", "list_artifacts", "discard_artifact");
                    else assertThat(tools).extracting(ToolSchema::getName).contains("get_novel_context")
                            .doesNotContain("draft_novel_content", "draft_novel_restore", "ask_user", "discard_artifact");
                    ContentBlock output = switch (n) {
                        case 0 -> toolCall("read-call", "get_novel_context", Map.of());
                        case 1 -> {
                            assertThat(resultText(messages, "read-call")).contains("Original prose.", "chapter_version");
                            yield toolCall("draft-call", "draft_novel_content", Map.of("content", "Polished prose."));
                        }
                        case 2 -> {
                            assertThat(resultText(messages, "draft-call")).contains(artifactId.toString()).doesNotContain("base_content");
                            yield toolCall("review-call", "ask_user", Map.of(
                                    "question", "Review the draft?", "options", List.of("Save", "Discard"),
                                    "reason", "artifact does not itself grant authorization"));
                        }
                        case 3 -> {
                            assertThat(resultText(messages, "review-call")).contains("author-edited-receipt");
                            yield TextBlock.builder().text("Reviewed result received.").build();
                        }
                        default -> throw new AssertionError("Unexpected extra model call: " + n);
                    };
                    return Flux.just(ChatResponse.builder().content(List.of(output)).finishReason(
                            output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
                });
            }
        };
        RuntimeContext ctx = new AgentScopeRuntimeContextFactory().create(request);
        HarnessAgent agent = ReflectionTestUtils.invokeMethod(factory, "buildAgent", request, workspace,
                BusinessSkillSelection.empty(), model);
        var gatewayFactory = spy(factory);
        doReturn(agent).when(gatewayFactory).getOrCreate(eq(request), any(AgentSessionTurns.Lease.class));
        when(budget.measureOutput(anyString())).thenReturn(new AgentBudgetService.OutputUsage(1, 1));
        var turns = mock(AgentSessionTurns.class);
        when(turns.acquire(any(AgentRunRequest.class))).thenAnswer(ignored -> mock(AgentSessionTurns.Lease.class));
        var gateway = new AgentScopeHarnessAgentGateway(gatewayFactory, new AgentScopeRuntimeContextFactory(),
                new ArtVerseProperties(), io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                budget, new AgentSessionHydrator(state), turns);
        ReflectionTestUtils.setField(gateway, "hitlTools", hitl);
        gateway.init();
        HarnessAgent resumed = null;
        try {
            var events = gateway.streamEvents(request).collectList().block(Duration.ofSeconds(20));
            var external = events.stream().filter(RequireExternalExecutionEvent.class::isInstance)
                    .map(RequireExternalExecutionEvent.class::cast).findFirst().orElseThrow();
            assertThat(external.getToolCalls()).singleElement().satisfies(call -> {
                assertThat(call.getId()).isEqualTo("review-call");
                assertThat(call.getName()).isEqualTo("ask_user");
            });
            verify(nativeDrafts, times(1)).draft(eq("Polished prose."), any(), any());
            verifyNoInteractions(drafts);
            // Native external short-circuit must not invoke callAsync or create a phantom decision.
            verify(statuses).requestUserInput(eq(1L), eq(3L), eq(requestId), argThat(value -> "USER_QUESTION".equals(value.purpose())));
            var result = ToolResultBlock.builder().id("review-call").name("ask_user")
                    .output(List.of(TextBlock.builder().text("author-edited-receipt").build()))
                    .state(ToolResultState.SUCCESS).build();
            agent.close();
            var summaryVariables = new LinkedHashMap<>(request.variables()); summaryVariables.put("review_completed", true);
            var summary = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                    request.messages(), Map.copyOf(summaryVariables), request.modelSpec(), request.llmApiKey(), request.requestId(),
                    request.conversationId(), BusinessSkillSelection.empty());
            RuntimeContext summaryContext = new AgentScopeRuntimeContextFactory().create(summary);
            assertThat(AgentScopeRuntimeContextFactory.createSessionId(summary))
                    .isEqualTo(AgentScopeRuntimeContextFactory.createSessionId(request));
            resumed = ReflectionTestUtils.invokeMethod(factory, "buildAgent", summary, workspace,
                    BusinessSkillSelection.empty(), model);
            doReturn(resumed).when(gatewayFactory).getOrCreate(eq(summary), any(AgentSessionTurns.Lease.class));
            var finalEvents = gateway.streamEvents(summary, List.of(result)).collectList().block(Duration.ofSeconds(20));
            assertThat(finalEvents.stream().filter(io.agentscope.core.event.AgentResultEvent.class::isInstance)
                    .map(io.agentscope.core.event.AgentResultEvent.class::cast).map(event -> event.getResult().getTextContent()).toList())
                    .contains("Reviewed result received.");
            assertThat(round.get()).isEqualTo(4);
            verify(budget, times(3)).reserveModelCall(eq(request), any(), anyString(), any());
            verify(budget, times(1)).reserveModelCall(eq(summary), any(), anyString(), any());
            verify(budget, times(3)).reserveToolCalls(any(), anyList());
            verifyNoInteractions(storyboard, plans);
        } finally { if (resumed != null) resumed.close(); agent.close(); }
    }

    private static String resultText(List<Msg> messages, String id) {
        return messages.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> id.equals(result.getId()))
                .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", (a,b) -> a + b);
    }

    private static ToolUseBlock toolCall(String id, String name, Map<String,Object> input) {
        try {
            // Real providers supply the raw streaming argument JSON as well as its parsed input.
            return new ToolUseBlock(id, name, input, new ObjectMapper().writeValueAsString(input), Map.of());
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
