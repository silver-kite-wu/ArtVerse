package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import com.artverse.agent.AgentRunRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
@RequiredArgsConstructor
class MangaConversationToolsStrategy implements AgentToolConfigurationStrategy {
    private final AgentToolGroupSupport toolGroups;
    @Override public Set<AgentTaskType> supportedTaskTypes() { return Set.of(AgentTaskType.MANGA_CONVERSATION); }
    @Override public void configure(Toolkit toolkit, AgentRunRequest request) {
        toolGroups.configureContext(toolkit, request);
    }
}
