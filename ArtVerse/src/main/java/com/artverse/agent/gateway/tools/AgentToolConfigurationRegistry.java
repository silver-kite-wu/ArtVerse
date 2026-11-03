package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentTaskType;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AgentToolConfigurationRegistry {
    private final Map<AgentTaskType, AgentToolConfigurationStrategy> strategies;

    public AgentToolConfigurationRegistry(List<AgentToolConfigurationStrategy> candidates) {
        EnumMap<AgentTaskType, AgentToolConfigurationStrategy> indexed = new EnumMap<>(AgentTaskType.class);
        for (AgentToolConfigurationStrategy candidate : candidates) {
            for (AgentTaskType taskType : candidate.supportedTaskTypes()) {
                AgentToolConfigurationStrategy existing = indexed.putIfAbsent(taskType, candidate);
                if (existing != null) {
                    throw new IllegalStateException("Multiple tool configuration strategies for task type " + taskType);
                }
            }
        }

        EnumSet<AgentTaskType> missing = EnumSet.allOf(AgentTaskType.class);
        missing.removeAll(indexed.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing tool configuration strategies for task types " + missing);
        }
        this.strategies = Map.copyOf(indexed);
    }

    public void configure(Toolkit toolkit, AgentTaskType taskType, McpClientWrapper businessTools) {
        Objects.requireNonNull(toolkit, "toolkit must not be null");
        Objects.requireNonNull(taskType, "taskType must not be null");
        strategies.get(taskType).configure(toolkit, Objects.requireNonNull(businessTools, "businessTools"));
    }
}
