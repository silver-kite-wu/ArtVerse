package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentRunRequest;
import com.artverse.application.tools.*;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.HashSet;

@Component
@RequiredArgsConstructor
class AgentToolGroupSupport {
    static final String CONTEXT_TOOLS = "story-context";
    static final String STORYBOARD_TOOLS = "storyboard-draft";
    static final String HITL_TOOLS = "hitl";
    static final String STORY_CHAT_CONTEXT_TOOLS = CONTEXT_TOOLS;
    static final String STORY_CHAT_WRITE_TOOLS = "novel-draft";
    private final MangaHitlTools hitl;
    private final MangaContextTools mangaContext;
    private final StoryChatContextTools novelContext;
    private final StoryboardAgentTools storyboardDraft;
    private final StoryboardCommitAgentTools storyboardCommit;
    private final StoryChatNovelContentTools novelDraft;
    private final AutomationReadAgentTools automationRead;
    private final AutomationManageAgentTools automationManage;
    private final StoryContextReadTools storyReads;
    private final KnowledgeReadTools knowledgeReads;
    private final ReferenceAssetReadTools assetReads;
    private final KnowledgeCandidateReadTools candidateReads;
    private final ArtifactReadAgentTools artifactReads;
    private final ArtifactDraftAgentTools artifactDrafts;
    private final NovelHistoryAgentTools novelHistory;
    private final SettingsDraftAgentTools settingsDraft;
    private final KnowledgeDraftAgentTools knowledgeDraft;
    private final ImageProposalAgentTools imageProposals;

    void configureContext(Toolkit toolkit, AgentRunRequest request) {
        group(toolkit, CONTEXT_TOOLS, mangaContext);
        if (request.variables().get("review_expert") instanceof com.artverse.agent.AgentReviewExpert) return;
        reads(toolkit, request, false);
        automation(toolkit, request);
        creative(toolkit, request);
        if (AgentToolCapability.requestsAutomationManagement(request)) group(toolkit, HITL_TOOLS, hitl);
    }
    void configureStoryboard(Toolkit toolkit, AgentRunRequest request) {
        group(toolkit, CONTEXT_TOOLS, mangaContext);
        reads(toolkit, request, true);
        group(toolkit, STORYBOARD_TOOLS, storyboardDraft);
        group(toolkit, "storyboard-commit", storyboardCommit);
        group(toolkit, HITL_TOOLS, hitl);
    }
    void configureDirector(Toolkit toolkit, AgentRunRequest request) {
        group(toolkit, CONTEXT_TOOLS, mangaContext);
        reads(toolkit, request, false);
        group(toolkit, HITL_TOOLS, hitl);
    }
    void configureStoryChatRead(Toolkit toolkit, AgentRunRequest request) {
        group(toolkit, Boolean.TRUE.equals(request.variables().get("review_completed")) ? "review-summary" : CONTEXT_TOOLS, novelContext);
        if (!Boolean.TRUE.equals(request.variables().get("review_completed"))) {
            reads(toolkit, request, false);
            automation(toolkit, request);
            creative(toolkit, request);
            if (AgentToolCapability.requestsAutomationManagement(request)) group(toolkit, HITL_TOOLS, hitl);
        }
    }
    void configureStoryChatWrite(Toolkit toolkit, AgentRunRequest request) {
        if (Boolean.TRUE.equals(request.variables().get("review_completed"))) {
            configureStoryChatRead(toolkit, request);
            return;
        }
        group(toolkit, CONTEXT_TOOLS, novelContext);
        reads(toolkit, request, true);
        automation(toolkit, request);
        creative(toolkit, request);
        if (requested(request, "恢复", "还原", "回滚", "restore", "revert", "rollback"))
            group(toolkit, STORY_CHAT_WRITE_TOOLS, novelDraft);
        else group(toolkit, STORY_CHAT_WRITE_TOOLS, novelDraft, "draft_novel_content");
        group(toolkit, HITL_TOOLS, hitl);
    }
    private void automation(Toolkit toolkit, AgentRunRequest request) {
        if (AgentToolCapability.requestsAutomationRead(request)) {
            group(toolkit, "automation-read", automationRead, "list_automations", "get_automation", "list_automation_runs", "get_automation_run");
            group(toolkit, "automation-manage", automationRead, "preview_automation");
        }
        if (AgentToolCapability.requestsAutomationManagement(request)) {
            group(toolkit, "automation-manage", automationManage);
        }
    }

