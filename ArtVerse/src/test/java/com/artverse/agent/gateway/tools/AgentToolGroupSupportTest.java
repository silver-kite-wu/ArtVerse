package com.artverse.agent.gateway.tools;

import com.artverse.agent.*;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.application.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolGroupSupportTest {
    final MangaToolSupport scope = mock(MangaToolSupport.class);
    final AgentToolAuditService audit = mock(AgentToolAuditService.class);
    final NovelContentArtifactService artifacts = mock(NovelContentArtifactService.class);
    final MangaStoryboardTools storyboard = mock(MangaStoryboardTools.class);
    final ObjectMapper mapper = new ObjectMapper();
    final AgentToolGroupSupport groups = new AgentToolGroupSupport(
            new MangaHitlTools(audit, scope, mock(MangaAgentRunService.class)),
            new MangaContextTools(mock(MangaChapterContextReader.class)),
            new StoryChatContextTools(scope, audit, mock(ChapterAccessService.class)),
            new StoryboardAgentTools(storyboard, mapper), new StoryboardCommitAgentTools(storyboard),
            new StoryChatNovelContentTools(scope, audit, artifacts),
            new AutomationReadAgentTools(mock(AutomationAgentTools.class), scope),
            new AutomationManageAgentTools(mock(AutomationAgentTools.class), scope, mapper, audit),
            new StoryContextReadTools(mock(AgentStoryKnowledgeReadService.class), scope),
            new KnowledgeReadTools(mock(AgentStoryKnowledgeReadService.class), scope),
            new ReferenceAssetReadTools(mock(AgentStoryKnowledgeReadService.class), scope),
            new KnowledgeCandidateReadTools(mock(AgentStoryKnowledgeReadService.class), scope),
            new ArtifactReadAgentTools(scope, audit, mock(AgentArtifactAccessService.class)),
            new ArtifactDraftAgentTools(scope, audit, mock(AgentArtifactAccessService.class)),
            new NovelHistoryAgentTools(scope, audit, mock(NovelHistoryAgentService.class)),
            new SettingsDraftAgentTools(scope,audit,mock(SettingsProposalService.class)),
            new KnowledgeDraftAgentTools(scope,audit,mock(KnowledgeProposalService.class)),
            new ImageProposalAgentTools(scope,audit,mock(com.artverse.application.imageproposal.ImageProposalService.class)));
    final Set<String> contextReads = Set.of("get_story_context", "list_chapters");
    final Set<String> automationReads = Set.of("list_automations", "get_automation", "list_automation_runs", "get_automation_run", "preview_automation");

    String[] withContext(String... names) {
        var all = new HashSet<>(contextReads);
        all.addAll(List.of(names));
        return all.toArray(String[]::new);
    }

    AgentRunRequest request(AgentTaskType type, String text) {
        return new AgentRunRequest("1", 2L, 3L, type, List.of(new AgentMessage("user", text)), Map.of(), null, "");
    }

    @Test void readToolsDoNotIncludeWritesOrLegacyNames() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatRead(tools, request(AgentTaskType.STORY_CHAT_READ, "Read this chapter"));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrder(withContext("get_novel_context"));
        assertThat(tools.getToolNames()).doesNotContain("draft_novel_content", "discard_artifact", "ask_user");
    }

    @Test void novelWriteExposesDraftAndExternalReviewWithoutModelCommit() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatWrite(tools, request(AgentTaskType.STORY_CHAT_WRITE, "Polish this chapter"));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrder(withContext("get_novel_context", "draft_novel_content", "ask_user", "get_artifact", "list_artifacts", "discard_artifact"));
        var schema = tools.getTool("draft_novel_content").getParameters();
        assertThat(((Map<?,?>) schema.get("properties")).keySet().stream().map(Object::toString).toList()).contains("content").doesNotContain("runtimeContext", "user_id");
        assertThat(((List<?>) schema.get("required")).stream().map(Object::toString).toList()).contains("content");
        assertThat(tools.isExternalTool("ask_user")).isTrue();
    }

    @Test void explicitManagementRequestActivatesSeparateAutomationGroups() {
        Toolkit tools = new Toolkit();
        groups.configureContext(tools, request(AgentTaskType.MANGA_CONVERSATION, "Please create a plan to generate this chapter tomorrow"));
        var expected = new HashSet<>(automationReads); expected.addAll(List.of(withContext("get_chapter_context", "create_automation", "update_automation", "ask_user")));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(tools.getToolGroup("automation-manage")).isNotNull();
        assertThat(tools.getToolNames()).doesNotContain("schedule_manga_generation");
    }

    @Test void writeAndManagementComposeWithoutRegisteringHitlTwice() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatWrite(tools, request(AgentTaskType.STORY_CHAT_WRITE,
                "Please create a plan to generate this chapter tomorrow"));
        var expected = new HashSet<>(automationReads); expected.addAll(List.of(withContext("get_novel_context", "draft_novel_content", "ask_user", "get_artifact", "list_artifacts", "discard_artifact", "create_automation", "update_automation")));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(tools.isExternalTool("ask_user")).isTrue();
        assertThat(tools.getTool("ask_user").isReadOnly()).isFalse();
    }

    @Test void summaryCannotInheritWriteOrManagementTools() {
        Toolkit tools = new Toolkit();
        var original = request(AgentTaskType.STORY_CHAT_WRITE, "Please create a plan to generate this chapter tomorrow");
        var summary = new AgentRunRequest(original.userId(), original.storyId(), original.chapterId(), original.taskType(),
                original.messages(), Map.of("review_completed", true), null, "");
        groups.configureStoryChatWrite(tools, summary);
        assertThat(tools.getToolNames()).containsExactly("get_novel_context");
    }

    @Test void generatedExternalSchemaRejectsInvalidQuestionsBeforePause() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatWrite(tools, request(AgentTaskType.STORY_CHAT_WRITE, "Polish"));
        var schema = tools.getTool("ask_user").getParameters();
        assertThat(ToolValidator.validateInput("{\"question\":\"\",\"options\":[\"a\"]}", schema)).isNotNull();
        assertThat(ToolValidator.validateInput("{\"question\":\"ok\",\"options\":[]}", schema)).isNotNull();
        assertThat(ToolValidator.validateInput("{\"question\":\"ok\",\"options\":[\"a\"],\"confirmed\":true}", schema)).isNotNull();
        assertThat(ToolValidator.validateInput("{\"question\":\"ok\",\"options\":[\"a\"]}", schema)).isNull();
    }

    @Test void featureQuestionDoesNotGrantManagement() {
        assertThat(AgentToolCapability.requestsAutomationManagement(request(AgentTaskType.MANGA_CONVERSATION, "How to create a plan?"))).isFalse();
        assertThat(AgentToolCapability.requestsAutomationManagement(request(AgentTaskType.MANGA_CONVERSATION, "不要帮我创建任务"))).isFalse();
    }

    @Test void scheduleQueryGetsReadGroupWithoutManagement() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatRead(tools, request(AgentTaskType.STORY_CHAT_READ, "当前作品有哪些定时任务？"));
        var expected = new HashSet<>(automationReads); expected.addAll(List.of(withContext("get_novel_context")));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(tools.getToolNames()).doesNotContain("create_automation", "update_automation");
    }

    @Test void unknownFieldsAreRejectedBeforeBusinessInvocation() {
        Toolkit tools = new Toolkit();
        groups.configureStoryboard(tools, request(AgentTaskType.MANGA_STORYBOARD, "Make storyboard"));
        var call = new ToolUseBlock("unknown-input", "draft_structured_storyboard", Map.of("pages", List.of(), "user_id", 99),
                "{\"pages\":[],\"user_id\":99}", Map.of());
        var result = tools.callTool(ToolCallParam.builder().toolUseBlock(call).input(call.getInput())
                .runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(result.getOutput().toString()).contains("validation");
        verifyNoInteractions(storyboard);
    }

    @Test void storyboardSchemaIsNestedAndInvalidPayloadNeverReachesCommand() {
        Toolkit tools = new Toolkit();
        groups.configureStoryboard(tools, request(AgentTaskType.MANGA_STORYBOARD, "Make storyboard"));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrder(withContext("get_chapter_context", "draft_structured_storyboard", "commit_storyboard", "ask_user", "get_artifact", "list_artifacts", "discard_artifact"));
        String schema = mapper.valueToTree(tools.getTool("draft_structured_storyboard").getParameters()).toString();
        assertThat(schema).contains("panels", "description").doesNotContain("user_id");
        var call = new ToolUseBlock("call-invalid", "draft_structured_storyboard", Map.of("pages", "not-an-array"),
                "{\"pages\":\"not-an-array\"}", Map.of());
        var result = tools.callTool(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(RuntimeContext.empty()).build()).block();
        assertThat(result).isNotNull();
        verifyNoInteractions(storyboard);
    }

    @Test void duplicateRegistrationFailsInsteadOfReplacingTool() {
        Toolkit tools = new Toolkit();
        groups.configureStoryChatRead(tools, request(AgentTaskType.STORY_CHAT_READ, "Read"));
        assertThatThrownBy(() -> groups.configureStoryChatRead(tools, request(AgentTaskType.STORY_CHAT_READ, "Read")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("overlapping");
    }

    @Test void stageCPublishesExactlyThirtyFiveBusinessToolsAcrossViews() {
        var all = new HashSet<String>();
        for (var type : List.of(AgentTaskType.STORY_CHAT_WRITE, AgentTaskType.MANGA_STORYBOARD, AgentTaskType.MANGA_CONVERSATION)) {
            var tools = new Toolkit();
            var request = request(type, "请帮我创建定时任务计划，修改设定和人物、追加知识候选、准备漫画生图，查看参考素材、产物，并恢复历史正文版本");
            if (type == AgentTaskType.STORY_CHAT_WRITE) groups.configureStoryChatWrite(tools, request);
            else if (type == AgentTaskType.MANGA_STORYBOARD) groups.configureStoryboard(tools, request);
            else groups.configureContext(tools, request);
            all.addAll(tools.getToolNames());
            for (String name : tools.getToolNames()) {
                assertThat(tools.getTool(name).getParameters()).containsEntry("additionalProperties", false);
                assertThat(mapper.valueToTree(tools.getTool(name).getParameters()).toString())
                        .doesNotContain("execution_owner", "fencing_token", "user_id");
            }
        }
        assertThat(all).containsExactlyInAnyOrder("get_story_context", "list_chapters", "get_novel_context", "get_chapter_context",
                "search_story_knowledge", "get_knowledge_detail", "get_character_context", "list_reference_assets",
                "get_artifact", "list_artifacts", "discard_artifact", "draft_novel_content", "list_novel_revisions",
                "get_novel_revision", "draft_novel_restore", "draft_structured_storyboard", "commit_storyboard",
                "list_knowledge_candidates", "get_knowledge_candidate", "preview_automation", "create_automation",
                "list_automations", "get_automation", "update_automation", "list_automation_runs", "get_automation_run", "ask_user",
                "draft_story_settings","draft_character_profile","draft_knowledge_change","list_generation_models",
                "preview_manga_generation","propose_manga_generation","get_generation_request","propose_generation_recovery");
    }

    @Test void stageCUsesClosedTypedSchemasAndNeverPublishesAdministrativeRecovery() {
        var tools=new Toolkit();groups.configureStoryChatRead(tools,request(AgentTaskType.STORY_CHAT_READ,"请修改作品设定并准备漫画生图恢复提案"));
        assertThat(ToolValidator.validateInput("{\"base_version\":0,\"patch\":{\"is_published\":true}}",tools.getTool("draft_story_settings").getParameters())).isNotNull();
        String schema=mapper.valueToTree(tools.getTool("propose_generation_recovery").getParameters()).toString();
        assertThat(schema).contains("RECHECK","REQUEST_REVIEW","RESUME_SAFE","RELEASE_CONFIRMED").doesNotContain("ADMIN_DECISION","confirm_external_ended","api_key");
        assertThat(tools.getTool("preview_manga_generation").isReadOnly()).isTrue();
        assertThat(tools.getTool("propose_manga_generation").isReadOnly()).isFalse();
    }

    @Test void descriptiveCreativeQueriesAndBackgroundExecutionDoNotExposeProposalWrites() {
        var tools=new Toolkit();groups.configureStoryChatRead(tools,request(AgentTaskType.STORY_CHAT_READ,"查看人物设定、知识候选和图片请求状态"));
        assertThat(tools.getToolNames()).contains("list_generation_models","preview_manga_generation","get_generation_request")
                .doesNotContain("draft_story_settings","draft_character_profile","draft_knowledge_change","propose_manga_generation","propose_generation_recovery");
        var original=request(AgentTaskType.STORY_CHAT_READ,"请修改人物和知识，准备漫画生图提案");
        var background=new AgentRunRequest(original.userId(),original.storyId(),original.chapterId(),original.taskType(),original.messages(),Map.of("automation_run_id",UUID.randomUUID().toString()),null,"");
        var backgroundTools=new Toolkit();groups.configureStoryChatRead(backgroundTools,background);
        assertThat(backgroundTools.getToolNames()).doesNotContain("draft_story_settings","draft_character_profile","draft_knowledge_change","propose_manga_generation","propose_generation_recovery");
    }

    @Test void readIntentDoesNotAcquireRestoreDiscardOrOtherOptionalReadGroups() {
        var tools = new Toolkit();
        groups.configureStoryChatRead(tools, request(AgentTaskType.STORY_CHAT_READ, "查看历史正文版本"));
        assertThat(tools.getToolNames()).containsExactlyInAnyOrder(withContext("get_novel_context", "list_novel_revisions", "get_novel_revision"));
        assertThat(tools.getToolNames()).doesNotContain("draft_novel_restore", "discard_artifact", "search_story_knowledge");
    }
}
