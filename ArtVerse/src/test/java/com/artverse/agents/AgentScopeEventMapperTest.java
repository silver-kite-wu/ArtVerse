package com.artverse.agents;

import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    private final AgentScopeEventMapper mapper = new AgentScopeEventMapper();

    @Test
    void mapsModelCallStartToThinkingEvent() {
        AgentRunEvent event = mapper.map(new ModelCallStartEvent("reply-1")).orElseThrow();

        assertThat(event.type()).isEqualTo("model_started");
        assertThat(event.phase()).isEqualTo("thinking");
        assertThat(event.label()).contains("模型正在分析");
    }

    @Test
    void mapsToolCallStartToToolEvent() {
        AgentRunEvent event = mapper.map(new ToolCallStartEvent("reply-1", "tool-1", "save_structured_storyboard"))
                .orElseThrow();

        assertThat(event.type()).isEqualTo("tool_call_started");
        assertThat(event.toolName()).isEqualTo("save_structured_storyboard");
        assertThat(event.label()).contains("保存结构化分镜");
    }

    @Test
    void mapsTextDeltaToReplyDelta() {
        AgentRunEvent event = mapper.map(new TextBlockDeltaEvent("reply-1", "block-1", "你好"))
                .orElseThrow();

        assertThat(event.type()).isEqualTo("text_delta");
        assertThat(event.text()).isEqualTo("你好");
    }
}
