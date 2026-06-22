package com.artverse.agents;

import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeHarnessAgentGatewayTest {

    @Test
    void mergesSystemMessagesIntoFirstUserMessage() {
        AgentRunRequest request = new AgentRunRequest(
                "default",
                1L,
                2L,
                AgentTaskType.STORYBOARD,
                List.of(
                        new AgentMessage("system", "系统提示"),
                        new AgentMessage("user", "用户内容")
                ),
                Map.of()
        );

        List<AgentMessage> messages = AgentScopeHarnessAgentGateway.prepareInputMessages(request);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(0).content()).contains("系统提示", "用户内容");
    }

    @Test
    void cacheKeyChangesWhenModelChanges() {
        AgentRunRequest first = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        ));
        AgentRunRequest second = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-reasoner",
                "key-a"
        ));

        assertThat(cacheKey(first)).isNotEqualTo(cacheKey(second));
    }

    @Test
    void cacheKeyChangesWhenBaseUrlChanges() {
        AgentRunRequest first = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        ));
        AgentRunRequest second = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://gateway.example.com",
                "deepseek-chat",
                "key-a"
        ));

        assertThat(cacheKey(first)).isNotEqualTo(cacheKey(second));
    }

    @Test
    void cacheKeyChangesWhenApiKeyHashChanges() {
        AgentRunRequest first = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        ));
        AgentRunRequest second = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-b"
        ));

        assertThat(cacheKey(first)).isNotEqualTo(cacheKey(second));
    }

    @Test
    void cacheKeyChangesWhenUserChanges() {
        AgentModelSpec spec = new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        );

        assertThat(cacheKey(requestWithSpec("user-1", spec)))
                .isNotEqualTo(cacheKey(requestWithSpec("user-2", spec)));
    }

    @Test
    void runtimeSessionIdIsStableWhenModelChanges() {
        AgentRunRequest first = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        ));
        AgentRunRequest second = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-reasoner",
                "key-b"
        ));
        AgentSessionIdFactory factory = new AgentSessionIdFactory();

        assertThat(AgentScopeHarnessAgentGateway.buildRuntimeContextForTest(first, factory).getSessionId())
                .isEqualTo(AgentScopeHarnessAgentGateway.buildRuntimeContextForTest(second, factory).getSessionId());
    }

    @Test
    void runtimeContextCarriesBusinessRequestIdWhenPresent() {
        UUID requestId = UUID.randomUUID();
        AgentRunRequest request = new AgentRunRequest(
                "user-1",
                1L,
                2L,
                AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "hi")),
                Map.of(),
                new AgentModelSpec("deepseek", "https://api.deepseek.com", "deepseek-chat", "key-a"),
                "secret",
                requestId,
                null
        );

        AgentRunContext context = AgentScopeHarnessAgentGateway
                .buildRuntimeContextForTest(request, new AgentSessionIdFactory())
                .get(AgentRunContext.class);

        assertThat(context).isNotNull();
        assertThat(context.requestId()).isEqualTo(requestId);
    }

    @Test
    void cacheKeyChangesWhenWorkspaceChanges() {
        AgentRunRequest request = requestWithSpec("user-1", new AgentModelSpec(
                "deepseek",
                "https://api.deepseek.com",
                "deepseek-chat",
                "key-a"
        ));

        assertThat(cacheKey(request, Path.of(".agentscope/workspace/users/user-1/stories/1")))
                .isNotEqualTo(cacheKey(request, Path.of(".agentscope/workspace/users/user-2/stories/1")));
    }

    @Test
    void parsesRequestUserIdForToolIdentity() {
        assertThat(AgentScopeHarnessAgentGateway.parseUserIdForTool("42")).isEqualTo(42L);
    }

    @Test
    void rejectsInvalidToolUserId() {
        assertThatThrownBy(() -> AgentScopeHarnessAgentGateway.parseUserIdForTool("user-42"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid agent user id");
    }

    private static AgentRunRequest requestWithSpec(String userId, AgentModelSpec spec) {
        return new AgentRunRequest(
                userId,
                1L,
                2L,
                AgentTaskType.CHAT,
                List.of(new AgentMessage("user", "hi")),
                Map.of(),
                spec,
                "secret"
        );
    }

    private static String cacheKey(AgentRunRequest request) {
        return cacheKey(request, null);
    }

    private static String cacheKey(AgentRunRequest request, Path workspace) {
        return AgentScopeHarnessAgentGateway.buildAgentCacheKey(
                request,
                new AgentModelSpec("deepseek", "https://api.deepseek.com", "deepseek-chat", "fallback"),
                workspace
        );
    }
}
