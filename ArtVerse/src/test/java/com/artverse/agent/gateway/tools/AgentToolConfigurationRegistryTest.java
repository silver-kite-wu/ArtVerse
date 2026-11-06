package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import com.artverse.agent.AgentRunRequest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolConfigurationRegistryTest {

    @Test
    void delegatesToStrategyIndexedByTaskType() {
        AgentToolConfigurationStrategy manga = strategy(EnumSet.of(AgentTaskType.MANGA_DIRECTOR));
        AgentToolConfigurationStrategy remaining = strategy(EnumSet.complementOf(
                EnumSet.of(AgentTaskType.MANGA_DIRECTOR)));
        AgentToolConfigurationRegistry registry = new AgentToolConfigurationRegistry(List.of(manga, remaining));
        Toolkit toolkit = mock(Toolkit.class);
        AgentRunRequest request = new AgentRunRequest("1", 2L, 3L, AgentTaskType.MANGA_DIRECTOR, List.of(), java.util.Map.of(), null, "");

        registry.configure(toolkit, request);

        verify(manga).configure(toolkit, request);
    }

    @Test
    void rejectsMissingTaskTypeStrategy() {
        AgentToolConfigurationStrategy incomplete = strategy(EnumSet.complementOf(
                EnumSet.of(AgentTaskType.MANGA_REVIEW)));

        assertThatThrownBy(() -> new AgentToolConfigurationRegistry(List.of(incomplete)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANGA_REVIEW");
    }

    @Test
    void rejectsDuplicateTaskTypeStrategy() {
        AgentToolConfigurationStrategy all = strategy(EnumSet.allOf(AgentTaskType.class));
        AgentToolConfigurationStrategy duplicate = strategy(Set.of(AgentTaskType.CHAT));

        assertThatThrownBy(() -> new AgentToolConfigurationRegistry(List.of(all, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT");
    }

    private AgentToolConfigurationStrategy strategy(Set<AgentTaskType> taskTypes) {
        AgentToolConfigurationStrategy strategy = mock(AgentToolConfigurationStrategy.class);
        when(strategy.supportedTaskTypes()).thenReturn(taskTypes);
        return strategy;
    }
}
