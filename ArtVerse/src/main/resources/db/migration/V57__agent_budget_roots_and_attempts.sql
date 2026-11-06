ALTER TABLE agent_usage_ledger
    ADD COLUMN root_request_id UUID,
    ADD COLUMN automation_run_id UUID REFERENCES automation_run(id),
    ADD COLUMN attempt_id UUID,
    ADD COLUMN attempt_hash VARCHAR(64),
    ADD COLUMN reservation_status VARCHAR(16);

ALTER TABLE agent_usage_ledger DROP CONSTRAINT ck_agent_usage_kind;
ALTER TABLE agent_usage_ledger ADD CONSTRAINT ck_agent_usage_kind CHECK
    (usage_kind IN ('BUDGET_ROOT','MODEL_CALL','INPUT_TOKEN','INPUT_BYTE','OUTPUT_TOKEN',
                   'TOOL_CALL','SUBAGENT','OUTPUT_BYTE'));

-- Historical rows remain historical; do not manufacture roots or attempt evidence.
ALTER TABLE agent_usage_ledger ADD CONSTRAINT ck_agent_usage_reservation CHECK ((
    (usage_kind <> 'BUDGET_ROOT' AND root_request_id IS NULL AND attempt_id IS NULL AND attempt_hash IS NULL AND reservation_status IS NULL)
    OR (usage_kind = 'BUDGET_ROOT' AND root_request_id = request_id AND amount = 0
        AND attempt_id IS NULL AND attempt_hash IS NULL AND reservation_status = 'ROOT'
        AND jsonb_typeof(metadata) = 'object'
        AND (metadata->>'model_limit')::bigint > 0 AND (metadata->>'tool_limit')::bigint >= 0)
    OR (usage_kind <> 'BUDGET_ROOT' AND root_request_id = request_id AND attempt_id IS NOT NULL
        AND length(attempt_hash) = 64
        AND reservation_status IN ('RESERVED','FINISHED','FAILED','UNKNOWN'))
) IS TRUE);

CREATE UNIQUE INDEX uq_agent_budget_root ON agent_usage_ledger(user_id, root_request_id)
    WHERE usage_kind = 'BUDGET_ROOT';
CREATE UNIQUE INDEX uq_agent_usage_attempt ON agent_usage_ledger(user_id, root_request_id, usage_kind, attempt_id)
    WHERE attempt_id IS NOT NULL;
CREATE INDEX ix_agent_usage_parent ON agent_usage_ledger(user_id, automation_run_id, usage_kind)
    WHERE automation_run_id IS NOT NULL;
