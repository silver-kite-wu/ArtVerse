ALTER TABLE manga_agent_runs
  DROP CONSTRAINT IF EXISTS ck_manga_agent_runs_route;

ALTER TABLE manga_agent_runs
  ADD CONSTRAINT ck_manga_agent_runs_route
  CHECK (route IN ('AUTO', 'CHAT', 'DIRECTOR', 'HITL', 'REVIEW'));
