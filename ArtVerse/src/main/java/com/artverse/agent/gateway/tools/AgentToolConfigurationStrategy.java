package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

import java.util.Set;

public interface AgentToolConfigurationStrategy {
    Set<AgentTaskType> supportedTaskTypes();

    void configure(Toolkit toolkit, McpClientWrapper businessTools);
}
