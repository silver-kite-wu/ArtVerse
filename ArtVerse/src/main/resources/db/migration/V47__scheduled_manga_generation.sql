CREATE TABLE scheduled_manga_generation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL,
    timezone_id VARCHAR(80) NOT NULL,
    state VARCHAR(40) NOT NULL,
    chapter_version BIGINT NOT NULL,
    model_config_id BIGINT,
    model_name VARCHAR(255) NOT NULL,
    scenes_snapshot TEXT NOT NULL,
    input_snapshot TEXT NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_scheduled_manga_user_idempotency UNIQUE(user_id, idempotency_key),
    CONSTRAINT ck_scheduled_manga_state CHECK (state IN ('SCHEDULED','RUNNING','SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED','STALE_REQUIRES_CONFIRMATION'))
);
CREATE INDEX ix_scheduled_manga_due ON scheduled_manga_generation(state, requested_at);
CREATE INDEX ix_scheduled_manga_user_created ON scheduled_manga_generation(user_id, created_at DESC);
CREATE UNIQUE INDEX uq_scheduled_manga_active_chapter ON scheduled_manga_generation(chapter_id)
    WHERE state IN ('SCHEDULED','RUNNING');

CREATE TABLE scheduled_manga_panel (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES scheduled_manga_generation(id) ON DELETE CASCADE,
    image_number INTEGER NOT NULL,
    prompt_snapshot TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    result_image_id BIGINT REFERENCES manga_images(id) ON DELETE SET NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    lease_owner VARCHAR(120),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_scheduled_manga_panel UNIQUE(task_id, image_number),
    CONSTRAINT ck_scheduled_manga_panel_state CHECK (state IN ('PENDING','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED','UNCERTAIN'))
);
CREATE INDEX ix_scheduled_manga_panel_due ON scheduled_manga_panel(state, next_attempt_at);

-- A small shared lease pool makes the concurrency ceiling apply across all application replicas.
CREATE TABLE scheduled_manga_worker_slot (
    slot_id INTEGER PRIMARY KEY,
    owner VARCHAR(160),
    lease_until TIMESTAMPTZ
);
INSERT INTO scheduled_manga_worker_slot(slot_id) VALUES (1), (2);

CREATE TABLE scheduled_manga_scoped_permit (
    permit_key VARCHAR(240) PRIMARY KEY,
    owner VARCHAR(160),
    lease_until TIMESTAMPTZ
);
