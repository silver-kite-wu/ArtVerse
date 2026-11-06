package com.artverse.agent.gateway.tools;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-created call scope, never a model argument or skill permission. */
public record AgentToolCapability(Set<String> toolNames, boolean summary, boolean automationManagement,
        ExecutionScope execution, Set<String> groups, Map<String, String> contractDigests) {
    public AgentToolCapability {
        toolNames = Set.copyOf(toolNames);
        groups = Set.copyOf(groups);
        contractDigests = Map.copyOf(contractDigests);
        if (!contractDigests.keySet().equals(toolNames)) throw new IllegalArgumentException("Incomplete tool contracts");
    }
    public enum Source { INTERACTIVE, AUTOMATION, EXPERT, REVIEW_SUMMARY }
    /** Facts captured from the original persisted run; possession never renews its lease. */
    public record ExecutionScope(UUID tenantId, Long actorId, Long storyId, Long writeChapterId,
            String readScopePolicy, UUID rootRequestId, Long runId, UUID conversationId, String sessionId,
            String stepId, String taskType, Source source, String profile, String workflowVersion,
            String owner, long fencingToken, com.artverse.agent.AutomationExecutionClaim automation,
            com.artverse.agent.AgentReviewExpert expert, String continuationId, String decisionReceiptHash,
            String executionBindingHash, String modelBindingHash) { }

    public AgentToolCapability withExecution(ExecutionScope scope) {
        if (scope == null) throw new IllegalArgumentException("Missing persisted execution scope");
        boolean management = automationManagement && scope.source() == Source.INTERACTIVE;
        return new AgentToolCapability(toolNames, summary, management, scope, groups, contractDigests);
    }
    public static boolean requestsAutomationRead(AgentRunRequest request) {
        if (Boolean.TRUE.equals(request.variables().get("review_completed"))) return false;
        if (!Set.of(AgentTaskType.MANGA_CONVERSATION, AgentTaskType.STORY_CHAT_READ, AgentTaskType.STORY_CHAT_WRITE)
                .contains(request.taskType())) return false;
        return requestsAutomationManagement(request)
                || latestUserText(request).matches("(?is).*(?:定时任务|计划任务|任务计划|预约|自动任务|automations?|schedules?|scheduled (?:tasks?|plans?)).*");
    }
    private static final Pattern MANAGEMENT = Pattern.compile(
            "(?is).*(?:请|帮我|替我|我要|给我|please|can you|i want).*(?:创建|安排|预约|定时|每天|每周|暂停|恢复|修改|取消|归档|schedule|create|pause|resume|update|archive).*(?:任务|计划|生成|执行|提醒|automation|schedule|plan|generate).*"
                    + "|(?is).*(?:请|帮我|替我|please|can you).*(?:任务|计划|automation|plan).*(?:暂停|恢复|修改|取消|归档|pause|resume|update|archive).*"
    );
    public static boolean requestsAutomationManagement(AgentRunRequest request) {
        if (Boolean.TRUE.equals(request.variables().get("review_completed"))) return false;
        if (!Set.of(AgentTaskType.MANGA_CONVERSATION, AgentTaskType.STORY_CHAT_READ, AgentTaskType.STORY_CHAT_WRITE)
                .contains(request.taskType())) return false;
        String text = latestUserText(request);
        if (text.matches("(?is).*(?:不要|别|不需要|能否|如何|怎么|支持吗|是否支持|do not|don't|how to|is it possible).*")) return false;
        return MANAGEMENT.matcher(text).matches();
    }
    private static String latestUserText(AgentRunRequest request) {
        return request.messages().stream().filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b).map(m -> m.content() == null ? "" : m.content().trim()).orElse("");
    }
}
