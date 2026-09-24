-- Versioned LLM prompts, edited through the admin port (127.0.0.1 only).
-- A change is a new row; old versions stay for rollback. One active version per name.
CREATE TABLE IF NOT EXISTS prompts (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,              -- e.g. 'morning_system'
    version     INT  NOT NULL,
    body        TEXT NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT FALSE,
    note        TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (name, version)
);
CREATE UNIQUE INDEX IF NOT EXISTS prompts_one_active ON prompts(name) WHERE is_active;

-- Which prompt version produced each message (0 = built-in default). For A/B and debugging.
ALTER TABLE morning_messages ADD COLUMN IF NOT EXISTS prompt_version INT NOT NULL DEFAULT 0;
