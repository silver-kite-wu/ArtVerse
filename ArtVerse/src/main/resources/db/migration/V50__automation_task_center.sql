CREATE TABLE automation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    task_type VARCHAR(16) NOT NULL CHECK (task_type IN ('AGENT','IMAGE')),
    executor_key VARCHAR(32) NOT NULL CHECK (executor_key IN ('MANGA_AGENT','STORY_CHAT','MANGA_IMAGE','IMAGE_GEN')),
    definition JSONB NOT NULL,
    timezone_id VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAUSED','COMPLETED','ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, idempotency_key)
);
CREATE INDEX ix_automation_due ON automation(next_run_at) WHERE status='ACTIVE' AND next_run_at IS NOT NULL;
CREATE INDEX ix_automation_user ON automation(user_id, created_at DESC);

CREATE TABLE automation_run (
    id UUID PRIMARY KEY,
    automation_id UUID NOT NULL REFERENCES automation(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    automation_version BIGINT NOT NULL,
    trigger_kind VARCHAR(16) NOT NULL CHECK (trigger_kind IN ('SCHEDULED','MANUAL')),
    scheduled_for TIMESTAMPTZ NOT NULL,
    invocation_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64),
    definition_snapshot JSONB NOT NULL,
    input_snapshot JSONB,
    snapshot_hash VARCHAR(64),
    state VARCHAR(24) NOT NULL CHECK (state IN ('QUEUED','RUNNING','RETRY_WAIT','WAITING_USER','CANCEL_REQUESTED','SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED','SKIPPED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancel_requested_at TIMESTAMPTZ,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    summary TEXT,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    waiting_reason VARCHAR(80),
    response JSONB,
    response_key VARCHAR(128),
    response_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    last_progress_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(automation_id, invocation_key)
);
CREATE UNIQUE INDEX uq_automation_run_schedule ON automation_run(automation_id,scheduled_for) WHERE trigger_kind='SCHEDULED';
CREATE UNIQUE INDEX uq_automation_run_blocking ON automation_run(automation_id) WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT','WAITING_USER','CANCEL_REQUESTED');
CREATE INDEX ix_automation_run_ready ON automation_run(next_attempt_at) WHERE state IN ('QUEUED','RETRY_WAIT','RUNNING','CANCEL_REQUESTED');
CREATE INDEX ix_automation_run_history ON automation_run(automation_id,created_at DESC,id);

CREATE TABLE automation_run_event (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES automation_run(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    safe_payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_automation_run_event_cursor ON automation_run_event(run_id,id);

ALTER TABLE scheduled_manga_generation ADD COLUMN automation_id UUID REFERENCES automation(id) ON DELETE SET NULL;
ALTER TABLE scheduled_manga_generation ADD COLUMN automation_run_id UUID REFERENCES automation_run(id) ON DELETE SET NULL;
CREATE INDEX ix_scheduled_manga_automation_run ON scheduled_manga_generation(automation_run_id);

ALTER TABLE image_gen_records ADD COLUMN automation_target_key VARCHAR(160);
ALTER TABLE image_gen_records ADD COLUMN automation_dispatch_state VARCHAR(24);
ALTER TABLE image_gen_records ADD COLUMN automation_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE image_gen_records ADD COLUMN automation_error_code VARCHAR(80);
CREATE UNIQUE INDEX uq_image_gen_automation_target ON image_gen_records(automation_target_key) WHERE automation_target_key IS NOT NULL;

CREATE TABLE automation_run_execution (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES automation_run(id) ON DELETE CASCADE,
    target_key VARCHAR(160) NOT NULL,
    execution_kind VARCHAR(32) NOT NULL,
    agent_run_id BIGINT REFERENCES manga_agent_runs(id) ON DELETE SET NULL,
    scheduled_manga_id UUID REFERENCES scheduled_manga_generation(id) ON DELETE SET NULL,
    image_record_id BIGINT REFERENCES image_gen_records(id) ON DELETE SET NULL,
    conversation_uuid UUID,
    request_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(run_id,target_key),
    CHECK ((execution_kind IN ('MANGA_AGENT','STORY_CHAT') AND scheduled_manga_id IS NULL AND image_record_id IS NULL)
        OR (execution_kind='MANGA_IMAGE' AND agent_run_id IS NULL AND image_record_id IS NULL)
        OR (execution_kind='IMAGE_GEN' AND agent_run_id IS NULL AND scheduled_manga_id IS NULL))
);

CREATE TABLE automation_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES automation_run(id) ON DELETE CASCADE,
    notification_kind VARCHAR(40) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(run_id,notification_kind)
);
CREATE INDEX ix_automation_notification_user ON automation_notification(user_id,created_at DESC);

CREATE TABLE automation_run_action (
    run_id UUID NOT NULL REFERENCES automation_run(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,idempotency_key)
);

-- One durable plan per legacy chapter task. Unstarted tasks remain unmaterialized,
-- so pause/archive can gate them before the old image worker dispatches anything.
INSERT INTO automation(id,user_id,name,task_type,executor_key,definition,timezone_id,status,next_run_at,idempotency_key,request_hash,created_at)
SELECT t.id,t.user_id,'章节预约生图 #'||t.chapter_id,'IMAGE','MANGA_IMAGE',
  jsonb_build_object('name','章节预约生图 #'||t.chapter_id,'taskType','IMAGE','executorKey','MANGA_IMAGE','instruction','生成章节缺失分格',
    'schedule',jsonb_build_object('kind','ONCE','localDateTime',to_char(t.requested_at AT TIME ZONE t.timezone_id,'YYYY-MM-DD"T"HH24:MI:SS'),'timezone',t.timezone_id,'weekdays','[]'::jsonb),
    'payload',jsonb_build_object('version',1,'storyId',c.story_id,'chapterIds',jsonb_build_array(t.chapter_id),'modelConfigId',t.model_config_id,'model',t.model_name,'inputPolicy','FIXED_SNAPSHOT','chapterSnapshots',jsonb_build_array(t.input_snapshot::jsonb)),
    'limits',jsonb_build_object('maxImagesPerRun',60,'maxModelCallsPerRun',10,'maxDurationSeconds',7200),
    'misfirePolicy','RUN_LATEST','overlapPolicy','SKIP','origin','CHAPTER'),t.timezone_id,
  CASE WHEN t.state IN ('SUCCEEDED','FAILED','PARTIAL_FAILED','CANCELLED') THEN 'COMPLETED' ELSE 'ACTIVE' END,
  CASE WHEN t.state='SCHEDULED' AND t.started_at IS NULL THEN t.requested_at END,
  'legacy-scheduled-manga:'||t.id,repeat('0',64),t.created_at
FROM scheduled_manga_generation t JOIN chapters c ON c.id=t.chapter_id
ON CONFLICT DO NOTHING;

INSERT INTO automation_run(id,automation_id,user_id,automation_version,trigger_kind,scheduled_for,invocation_key,
  definition_snapshot,input_snapshot,state,summary,waiting_reason,created_at,started_at,completed_at)
SELECT t.id,t.id,t.user_id,0,'SCHEDULED',t.requested_at,'legacy:'||t.id,a.definition,
  jsonb_build_object('chapters',jsonb_build_array(t.input_snapshot::jsonb),'imageCount',
    COALESCE(jsonb_array_length(COALESCE((t.input_snapshot::jsonb)->'missingImageNumbers',(t.input_snapshot::jsonb)->'missing_image_numbers')),0)),
  CASE WHEN EXISTS(SELECT 1 FROM scheduled_manga_panel p WHERE p.task_id=t.id AND p.state='UNCERTAIN') THEN 'WAITING_USER'
    WHEN t.state='STALE_REQUIRES_CONFIRMATION' THEN 'WAITING_USER'
    WHEN t.state='SCHEDULED' THEN 'QUEUED' ELSE t.state END,
  COALESCE(t.error_message,'已从原预约任务导入'),
  CASE WHEN EXISTS(SELECT 1 FROM scheduled_manga_panel p WHERE p.task_id=t.id AND p.state='UNCERTAIN') THEN 'RESULT_UNCERTAIN'
    WHEN t.state='STALE_REQUIRES_CONFIRMATION' THEN 'INPUT_CHANGED' END,
  t.created_at,t.started_at,t.completed_at
FROM scheduled_manga_generation t JOIN automation a ON a.id=t.id
WHERE NOT(t.state='SCHEDULED' AND t.started_at IS NULL)
ON CONFLICT DO NOTHING;

UPDATE scheduled_manga_generation t SET automation_id=t.id,
  automation_run_id=(SELECT r.id FROM automation_run r WHERE r.id=t.id)
WHERE EXISTS(SELECT 1 FROM automation a WHERE a.id=t.id);

INSERT INTO automation_run_execution(run_id,target_key,execution_kind,scheduled_manga_id)
SELECT t.automation_run_id,'chapter:'||t.chapter_id,'MANGA_IMAGE',t.id FROM scheduled_manga_generation t
WHERE t.automation_run_id IS NOT NULL ON CONFLICT DO NOTHING;

UPDATE automation SET status='ACTIVE' WHERE id IN(SELECT automation_id FROM automation_run WHERE state='WAITING_USER');
