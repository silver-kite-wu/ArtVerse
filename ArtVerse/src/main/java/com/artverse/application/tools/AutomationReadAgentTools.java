package com.artverse.application.tools;

import com.artverse.application.automation.AutomationAgentTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AutomationReadAgentTools {
    private final AutomationAgentTools commands;
    private final MangaToolSupport support;
    private com.artverse.application.automation.AutomationReadService reads;
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public void setReadService(com.artverse.application.automation.AutomationReadService reads,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.reads=java.util.Objects.requireNonNull(reads);this.mapper=java.util.Objects.requireNonNull(mapper);
    }

    public enum Status { ACTIVE, PAUSED, COMPLETED, ARCHIVED }
    public enum TaskType { AGENT, IMAGE }

    @Tool(name="preview_automation",description="Preview the exact schedule, target chapters, saved model configuration and finite execution limits. The short-lived preview reference is read-only: it grants no author approval, creates no plan/proposal/run and reserves no quota.",readOnly=true,concurrencySafe=true)
    public com.artverse.application.automation.AutomationReadViews.Preview preview(
            @ToolParam(name="definition",description="Typed plan definition with a complete schedule, current-story chapter targets, saved model configuration and limits") AutomationManageAgentTools.Definition definition,
            RuntimeContext context) {
        var execution=support.resolveContext(context);
        return reads.preview(execution,AutomationManageAgentTools.toDefinition(definition,null,execution,mapper));
    }

    @Tool(name="get_automation",description="Read one persisted plan in the current story, including its version and sanitized model display information. Reading a plan does not authorize a change.",readOnly=true,concurrencySafe=true)
    public com.artverse.application.automation.AutomationReadViews.Plan get(
            @ToolParam(name="automation_id",description="Persisted automation ID returned by list_automations") java.util.UUID id,RuntimeContext context) {
        return reads.get(support.resolveContext(context),id);
    }

    @Tool(name="list_automation_runs",description="Read one current-story plan's execution history. Follow next_cursor with the same plan; a changed snapshot requires restarting from the first page. No internal leases, raw provider errors or credentials are returned.",readOnly=true,concurrencySafe=true)
    public com.artverse.application.automation.AutomationReadViews.RunPage runs(
            @ToolParam(name="automation_id",description="Persisted automation ID in the current story") java.util.UUID id,
            @ToolParam(name="cursor",required=false,description="Opaque next_cursor from the preceding page") String cursor,
            @ToolParam(name="limit",required=false,description="Page size from 1 to 50; default 20") Integer limit,RuntimeContext context) {
        return reads.runs(support.resolveContext(context),id,cursor,limit);
    }

    @Tool(name="get_automation_run",description="Read a current-story run's persisted status, review artifact references and per-image results. It reports truncation explicitly and never executes, retries, confirms or expands the run.",readOnly=true,concurrencySafe=true)
    public com.artverse.application.automation.AutomationReadViews.RunDetail run(
            @ToolParam(name="run_id",description="Run ID returned by list_automation_runs") java.util.UUID id,RuntimeContext context) {
        return reads.runDetail(support.resolveContext(context),id);
    }

    @Tool(name = "list_automations", description = "List this story's persisted plans. Follow next_cursor while has_more is true; reuse the same filters. If the snapshot changes, restart without a cursor.", readOnly = true)
    public com.artverse.application.automation.AutomationStore.PlanPage list(
            @ToolParam(name = "status", required = false, description = "Optional persisted plan status") Status status,
            @ToolParam(name = "task_type", required = false, description = "Optional AGENT or IMAGE task filter") TaskType taskType,
            @ToolParam(name = "cursor", required = false, description = "Opaque next_cursor returned by the preceding page") String cursor,
            @ToolParam(name = "limit", required = false, description = "Page size from 1 to 50; defaults to 20") Integer limit,
            RuntimeContext context) {
        return commands.list(support.resolveContext(context), status == null ? null : status.name(),
                taskType == null ? null : taskType.name(), cursor, limit);
    }
}
