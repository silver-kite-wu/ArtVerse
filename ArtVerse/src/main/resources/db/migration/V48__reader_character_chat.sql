CREATE TABLE reader_character_stage_snapshots (
    id BIGSERIAL PRIMARY KEY,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    character_profile_id BIGINT NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
    anchor_chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    anchor_chapter_number INT NOT NULL,
    persona TEXT NOT NULL DEFAULT '',
    growth_summary TEXT NOT NULL DEFAULT '',
    emotional_state TEXT NOT NULL DEFAULT '',
    known_facts TEXT NOT NULL DEFAULT '',
    source_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reader_character_snapshot UNIQUE (story_id, character_profile_id, anchor_chapter_id),
    CONSTRAINT ck_reader_character_snapshot_status CHECK (status IN ('BUILDING', 'READY', 'FAILED'))
);
CREATE INDEX idx_reader_character_snapshot_lookup
    ON reader_character_stage_snapshots(story_id, anchor_chapter_id, status);

CREATE TABLE reader_character_conversations (
    id BIGSERIAL PRIMARY KEY,
    conversation_uuid UUID NOT NULL UNIQUE,
    reader_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    character_profile_id BIGINT NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
    anchor_chapter_id BIGINT NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,
    snapshot_id BIGINT NOT NULL REFERENCES reader_character_stage_snapshots(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reader_character_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT uq_reader_character_conversation_scope UNIQUE (reader_id, story_id, character_profile_id, anchor_chapter_id)
);
CREATE INDEX idx_reader_character_conversations_reader
    ON reader_character_conversations(reader_id, updated_at DESC);

CREATE TABLE reader_character_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES reader_character_conversations(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    client_message_id VARCHAR(120),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    completion_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reader_character_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_reader_character_message_status CHECK (completion_status IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT uq_reader_character_message_request_role UNIQUE (conversation_id, request_id, role)
);
CREATE INDEX idx_reader_character_messages_order
    ON reader_character_messages(conversation_id, created_at ASC, id ASC);
CREATE UNIQUE INDEX uq_reader_character_messages_client_id
    ON reader_character_messages(conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
