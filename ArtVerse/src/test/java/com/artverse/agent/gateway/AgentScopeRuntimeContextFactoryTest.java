package com.artverse.agent.gateway;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.NovelDraftBaseContext;
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

    @Test
    void runtimeCarriesTheOriginalOwnerAndFenceWithoutDerivingANewClaim() {
        var request = new AgentRunRequest("9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), Map.of("execution_owner", "original-worker", "fencing_token", 41L), null, null, null, null);
        var runtime = factory.create(request).get(MangaAgentRuntimeContext.class);
        assertThat(runtime.executionOwner()).isEqualTo("original-worker");
        assertThat(runtime.fencingToken()).isEqualTo(41L);
        assertThat(factory.create(new AgentRunRequest("9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), Map.of(), null, null, null, null)).get(MangaAgentRuntimeContext.class).executionOwner()).isNull();
    }

    @Test
    void backgroundClaimIsTypedAndPartialClaimsAreRejected() {
        var parent = new com.artverse.agent.AutomationExecutionClaim(java.util.UUID.randomUUID(), "parent-original", 17L);
        var request = new AgentRunRequest("9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), parent.variables(), null, null, null, null);
        assertThat(factory.create(request).get(MangaAgentRuntimeContext.class).automationClaim()).isEqualTo(parent);
        var partial = new AgentRunRequest("9", 1L, 2L, AgentTaskType.MANGA_STORYBOARD,
                List.of(), Map.of("automation_run_id", parent.runId().toString()), null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.create(partial)).hasMessageContaining("凭证不完整");
    }

    @Test
    void novelWriteCarriesServerOwnedChapterVersionForStaleDraftRejection() {
        AgentRunRequest request = new AgentRunRequest("9", 1L, 2L, AgentTaskType.STORY_CHAT_WRITE,
                List.of(), Map.of("chapter_version", 6L), null, null, null, null);
        var context = factory.create(request);
        assertThat(context.get(NovelDraftBaseContext.class).chapterVersion()).isEqualTo(6L);
    }
}
