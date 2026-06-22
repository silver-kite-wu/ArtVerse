ALTER TABLE manga_agent_runs
  DROP CONSTRAINT IF EXISTS ck_manga_agent_runs_status;

ALTER TABLE manga_agent_runs
  ADD CONSTRAINT ck_manga_agent_runs_status
  CHECK (status IN ('RUNNING', 'WAITING_USER', 'SUCCEEDED', 'DEGRADED', 'FAILED', 'CANCELLED', 'INTERRUPTED'));
