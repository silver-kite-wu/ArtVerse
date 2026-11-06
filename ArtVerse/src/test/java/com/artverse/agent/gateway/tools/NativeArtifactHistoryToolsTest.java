package com.artverse.agent.gateway.tools;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual native reflection, guarded invocation and ToolResult serialization, without provider calls. */
class NativeArtifactHistoryToolsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    final MangaAgentRuntimeContext execution = new MangaAgentRuntimeContext(1L, 2L, 3L,
            UUID.randomUUID(), UUID.randomUUID(), null, AgentTaskType.STORY_CHAT_WRITE, "story-chat:POLISH", 1L, null, "owner");
    final RuntimeContext runtime = RuntimeContext.builder().put(MangaAgentRuntimeContext.class, execution).build();
    final AgentArtifactAccessService artifacts = mock(AgentArtifactAccessService.class);
    final NovelHistoryAgentService history = mock(NovelHistoryAgentService.class);
    final AgentRunToolStatus statuses = mock(AgentRunToolStatus.class);
    final MangaToolSupport support = new MangaToolSupport(statuses);
    final AgentToolAuditService audit = new AgentToolAuditService(statuses);
    final ObjectMapper mapper = new ObjectMapper();

    @Test void nativeReadListDiscardAndRestoreSupplyOnlyPublicArgumentsAndReturnActualDomainReceipt() {
        Toolkit toolkit = toolkit(); UUID artifact = UUID.randomUUID();
        when(artifacts.list(execution, null, null, null, 10)).thenReturn(Map.of("items", List.of(), "has_more", false));
        when(artifacts.get(execution, artifact, null, 400)).thenReturn(Map.of("artifact_id", artifact.toString(), "artifact_version", "v1", "content", "draft"));
        when(artifacts.discard(eq(execution), eq(artifact), eq("v1"), any(AgentToolAttempt.class))).thenReturn(Map.of("artifact_id", artifact.toString(), "status", "DISCARDED"));
        when(history.list(execution, null, 10)).thenReturn(Map.of("items", List.of(Map.of("revision_id", 7L)), "has_more", false));
        when(history.get(execution, 7L, null, 400)).thenReturn(Map.of("revision_id", 7L, "content", "historical text"));
        when(history.restore(eq(7L), eq(execution), any(AgentToolAttempt.class))).thenReturn(new NovelContentArtifactService.ArtifactView(artifact,
                execution.requestId(), "NOVEL_CONTENT_DRAFT", "VALIDATED", "1", Map.of("base_version", 3L,
                "content_hash", "draft-hash", "content", "server fetched complete historical text", "base_content", "current text"), Map.of(), "checksum"));
        assertThat(text(call(toolkit, "list_artifacts", Map.of("limit", 10)))).contains("has_more");
        assertThat(text(call(toolkit, "get_artifact", Map.of("artifact_id", artifact.toString(), "limit", 400)))).contains("v1", "draft");
        assertThat(text(call(toolkit, "discard_artifact", Map.of("artifact_id", artifact.toString(), "expected_artifact_version", "v1")))).contains("DISCARDED");
        assertThat(text(call(toolkit, "list_novel_revisions", Map.of("limit", 10)))).contains("revision_id");
        assertThat(text(call(toolkit, "get_novel_revision", Map.of("revision_id", 7, "limit", 400)))).contains("historical text");
        assertThat(text(call(toolkit, "draft_novel_restore", Map.of("revision_id", 7)))).contains("VALIDATED", "draft-hash")
                .doesNotContain("server fetched complete historical text", "base_content", "current text");
        verify(history).restore(eq(7L), eq(execution), any(AgentToolAttempt.class));
        verify(artifacts).discard(eq(execution), eq(artifact), eq("v1"), any(AgentToolAttempt.class));
        harnessConsumesAllSix(toolkit, artifact);
    }

    @Test void nativeValidatorRejectsEchoedHistoricalContentExecutionIdentityAndMissingCasBeforeDomainService() {
        Toolkit toolkit = toolkit();
        for (Map<String,Object> input : List.of(Map.<String,Object>of("revision_id", 7, "content", "model edited history"),
                Map.<String,Object>of("revision_id", 7, "user_id", 99), Map.<String,Object>of("revision_id", "not-a-number"))) {
            var result = call(toolkit, "draft_novel_restore", input);
            assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        }
        var discard = call(toolkit, "discard_artifact", Map.of("artifact_id", UUID.randomUUID().toString()));
        assertThat(discard.getState()).isEqualTo(ToolResultState.ERROR);
        verifyNoInteractions(history, artifacts);
        var schema = toolkit.getTool("draft_novel_restore").getParameters();
        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat(((Map<?,?>) schema.get("properties")).keySet().stream().map(Object::toString).toList()).containsExactly("revision_id");
    }

    private Toolkit toolkit() {
        var result = new Toolkit();
        AgentToolGroupSupport.group(result, "artifact-read", new ArtifactReadAgentTools(support, audit, artifacts));
        AgentToolGroupSupport.group(result, "artifact-draft", new ArtifactDraftAgentTools(support, audit, artifacts));
        AgentToolGroupSupport.group(result, "history", new NovelHistoryAgentTools(support, audit, history));
        var novel = new StoryChatNovelContentTools(support, audit, mock(NovelContentArtifactService.class)); novel.setHistory(history);
        AgentToolGroupSupport.group(result, "novel", novel);
        return result;
    }

    /** Native Harness pairing/serialization; actual transactions are covered by the PostgreSQL suite. */
    private void harnessConsumesAllSix(Toolkit toolkit, UUID artifact) {
        var names = List.of("list_artifacts", "get_artifact", "list_novel_revisions", "get_novel_revision", "draft_novel_restore", "discard_artifact");
        var inputs = List.of(Map.<String,Object>of("limit", 10), Map.<String,Object>of("artifact_id", artifact.toString(), "limit", 400),
                Map.<String,Object>of("limit", 10), Map.<String,Object>of("revision_id", 7, "limit", 400),
                Map.<String,Object>of("revision_id", 7), Map.<String,Object>of("artifact_id", artifact.toString(), "expected_artifact_version", "v1"));
        var rounds = new java.util.concurrent.atomic.AtomicInteger();
        io.agentscope.core.model.Model model = new io.agentscope.core.model.Model() {
            public String getModelName() { return "artifact-history-native-fixture"; }
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(List<Msg> messages,
                    List<io.agentscope.core.model.ToolSchema> schemas, io.agentscope.core.model.GenerateOptions options) {
                int round = rounds.getAndIncrement();
                if (round > 0) {
                    var result = messages.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(value -> ("artifact-history-" + (round - 1)).equals(value.getId())).findFirst().orElseThrow();
                    assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
                    assertThat(text(result)).isNotBlank().doesNotContain("server fetched complete historical text", "base_content");
                }
                ContentBlock output = round == names.size() ? TextBlock.builder().text("Scoped artifact and history results received.").build()
                        : nativeBlock("artifact-history-" + round, names.get(round), inputs.get(round));
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(output))
                        .finishReason(output instanceof ToolUseBlock ? "tool_calls" : "stop").build());
            }
        };
        var permissions = io.agentscope.core.permission.PermissionContextState.builder().mode(io.agentscope.core.permission.PermissionMode.DONT_ASK);
        names.forEach(name -> permissions.addAllowRule(name, new io.agentscope.core.permission.PermissionRule(name, null,
                io.agentscope.core.permission.PermissionBehavior.ALLOW, "fixture")));
        var agent = io.agentscope.harness.agent.HarnessAgent.builder().name("artifact-history").model(model).workspace(workspace).toolkit(toolkit)
                .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).permissionContext(permissions.build()).maxIters(9)
                .disableCompaction().disableTranscript().disableShellTool().disableFilesystemTools().disableWebTools()
                .disableMemoryTools().disableMemoryHooks().disableSubagents().disableDynamicSubagents().disableDynamicSkills()
                .disableDefaultWorkspaceSkills().disableWorkspaceContext().disableAtPathExpansion().disableToolsConfig().build();
        try {
            var result = agent.call(List.of(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("Read history and manage drafts").build()).build()),
                    RuntimeContext.builder(runtime).userId("1").sessionId(UUID.randomUUID().toString()).build()).block(java.time.Duration.ofSeconds(30));
            assertThat(result.getTextContent()).contains("results received"); assertThat(rounds).hasValue(7);
        } finally { agent.close(); }
    }
    private ToolResultBlock call(Toolkit toolkit, String name, Map<String,Object> input) {
        try {
            var call = new ToolUseBlock(UUID.randomUUID().toString(), name, input, mapper.writeValueAsString(input), Map.of());
            return toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).runtimeContext(runtime).build()).block();
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private ToolUseBlock nativeBlock(String id, String name, Map<String,Object> input) {
        try { return new ToolUseBlock(id, name, input, mapper.writeValueAsString(input), Map.of()); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private String text(ToolResultBlock result) { return result.getOutput().stream().filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat); }
}