    private void creative(Toolkit toolkit, AgentRunRequest request) {
        if (Boolean.TRUE.equals(request.variables().get("review_completed"))
                || request.variables().get("review_expert") != null
                || request.variables().containsKey("automation_run_id")
                || !java.util.Set.of(com.artverse.agent.AgentTaskType.MANGA_CONVERSATION,
                    com.artverse.agent.AgentTaskType.STORY_CHAT_READ,com.artverse.agent.AgentTaskType.STORY_CHAT_WRITE).contains(request.taskType())) return;
        boolean drafting=creativeChangeRequested(request);
        if (drafting&&requested(request,"设定","角色","人物","标题","简介","风格","settings","character","profile","title","description","style"))
            group(toolkit,"settings-draft",settingsDraft);
        if (drafting&&requested(request,"知识","候选","knowledge","candidate")) group(toolkit,"knowledge-draft",knowledgeDraft);
        if (requested(request,"生图","图片","画图","生成漫画","重生成","image","generation","render","recovery")
                ||requested(request,"漫画")&&requested(request,"生成","恢复")) {
            group(toolkit,"asset-read",imageProposals,"list_generation_models");
            group(toolkit,"image-proposal",imageProposals,"preview_manga_generation","get_generation_request");
            if(drafting)group(toolkit,"image-proposal",imageProposals,"propose_manga_generation","propose_generation_recovery");
        }
        if ((toolkit.getToolGroup("settings-draft")!=null||toolkit.getToolGroup("knowledge-draft")!=null||toolkit.getToolGroup("image-proposal")!=null)
                && !toolkit.getToolNames().contains("get_artifact"))group(toolkit,"artifact-read",artifactReads);
    }

    private static boolean creativeChangeRequested(AgentRunRequest request) {
        if(requested(request,"不要","不需要","如何","怎么","能否","是否","how to","do not","don't","is it possible"))return false;
        return requested(request,"修改","更新","新建","创建","调整","编辑","草拟","草稿","准备","追加","生成","生图","重画","恢复","提案","draft","change","update","create","edit","prepare","propose","generate","recover","regenerate");
    }

    /** Intent selects a small read view; ownership, projection and write authority stay in domain services. */
    private void reads(Toolkit toolkit, AgentRunRequest request, boolean drafting) {
        group(toolkit, CONTEXT_TOOLS, storyReads);
        if (requested(request, "知识", "设定", "角色", "人物", "关系", "世界观", "knowledge", "character", "world", "continuity"))
            group(toolkit, "knowledge-read", knowledgeReads);
        if (requested(request, "素材", "参考图", "形象", "asset", "reference", "appearance"))
            group(toolkit, "asset-read", assetReads);
        if (requested(request, "候选", "待审", "candidate", "proposal"))
            group(toolkit, "knowledge-candidate-read", candidateReads);
        if (drafting || requested(request, "产物", "草稿", "artifact", "draft"))
            group(toolkit, "artifact-read", artifactReads);
        if (drafting) group(toolkit, "artifact-draft-management", artifactDrafts);
        if ((request.taskType() == com.artverse.agent.AgentTaskType.STORY_CHAT_READ
                || request.taskType() == com.artverse.agent.AgentTaskType.STORY_CHAT_WRITE)
                && requested(request, "历史", "版本", "恢复", "还原", "回滚", "history", "revision", "version", "restore", "revert", "rollback"))
            group(toolkit, "novel-history-read", novelHistory);
    }

