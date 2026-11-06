package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import com.artverse.agent.AgentRunRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class NoAgentToolsStrategy implements AgentToolConfigurationStrategy {
    private static final Set<AgentTaskType> TASK_TYPES = Set.of(
            AgentTaskType.CHAT, AgentTaskType.NOVEL, AgentTaskType.MANGA_ROUTER,
            AgentTaskType.KNOWLEDGE_EXTRACTION, AgentTaskType.READER_CHARACTER_CHAT);

    @Override
    public Set<AgentTaskType> supportedTaskTypes() {
        return TASK_TYPES;
    }

    @Override
    public void configure(Toolkit toolkit, AgentRunRequest request) {
        // These agents intentionally have no ArtVerse business tools.
    }
}
