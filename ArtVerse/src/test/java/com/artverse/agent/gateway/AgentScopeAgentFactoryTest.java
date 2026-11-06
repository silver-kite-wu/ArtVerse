package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import reactor.core.publisher.Mono;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentScopeAgentFactoryTest {
    @TempDir Path workspace;

    static java.util.stream.Stream<AgentModelCapabilities> modelCapabilities() {
        return java.util.stream.Stream.of(AgentModelCapabilities.UNKNOWN,
                new AgentModelCapabilities(true, false, false, "synthetic-v1"),
                new AgentModelCapabilities(false, true, false, "native-only-v1"),
                new AgentModelCapabilities(true, true, true, "native-with-tools-v1"));
    }

    @ParameterizedTest @MethodSource("modelCapabilities")
    void productionModelBuilderUsesTheActualCapabilityDeclaration(AgentModelCapabilities capabilities) {
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), new MangaAgentPromptProvider(), null,
                mock(AgentTurnStateStores.class), mock(AgentToolConfigurationRegistry.class),
                mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), mock(MangaAgentRunService.class),
                mock(PostgresAgentWorkspaceStore.class), mock(AgentToolBoundaryMiddleware.class));
        var spec = new AgentModelSpec("fixture", "https://provider.example.test/v1", "fixture-model", "hash", 1L, capabilities);
        io.agentscope.core.model.Model model = ReflectionTestUtils.invokeMethod(factory, "buildChatModel", "fixture-not-a-key", spec);
        assertThat(model.getModelName()).isEqualTo("fixture-model");
        assertThat(model.supportsNativeStructuredOutput()).isEqualTo(capabilities.nativeStructuredOutput());
        assertThat(model.supportsNativeStructuredOutputWithTools()).isEqualTo(capabilities.nativeStructuredOutputWithTools());
    }

    @Test void freshAgentContainsOnlyCuratedNativeToolsAndDenyByDefaultPermissions() {
        var registry = mock(AgentToolConfigurationRegistry.class);
        doAnswer(call -> {
            Toolkit toolkit = call.getArgument(0);
            toolkit.createToolGroup("fixture-write", "fixture business tools", true);
            toolkit.registration().tool(new FixtureTool()).group("fixture-write").apply();
            toolkit.setActiveGroups(List.of("fixture-write"));
            return null;
        }).when(registry).configure(any(), any());
        var boundary = new AgentToolBoundaryMiddleware(mock(MangaAgentRunService.class),
                mock(AutomationAgentTools.class), mock(AgentBudgetService.class));
        var factory = new AgentScopeAgentFactory(CompactionConfig.builder().build(), new ArtVerseProperties(),
                mock(AgentWorkspaceService.class), mock(MangaAgentPromptProvider.class), null,
                mock(AgentTurnStateStores.class), registry,
                mock(ArtVerseSkillRegistry.class), mock(ArtVerseSkillRepository.class), mock(MangaAgentRunService.class),
                mock(PostgresAgentWorkspaceStore.class), boundary);
        var request = new AgentRunRequest("1",2L,3L,AgentTaskType.STORY_CHAT_WRITE,List.of(),Map.of(),
                new AgentModelSpec("openai","http://127.0.0.1:1","fixture","fixture", 1L,
                        new AgentModelCapabilities(true, false, false, "fixture-v1")),"fixture-not-a-real-key");
        HarnessAgent agent = ReflectionTestUtils.invokeMethod(factory,"buildAgent",request,workspace, BusinessSkillSelection.empty());
        try {
            Object delegate = ReflectionTestUtils.getField(agent,"delegate");
            assertThat(ReflectionTestUtils.getField(delegate,"initialActiveToolGroups")).isEqualTo(List.of("fixture-write"));
            PermissionContextState permissions = ReflectionTestUtils.invokeMethod(delegate,"getPermissionContext");
            assertThat(permissions.getMode()).isEqualTo(PermissionMode.DONT_ASK);
            assertThat(permissions.getAllowRules()).containsOnlyKeys("fixture_business_tool");
            Toolkit toolkit = ReflectionTestUtils.invokeMethod(delegate, "getToolkit");
            assertThat(toolkit.getToolNames()).containsExactly("fixture_business_tool");
        } finally { agent.close(); }
        var undeclared = new AgentRunRequest(request.userId(), request.storyId(), request.chapterId(), request.taskType(),
                request.messages(), request.variables(), new AgentModelSpec("fixture", "https://provider.example.test/v1", "unknown", "hash"),
                "fixture-not-a-real-key");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(factory, "buildAgent", undeclared, workspace, BusinessSkillSelection.empty()))
                .isInstanceOf(com.artverse.common.BusinessException.class).hasMessageContaining("model-capabilities");
    }

    public static final class FixtureTool extends ToolBase {
        FixtureTool() { super(ToolBase.builder().name("fixture_business_tool").description("fixture").inputSchema(Map.of("type","object"))); }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) { return Mono.error(new AssertionError("Build test must never execute tools")); }
    }
}
