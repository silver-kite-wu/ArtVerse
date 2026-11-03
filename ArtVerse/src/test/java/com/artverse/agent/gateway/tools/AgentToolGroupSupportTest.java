package com.artverse.agent.gateway.tools;

import com.artverse.application.tools.MangaHitlTools;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolGroupSupportTest {

    private MangaHitlTools hitlTools;
    private Toolkit toolkit;
    private McpClientWrapper businessTools;
    private AgentToolGroupSupport support;

    @BeforeEach
    void setUp() {
        hitlTools = mock(MangaHitlTools.class);
        toolkit = mock(Toolkit.class);
        businessTools = mock(McpClientWrapper.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(toolkit.registration()).thenReturn(registration);
        when(registration.tool(any())).thenReturn(registration);
        when(registration.mcpClient(any())).thenReturn(registration);
        when(registration.enableTools(any())).thenReturn(registration);
        when(registration.group(anyString())).thenReturn(registration);
        support = new AgentToolGroupSupport(hitlTools);
    }

    @Test
    void contextConfigurationActivatesOnlyReadTools() {
        support.configureContext(toolkit, businessTools);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.STORYBOARD_TOOLS), anyString(), eq(true));
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.HITL_TOOLS), anyString(), eq(true));
        verify(toolkit).setActiveGroups(List.of(AgentToolGroupSupport.CONTEXT_TOOLS));
    }

    @Test
    void scheduledGenerationToolIsExposedOnlyWhenConversationRouteRequestsIt() {
        support.configureContext(toolkit,businessTools,true);

        verify(toolkit.registration()).enableTools(List.of("get_chapter_context","schedule_manga_generation","create_automation","list_automations","update_automation"));
    }

    @Test
    void storyboardConfigurationActivatesAllMangaTools() {
        support.configureStoryboard(toolkit, businessTools);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.STORYBOARD_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.HITL_TOOLS);
        verify(toolkit.registration(), org.mockito.Mockito.atLeastOnce()).mcpClient(businessTools);
        verify(toolkit).setActiveGroups(List.of(
                AgentToolGroupSupport.CONTEXT_TOOLS,
                AgentToolGroupSupport.STORYBOARD_TOOLS,
                AgentToolGroupSupport.HITL_TOOLS));
    }

    @Test
    void directorConfigurationExcludesStoryboardMutationTools() {
        support.configureDirector(toolkit, businessTools);

        verifyGroupCreated(AgentToolGroupSupport.CONTEXT_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.HITL_TOOLS);
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.STORYBOARD_TOOLS), anyString(), eq(true));
        verify(toolkit).setActiveGroups(List.of(
                AgentToolGroupSupport.CONTEXT_TOOLS,
                AgentToolGroupSupport.HITL_TOOLS));
    }

    @Test
    void storyChatReadConfigurationExcludesWriteTools() {
        support.configureStoryChatRead(toolkit, businessTools);

        verifyGroupCreated(AgentToolGroupSupport.STORY_CHAT_CONTEXT_TOOLS);
        verify(toolkit, never()).createToolGroup(eq(AgentToolGroupSupport.STORY_CHAT_WRITE_TOOLS), anyString(), eq(true));
        verify(toolkit).setActiveGroups(List.of(AgentToolGroupSupport.STORY_CHAT_CONTEXT_TOOLS));
    }

    @Test
    void storyChatWriteConfigurationActivatesDraftCommitAndHitlTools() {
        support.configureStoryChatWrite(toolkit, businessTools);

        verifyGroupCreated(AgentToolGroupSupport.STORY_CHAT_CONTEXT_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.STORY_CHAT_WRITE_TOOLS);
        verifyGroupCreated(AgentToolGroupSupport.HITL_TOOLS);
        verify(toolkit.registration(), org.mockito.Mockito.atLeastOnce()).mcpClient(businessTools);
        verify(toolkit).setActiveGroups(List.of(
                AgentToolGroupSupport.STORY_CHAT_CONTEXT_TOOLS,
                AgentToolGroupSupport.STORY_CHAT_WRITE_TOOLS,
                AgentToolGroupSupport.HITL_TOOLS));
    }

    private void verifyGroupCreated(String group) {
        verify(toolkit).createToolGroup(eq(group), anyString(), eq(true));
    }
}
