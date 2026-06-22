CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE manga_agent_conversations (
  id BIGSERIAL PRIMARY KEY,
  conversation_uuid UUID NOT NULL,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  archived_at TIMESTAMPTZ,
  CONSTRAINT uk_manga_agent_conversations_uuid UNIQUE (conversation_uuid),
  CONSTRAINT ck_manga_agent_conversations_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_manga_agent_conversations_chapter_status
  ON manga_agent_conversations(user_id, chapter_id, status, updated_at DESC);

INSERT INTO manga_agent_conversations (
  conversation_uuid,
  user_id,
  story_id,
  chapter_id,
  title,
  status,
  created_at,
  updated_at
)
SELECT
  gen_random_uuid(),
  source.user_id,
  source.story_id,
  source.chapter_id,
  '默认对话',
  'ACTIVE',
  source.created_at,
  source.updated_at
FROM (
  SELECT
    user_id,
    story_id,
    chapter_id,
    MIN(created_at) AS created_at,
    MAX(updated_at) AS updated_at
  FROM (
    SELECT
      user_id,
      story_id,
      chapter_id,
      created_at,
      created_at AS updated_at
    FROM manga_agent_messages

    UNION ALL

    SELECT
      user_id,
      story_id,
      chapter_id,
      created_at,
      updated_at
    FROM manga_agent_runs
  ) history
  GROUP BY user_id, story_id, chapter_id
) source
ON CONFLICT DO NOTHING;

ALTER TABLE manga_agent_messages
  ADD COLUMN conversation_id BIGINT REFERENCES manga_agent_conversations(id) ON DELETE CASCADE;

ALTER TABLE manga_agent_runs
  ADD COLUMN conversation_id BIGINT REFERENCES manga_agent_conversations(id) ON DELETE CASCADE;

UPDATE manga_agent_messages m
SET conversation_id = c.id
FROM manga_agent_conversations c
WHERE c.user_id = m.user_id
  AND c.chapter_id = m.chapter_id
  AND m.conversation_id IS NULL;

UPDATE manga_agent_runs r
SET conversation_id = c.id
FROM manga_agent_conversations c
WHERE c.user_id = r.user_id
  AND c.chapter_id = r.chapter_id
  AND r.conversation_id IS NULL;

ALTER TABLE manga_agent_messages
  ALTER COLUMN conversation_id SET NOT NULL;

ALTER TABLE manga_agent_runs
  ALTER COLUMN conversation_id SET NOT NULL;

CREATE INDEX idx_manga_agent_messages_conversation_order
  ON manga_agent_messages(conversation_id, created_at ASC);

CREATE INDEX idx_manga_agent_runs_conversation_status
  ON manga_agent_runs(conversation_id, status, updated_at DESC);
