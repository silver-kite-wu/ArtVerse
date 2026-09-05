package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.UserProviderConfig;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeRuntimeContextFactoryTest {

    private final AgentScopeRuntimeContextFactory factory = new AgentScopeRuntimeContextFactory();

    @Test
    void mangaExecutionContextCarriesUserWorkflowConfig() {
        UserProviderConfig config = new UserProviderConfig(
                "workflow", "coze", "My Coze", "sk-user", "https://user-gateway.example", "workflow");
        AgentRunRequest request = new AgentRunRequest(
                "9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), Map.of("workflow_config", config), null, null, null, null);

        RuntimeContext runtimeContext = factory.create(request);

        MangaAgentRuntimeContext manga = runtimeContext.get(MangaAgentRuntimeContext.class);
        assertThat(manga).isNotNull();
        assertThat(manga.workflowConfig().apiKey()).isEqualTo("sk-user");
        assertThat(manga.workflowConfig().baseUrl()).isEqualTo("https://user-gateway.example");
    }

    @Test
    void missingWorkflowConfigYieldsBlankInsteadOfOperatorFallback() {
        AgentRunRequest request = new AgentRunRequest(
                "9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), Map.of(), null, null, null, null);

        RuntimeContext runtimeContext = factory.create(request);

        MangaAgentRuntimeContext manga = runtimeContext.get(MangaAgentRuntimeContext.class);
        assertThat(manga).isNotNull();
        assertThat(manga.workflowConfig().apiKey()).isBlank();
        assertThat(manga.workflowConfig().baseUrl()).isBlank();
    }

    @Test
    void nonMangaTaskDoesNotCarryMangaRuntimeContext() {
        AgentRunRequest request = new AgentRunRequest(
                "9", 1L, 2L, AgentTaskType.MANGA_ROUTER,
                List.of(), Map.of(), null, null, null, null);

        RuntimeContext runtimeContext = factory.create(request);

        assertThat(runtimeContext.get(MangaAgentRuntimeContext.class)).isNull();
    }
}
