package com.artverse.agent.gateway.tools;

import com.artverse.application.tools.MangaHitlTools;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class AgentToolGroupSupport {
    static final String CONTEXT_TOOLS = "context-tools";
    static final String STORYBOARD_TOOLS = "storyboard-tools";
    static final String HITL_TOOLS = "hitl-tools";
    static final String STORY_CHAT_CONTEXT_TOOLS = "story-chat-context-tools";
    static final String STORY_CHAT_WRITE_TOOLS = "story-chat-write-tools";

    private final MangaHitlTools mangaHitlTools;

    void configureContext(Toolkit toolkit, McpClientWrapper businessTools) {
        configureContext(toolkit, businessTools, false);
    }

    void configureContext(Toolkit toolkit, McpClientWrapper businessTools, boolean allowScheduling) {
        registerRemote(toolkit, businessTools, CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.",
                allowScheduling ? List.of("get_chapter_context", "schedule_manga_generation", "create_automation", "list_automations", "update_automation") : List.of("get_chapter_context"));
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS));
    }

    void configureStoryboard(Toolkit toolkit, McpClientWrapper businessTools) {
        registerRemote(toolkit, businessTools, CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.",
                List.of("get_chapter_context"));
        registerRemote(toolkit, businessTools, STORYBOARD_TOOLS,
                "Validated storyboard draft and commit tools.",
                List.of("draft_structured_storyboard", "commit_storyboard"));
        createGroup(toolkit, HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.", mangaHitlTools);
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, STORYBOARD_TOOLS, HITL_TOOLS));
    }

    void configureDirector(Toolkit toolkit, McpClientWrapper businessTools) {
        registerRemote(toolkit, businessTools, CONTEXT_TOOLS,
                "Read-only manga chapter, story, storyboard, and image context tools.",
                List.of("get_chapter_context"));
        createGroup(toolkit, HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.", mangaHitlTools);
        toolkit.setActiveGroups(List.of(CONTEXT_TOOLS, HITL_TOOLS));
    }

    void configureStoryChatRead(Toolkit toolkit, McpClientWrapper businessTools) {
        registerRemote(toolkit, businessTools, STORY_CHAT_CONTEXT_TOOLS,
                "Read-only novel chapter context tools.", List.of("get_novel_context", "create_automation", "list_automations", "update_automation"));
        toolkit.setActiveGroups(List.of(STORY_CHAT_CONTEXT_TOOLS));
    }

    void configureStoryChatWrite(Toolkit toolkit, McpClientWrapper businessTools) {
        registerRemote(toolkit, businessTools, STORY_CHAT_CONTEXT_TOOLS,
                "Read-only novel chapter context tools.", List.of("get_novel_context", "create_automation", "list_automations", "update_automation"));
        registerRemote(toolkit, businessTools, STORY_CHAT_WRITE_TOOLS,
                "Safe two-phase novel content draft and commit tools.",
                List.of("draft_novel_content", "commit_novel_content"));
        createGroup(toolkit, HITL_TOOLS,
                "Human-in-the-loop tools for asking the user to choose or confirm.", mangaHitlTools);
        toolkit.setActiveGroups(List.of(STORY_CHAT_CONTEXT_TOOLS, STORY_CHAT_WRITE_TOOLS, HITL_TOOLS));
    }

    private void registerRemote(Toolkit toolkit, McpClientWrapper client, String group,
                                String description, List<String> tools) {
        toolkit.createToolGroup(group, description, true);
        toolkit.registration().mcpClient(client).enableTools(tools).group(group).apply();
    }

    private void createGroup(Toolkit toolkit, String name, String description, Object tools) {
        toolkit.createToolGroup(name, description, true);
        toolkit.registration().tool(tools).group(name).apply();
    }
}
