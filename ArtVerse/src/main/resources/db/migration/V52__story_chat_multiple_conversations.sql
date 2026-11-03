-- Story chat supports independent, resumable conversations in the same chapter.
-- Keep the single active conversation rule for the manga agent only.
DROP INDEX uk_ai_conversations_one_active_scoped;
CREATE UNIQUE INDEX uk_ai_conversations_one_active_scoped
    ON ai_conversations(user_id, chapter_id, conversation_type)
    WHERE status = 'ACTIVE' AND conversation_type = 'MANGA_AGENT';
