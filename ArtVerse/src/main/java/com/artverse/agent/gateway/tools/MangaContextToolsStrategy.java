package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
class MangaContextToolsStrategy implements AgentToolConfigurationStrategy {
    private static final Set<AgentTaskType> TASK_TYPES = Set.of(AgentTaskType.MANGA_CREATIVE, AgentTaskType.MANGA_REVIEW);

    private final AgentToolGroupSupport toolGroups;

    @Override
    public Set<AgentTaskType> supportedTaskTypes() {
        return TASK_TYPES;
    }

    @Override
    public void configure(Toolkit toolkit, McpClientWrapper businessTools) {
        toolGroups.configureContext(toolkit, businessTools);
    }
}