    private static boolean requested(AgentRunRequest request, String... terms) {
        String text = request.messages().stream().filter(message -> "user".equalsIgnoreCase(message.role()))
                .reduce((a,b) -> b).map(message -> message.content() == null ? "" : message.content().toLowerCase(java.util.Locale.ROOT)).orElse("");
        return java.util.Arrays.stream(terms).anyMatch(text::contains);
    }
    static void group(Toolkit toolkit, String group, Object wrapper, String... included) {
        var names = new HashSet<String>();
        if (wrapper instanceof AgentTool tool) names.add(tool.getName());
        else for (Method method : wrapper.getClass().getMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.name().isBlank() ? method.getName() : annotation.name();
                if (!names.add(name)) throw new IllegalStateException("Duplicate tool: " + name);
            }
        }
        if (included.length > 0) {
            var selected = java.util.Set.of(included);
            if (!names.containsAll(selected)) throw new IllegalStateException("Missing explicitly selected tool: " + group);
            names.retainAll(selected);
        }
        if (names.isEmpty() || names.stream().anyMatch(toolkit.getToolNames()::contains)) {
            throw new IllegalStateException("Empty or overlapping business tool registration: " + group);
        }
        if (toolkit.getToolGroup(group) == null) toolkit.createToolGroup(group, "ArtVerse " + group, true);
        // Let the native reflection registrar define the contract before publishing its guarded delegate.
        Toolkit generated = new Toolkit();
        generated.registerTool(wrapper);
        if (!generated.getToolNames().containsAll(names)) throw new IllegalStateException("Incomplete registration: " + group);
        // Only reflection registration creates a fresh mutable root schema here.
        // Native AgentTool instances own their schemas, which may use Map.of.
        if (!(wrapper instanceof AgentTool)) names.forEach(name -> {
            var schema = generated.getTool(name).getParameters();
            var constrained = closeObjectSchemas(schema);
            if ("ask_user".equals(name)) constrainQuestion(constrained);
            schema.clear();
            schema.putAll(constrained);
        });
        names.forEach(name -> toolkit.registration().agentTool(new AgentToolInvocationGuard(generated.getTool(name)))
                .group(group).apply());
    }

    @SuppressWarnings("unchecked")
    private static void constrainQuestion(java.util.Map<String, Object> schema) {
        var fields = (java.util.Map<String, Object>) schema.get("properties");
        var question = (java.util.Map<String, Object>) fields.get("question");
        question.put("minLength", 1); question.put("maxLength", 4000);
        var options = (java.util.Map<String, Object>) fields.get("options");
        options.put("minItems", 1); options.put("maxItems", 10);
        var item = (java.util.Map<String, Object>) options.get("items");
        item.put("minLength", 1); item.put("maxLength", 1000);
    }

    /** Tighten the framework-generated schema; validation remains in native ToolValidator. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> closeObjectSchemas(java.util.Map<String, Object> source) {
        var schema = new java.util.LinkedHashMap<>(source);
        if (schema.containsKey("properties")) schema.put("additionalProperties", false);
        // Traverse schema nodes, never arbitrary examples/default values or dynamic-key payloads.
        for (String key : java.util.List.of("properties", "$defs", "definitions")) {
            if (schema.get(key) instanceof java.util.Map<?, ?> children) {
                var copy = new java.util.LinkedHashMap<String, Object>();
                children.forEach((name, child) -> copy.put(name.toString(), child instanceof java.util.Map<?, ?> node
                        ? closeObjectSchemas((java.util.Map<String, Object>) node) : child));
                schema.put(key, copy);
            }
        }
        for (String key : java.util.List.of("items", "additionalProperties")) {
            if (schema.get(key) instanceof java.util.Map<?, ?> node)
                schema.put(key, closeObjectSchemas((java.util.Map<String, Object>) node));
        }
        for (String key : java.util.List.of("allOf", "anyOf", "oneOf")) {
            if (schema.get(key) instanceof java.util.List<?> children)
                schema.put(key, children.stream().map(child -> child instanceof java.util.Map<?, ?> node
                        ? closeObjectSchemas((java.util.Map<String, Object>) node) : child).toList());
        }
        return schema;
    }
}
