package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import com.artverse.agent.AgentRunRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
class MangaDirectorToolsStrategy implements AgentToolConfigurationStrategy {
    private final AgentToolGroupSupport toolGroups;

    @Override
    public Set<AgentTaskType> supportedTaskTypes() {
        return Set.of(AgentTaskType.MANGA_DIRECTOR);
    }

    @Override
    public void configure(Toolkit toolkit, AgentRunRequest request) {
        toolGroups.configureDirector(toolkit, request);
    }
}
