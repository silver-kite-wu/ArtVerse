package com.artverse.application.tools;

import com.artverse.application.AgentToolAuditService;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.application.automation.AutomationDefinition;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AutomationManageAgentTools {
    private final AutomationAgentTools commands;
    private final MangaToolSupport support;
    private final ObjectMapper mapper;
    private final AgentToolAuditService audit;
    public enum Executor { STORY_CHAT, MANGA_AGENT, MANGA_IMAGE }
    public enum Action { edit, pause, resume, archive }
    public record Payload(Long chapter_id, List<Long> chapter_ids, Long model_config_id, String model, String input_policy) {}
    public record Schedule(String kind, String local_date_time, String local_time, String timezone, List<Integer> weekdays) {}
    public record Limits(Integer max_images_per_run, Integer max_model_calls_per_run, Integer max_duration_seconds) {}
    public record Definition(String name, Executor executor, String instruction, Schedule schedule, Payload payload, Limits limits) {}

    @Tool(name = "create_automation", description = "Prepare an exact plan proposal for author review. AWAITING_REVIEW has no scheduled effect; only a COMMITTED receipt proves the plan was saved. Missing time, scope or paid budget must be clarified.", concurrencySafe = false)
    public Object create(@ToolParam(name = "definition", description = "Typed plan with schedule, target chapters, saved model configuration and limits") Definition definition,
                         RuntimeContext runtime) {
        var ctx = support.resolveContext(runtime);
        return audit.around("create_automation", ctx.userId(), ctx.chapterId(), runtime, () -> {
            var result = commands.create(ctx, Map.of("definition", convert(definition, null, runtime)),
                    com.artverse.agent.AgentToolAttempt.require(runtime, "create_automation"));
            return Map.of("command", result);
        });
    }

    @Tool(name = "update_automation", description = "Prepare one exact plan change with expected_version for author review. AWAITING_REVIEW never edits the plan. Repeating the same logical command returns its existing proposal or decision receipt.", concurrencySafe = false)
    public Object update(@ToolParam(name = "automation_id", description = "Plan UUID") UUID id,
                         @ToolParam(name = "action", description = "edit, pause, resume or archive") Action action,
                         @ToolParam(name = "expected_version", description = "Version read from the persisted plan") Long version,
                         @ToolParam(name = "definition", required = false, description = "Complete replacement definition, required only for edit") Definition definition,
                         RuntimeContext runtime) {
        if (id == null || action == null || version == null || version < 0) throw new BusinessException(400, "Plan ID, action and version are required");
        if (action != Action.edit && definition != null) throw new BusinessException(400,"Only edit accepts a replacement definition");
        var ctx = support.resolveContext(runtime);
        return audit.around("update_automation", ctx.userId(), ctx.chapterId(), runtime, () -> {
            Map<String,Object> args = new LinkedHashMap<>();
            args.put("automationId", id.toString()); args.put("action", action.name()); args.put("expectedVersion", version);
            if (action == Action.edit) args.put("definition", convert(definition, version, runtime));
            return Map.of("command", commands.update(ctx, args,
                    com.artverse.agent.AgentToolAttempt.require(runtime, "update_automation")));
        });
    }

    private AutomationDefinition convert(Definition definition, Long version, RuntimeContext runtime) {
        return toDefinition(definition,version,support.resolveContext(runtime),mapper);
    }

    public static AutomationDefinition toDefinition(Definition definition, Long version,
            com.artverse.agent.MangaAgentRuntimeContext ctx,ObjectMapper mapper) {
        if (definition == null || definition.executor() == null || definition.schedule() == null || definition.payload() == null) throw new BusinessException(400, "Complete automation definition is required");
        var raw = definition.payload();
        var payload = mapper.createObjectNode().put("storyId", ctx.storyId());
        if (raw.chapter_id() != null) payload.put("chapterId", raw.chapter_id());
        if (raw.chapter_ids() != null) payload.set("chapterIds", mapper.valueToTree(raw.chapter_ids()));
        if (raw.model_config_id() != null) payload.put("modelConfigId", raw.model_config_id());
        if (raw.model() != null) payload.put("model", raw.model());
        if (raw.input_policy() != null) payload.put("inputPolicy", raw.input_policy());
        var schedule = definition.schedule();
        var limits = definition.limits();
        return new AutomationDefinition(definition.name(), definition.executor() == Executor.MANGA_IMAGE ? "IMAGE" : "AGENT",
                definition.executor().name(), definition.instruction(),
                new AutomationDefinition.Schedule(schedule.kind(), schedule.local_date_time(), schedule.local_time(), schedule.timezone(), schedule.weekdays()),
                payload, limits == null ? null : new AutomationDefinition.Limits(limits.max_images_per_run(), limits.max_model_calls_per_run(), limits.max_duration_seconds()),
                null, null, null, version, "CONVERSATION", ctx.conversationId());
    }
}
