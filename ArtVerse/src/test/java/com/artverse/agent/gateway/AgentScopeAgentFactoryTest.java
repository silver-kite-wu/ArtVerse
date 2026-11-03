package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.agent.mcp.McpAgentExecutionTokenService;
import com.artverse.application.ArtVerseSkillRegistry;
import com.artverse.application.MangaAgentRunService;
import com.artverse.config.ArtVerseProperties;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import reactor.core.publisher.Mono;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentScopeAgentFactoryTest {
    @TempDir Path workspace;

    @Test void freshAgentStateIncludesBusinessGroupsRegisteredBeforeBuild() {
        var registry=mock(AgentToolConfigurationRegistry.class);
        doAnswer(call->{
            Toolkit toolkit=call.getArgument(0);
            toolkit.createToolGroup("fixture-write","fixture business tools",true);
            toolkit.registration().tool(new FixtureTool()).group("fixture-write").apply();
            toolkit.setActiveGroups(List.of("fixture-write"));
            return null;
        }).when(registry).configure(any(),eq(AgentTaskType.STORY_CHAT_WRITE),any());
        var factory=new AgentScopeAgentFactory(CompactionConfig.builder().build(),new ArtVerseProperties(),
                mock(AgentWorkspaceService.class),mock(MangaAgentPromptProvider.class),null,registry,
                mock(ArtVerseSkillRegistry.class),mock(ArtVerseSkillRepository.class),mock(MangaAgentRunService.class),
                mock(PostgresAgentWorkspaceStore.class),mock(McpAgentExecutionTokenService.class));
        var request=new AgentRunRequest("1",2L,3L,AgentTaskType.STORY_CHAT_WRITE,List.of(),Map.of(),
                new AgentModelSpec("openai","http://127.0.0.1:1","fixture","fixture"),"fixture-not-a-real-key");
        HarnessAgent agent=ReflectionTestUtils.invokeMethod(factory,"buildAgent",request,workspace,
                BusinessSkillSelection.empty(),mock(McpClientWrapper.class));
        try {
            Object delegate=ReflectionTestUtils.getField(agent,"delegate");
            assertThat(ReflectionTestUtils.getField(delegate,"initialActiveToolGroups")).isEqualTo(List.of("fixture-write"));
            PermissionContextState permissions=ReflectionTestUtils.invokeMethod(delegate,"getPermissionContext");
            assertThat(permissions.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(permissions.getAllowRules()).containsOnlyKeys("fixture_business_tool");
        } finally { agent.close(); }
    }
    static final class FixtureTool extends ToolBase {
        FixtureTool(){super(ToolBase.builder().name("fixture_business_tool").description("fixture").inputSchema(Map.of("type","object")));}
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param){return Mono.error(new AssertionError("This build test must never execute tools"));}
    }
}
