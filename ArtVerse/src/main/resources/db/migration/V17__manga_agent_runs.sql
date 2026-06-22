CREATE TABLE manga_agent_runs (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_message TEXT NOT NULL,
  final_reply TEXT,
  error_message TEXT,
  user_input_request_json TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT ck_manga_agent_runs_status CHECK (status IN ('RUNNING', 'WAITING_USER', 'SUCCEEDED', 'DEGRADED', 'FAILED')),
  CONSTRAINT uk_manga_agent_runs_user_request UNIQUE (user_id, request_id)
);

CREATE INDEX idx_manga_agent_runs_chapter_status
  ON manga_agent_runs(user_id, chapter_id, status, updated_at DESC);

CREATE TABLE manga_agent_run_events (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES manga_agent_runs(id) ON DELETE CASCADE,
  event_name VARCHAR(64) NOT NULL,
  event_type VARCHAR(64),
  phase VARCHAR(64),
  label VARCHAR(255),
  status VARCHAR(64),
  payload_json TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_manga_agent_run_events_run_order
  ON manga_agent_run_events(run_id, id ASC);
