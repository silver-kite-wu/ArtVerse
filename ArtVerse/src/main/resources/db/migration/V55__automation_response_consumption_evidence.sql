ALTER TABLE automation_run_action
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD COLUMN consumed_fencing_token BIGINT;

ALTER TABLE automation_run_action
    ADD CONSTRAINT ck_automation_response_consumption
    CHECK ((consumed_at IS NULL AND consumed_fencing_token IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_fencing_token IS NOT NULL));
