ALTER TABLE users ADD COLUMN ai_permission_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';
ALTER TABLE users ADD CONSTRAINT ck_users_ai_permission_mode
    CHECK (ai_permission_mode IN ('MANUAL', 'AUTO', 'FULL'));
