ALTER TABLE automation_run_action
    ADD COLUMN action_kind VARCHAR(16),
    ADD COLUMN payload_hash VARCHAR(64),
    ADD COLUMN target_binding JSONB,
    ADD COLUMN consumption_evidence JSONB;

-- Legacy actions remain identifiable, but are not promoted to verified actions.
ALTER TABLE automation_run_action ADD CONSTRAINT ck_automation_action_binding CHECK ((
    (action_kind IS NULL AND payload_hash IS NULL AND target_binding IS NULL AND consumption_evidence IS NULL)
    OR (action_kind IS NOT NULL AND action_kind IN ('RESPOND', 'RETRY') AND payload_hash IS NOT NULL
        AND length(payload_hash) = 64 AND target_binding IS NOT NULL
        AND jsonb_typeof(target_binding) = 'object'
        AND target_binding @> '{"schema_version":1}'::jsonb
        AND target_binding ?& ARRAY['actor', 'automation_run_id', 'definition_hash', 'response_hash', 'response_kind']
        AND ((consumption_evidence IS NULL AND consumed_at IS NULL AND consumed_fencing_token IS NULL)
            OR (consumption_evidence IS NOT NULL AND jsonb_typeof(consumption_evidence) = 'object'
                AND consumed_at IS NOT NULL AND consumed_fencing_token IS NOT NULL
                AND consumption_evidence ?& ARRAY['target', 'request_hash', 'kind']
                AND consumption_evidence->'target' = target_binding
                AND consumption_evidence->>'request_hash' = request_hash
                AND consumption_evidence->>'kind' IN ('NATIVE_RECORDED', 'REVIEW_SUMMARY_COMPLETED'))))
) IS TRUE);
