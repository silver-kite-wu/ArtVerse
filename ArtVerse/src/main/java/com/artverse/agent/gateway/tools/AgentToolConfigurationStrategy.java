package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import com.artverse.agent.AgentRunRequest;

import java.util.Set;

public interface AgentToolConfigurationStrategy {
    Set<AgentTaskType> supportedTaskTypes();

    void configure(Toolkit toolkit, AgentRunRequest request);
}
