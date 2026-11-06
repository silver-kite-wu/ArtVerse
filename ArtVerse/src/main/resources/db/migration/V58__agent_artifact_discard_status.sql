-- Model discard retires an unreviewed draft; author rejection keeps its separate meaning.
ALTER TABLE manga_agent_run_artifacts DROP CONSTRAINT ck_manga_agent_run_artifact_status;
ALTER TABLE manga_agent_run_artifacts ADD CONSTRAINT ck_manga_agent_run_artifact_status
    CHECK (status IN ('DRAFT', 'VALIDATED', 'REJECTED', 'COMMITTED', 'SUPERSEDED', 'DISCARDED'));
