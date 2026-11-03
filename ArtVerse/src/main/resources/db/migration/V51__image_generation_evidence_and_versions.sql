ALTER TABLE manga_images ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE image_gen_records
    ADD COLUMN request_id UUID,
    ADD COLUMN root_request_id UUID,
    ADD COLUMN request_hash VARCHAR(64),
    ADD COLUMN operation VARCHAR(32),
    ADD COLUMN execution_lane VARCHAR(24),
    ADD COLUMN profile_model_key VARCHAR(96),
    ADD COLUMN chapter_id BIGINT,
    ADD COLUMN image_number INTEGER,
    ADD COLUMN target_image_id BIGINT,
    ADD COLUMN target_base_version BIGINT,
    ADD COLUMN target_expected_absent BOOLEAN,
    ADD COLUMN manga_image_id BIGINT,
    ADD COLUMN result_image_version BIGINT,
    ADD COLUMN execution_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN provider_started_at TIMESTAMPTZ,
    ADD COLUMN attempt_decision VARCHAR(24),
    ADD COLUMN retry_not_before TIMESTAMPTZ,
    ADD COLUMN candidate_object_key VARCHAR(700),
    ADD COLUMN candidate_hash VARCHAR(64),
    ADD COLUMN candidate_content_type VARCHAR(100),
    ADD COLUMN candidate_size BIGINT,
    ADD COLUMN outcome VARCHAR(32),
    ADD COLUMN result_code VARCHAR(80),
    ADD COLUMN first_started_at TIMESTAMPTZ,
    ADD COLUMN operation_deadline TIMESTAMPTZ,
    ADD COLUMN recovery_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN review_requested_at TIMESTAMPTZ,
    ADD COLUMN recovery_decision VARCHAR(40),
    ADD COLUMN recovery_audit TEXT DEFAULT '[]',
    ADD COLUMN replacement_request_id UUID;
CREATE UNIQUE INDEX uq_image_record_operation ON image_gen_records(user_id, request_id);
CREATE INDEX ix_image_record_root ON image_gen_records(user_id, root_request_id, image_number);
CREATE INDEX ix_image_unresolved_lane ON image_gen_records(execution_lane) WHERE attempt_decision='UNRESOLVED';
CREATE INDEX ix_image_unresolved_user ON image_gen_records(user_id) WHERE attempt_decision='UNRESOLVED';
CREATE INDEX ix_image_unresolved_profile ON image_gen_records(profile_model_key) WHERE attempt_decision='UNRESOLVED';
CREATE INDEX ix_image_target_evidence ON image_gen_records(chapter_id,image_number,target_image_id,target_base_version) WHERE request_id IS NOT NULL;
