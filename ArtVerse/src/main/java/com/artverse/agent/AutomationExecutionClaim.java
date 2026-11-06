package com.artverse.agent;

import java.util.Map;
import java.util.UUID;

/** Captured by the automation worker, carried unchanged through a single child invocation. */
public record AutomationExecutionClaim(UUID runId, String owner, long fencingToken) {
    public boolean valid() {
        return runId != null && owner != null && !owner.isBlank() && fencingToken > 0;
    }

    public Map<String, Object> variables() {
        if (!valid()) throw new IllegalArgumentException("Invalid automation execution claim");
        return Map.of("automation_run_id", runId.toString(), "automation_execution_owner", owner,
                "automation_fencing_token", fencingToken);
    }
}
