ALTER TABLE reader_character_conversations
    ADD COLUMN conversation_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE',
    ADD COLUMN title VARCHAR(255),
    ADD COLUMN last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN is_friend BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN knowledge_anchor_chapter_number INT;

ALTER TABLE reader_character_conversations
    DROP CONSTRAINT uq_reader_character_conversation_scope,
    DROP CONSTRAINT IF EXISTS fk_reader_character_conversation_snapshot;

ALTER TABLE reader_character_conversations
    ALTER COLUMN character_profile_id DROP NOT NULL,
    ALTER COLUMN snapshot_id DROP NOT NULL;

ALTER TABLE reader_character_conversations
    ADD CONSTRAINT ck_reader_character_conversation_type
        CHECK (conversation_type IN ('SINGLE', 'GROUP'));

CREATE UNIQUE INDEX uq_reader_single_character_conversation
    ON reader_character_conversations(reader_id, story_id, character_profile_id)
    WHERE conversation_type = 'SINGLE' AND character_profile_id IS NOT NULL;

CREATE INDEX idx_reader_character_conversations_last_message
    ON reader_character_conversations(reader_id, story_id, last_message_at DESC);

CREATE TABLE reader_character_conversation_members (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES reader_character_conversations(id) ON DELETE CASCADE,
    character_profile_id BIGINT NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    CONSTRAINT uq_reader_character_conversation_member UNIQUE (conversation_id, character_profile_id)
);

CREATE INDEX idx_reader_character_conversation_members_lookup
    ON reader_character_conversation_members(conversation_id, joined_at ASC);

CREATE TABLE reader_character_friends (
    id BIGSERIAL PRIMARY KEY,
    reader_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    character_profile_id BIGINT NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
    conversation_id BIGINT NOT NULL REFERENCES reader_character_conversations(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reader_character_friend UNIQUE (reader_id, story_id, character_profile_id),
    CONSTRAINT uq_reader_character_friend_conversation UNIQUE (conversation_id)
);

CREATE INDEX idx_reader_character_friends_reader_story
    ON reader_character_friends(reader_id, story_id, created_at ASC);

CREATE TABLE reader_story_progress (
    id BIGSERIAL PRIMARY KEY,
    reader_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    max_chapter_number INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reader_story_progress UNIQUE (reader_id, story_id)
);

ALTER TABLE character_profiles
    ADD COLUMN terminal_chapter_number INT,
    ADD COLUMN terminal_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE reader_character_messages
    ADD COLUMN sender_character_profile_id BIGINT REFERENCES character_profiles(id) ON DELETE SET NULL,
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_reader_character_messages_visible_order
    ON reader_character_messages(conversation_id, deleted_at, created_at ASC, id ASC);
