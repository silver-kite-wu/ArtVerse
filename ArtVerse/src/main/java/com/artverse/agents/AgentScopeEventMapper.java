package com.artverse.agents;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class AgentScopeEventMapper {

    public Optional<AgentRunEvent> map(AgentEvent event) {
        if (event instanceof AgentStartEvent start) {
            return Optional.of(new AgentRunEvent(
                    "run_started",
                    "started",
                    "智能体已启动",
                    null,
                    "running",
                    null,
                    Map.of("agent", start.getName()),
                    java.time.OffsetDateTime.now()
            ));
        }
        if (event instanceof ModelCallStartEvent) {
            return Optional.of(AgentRunEvent.of("model_started", "thinking", "模型正在分析当前章节"));
        }
        if (event instanceof ModelCallEndEvent) {
            return Optional.of(AgentRunEvent.of("model_finished", "thinking", "模型分析完成"));
        }
        if (event instanceof ThinkingBlockStartEvent) {
            return Optional.of(AgentRunEvent.of("thinking_started", "thinking", "智能体正在推理"));
        }
        if (event instanceof ThinkingBlockDeltaEvent) {
            return Optional.empty();
        }
        if (event instanceof ToolCallStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_started",
                    labelForTool(tool.getToolCallName(), "准备调用"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolCallEndEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_call_ready",
                    labelForTool(tool.getToolCallName(), "工具参数已准备"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultStartEvent tool) {
            return Optional.of(AgentRunEvent.tool(
                    "tool_started",
                    labelForTool(tool.getToolCallName(), "正在执行"),
                    tool.getToolCallName(),
                    "running",
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String status = tool.getState() == null ? "finished" : tool.getState().name().toLowerCase();
            return Optional.of(AgentRunEvent.tool(
                    "tool_finished",
                    labelForTool(tool.getToolCallName(), "工具执行完成"),
                    tool.getToolCallName(),
                    status,
                    Map.of("toolCallId", tool.getToolCallId())
            ));
        }
        if (event instanceof TextBlockDeltaEvent text) {
            String delta = text.getDelta();
            return delta == null || delta.isBlank() ? Optional.empty() : Optional.of(AgentRunEvent.text(delta));
        }
        if (event instanceof AgentResultEvent) {
            return Optional.of(AgentRunEvent.of("reply_ready", "replying", "最终回复已生成"));
        }
        if (event instanceof AgentEndEvent) {
            return Optional.of(AgentRunEvent.of("run_finished", "finished", "智能体运行结束"));
        }
        return Optional.empty();
    }

    private String labelForTool(String toolName, String prefix) {
        return prefix + "：" + switch (toolName == null ? "" : toolName) {
            case "get_chapter_context" -> "读取章节上下文";
            case "generate_storyboard" -> "生成分镜";
            case "save_storyboard" -> "保存分镜";
            case "save_structured_storyboard" -> "保存结构化分镜";
            case "ask_user" -> "询问用户";
            default -> toolName == null || toolName.isBlank() ? "工具" : toolName;
        };
    }
}
