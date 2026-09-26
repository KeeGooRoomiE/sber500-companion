-- A failed LLM call leaves an empty placeholder row. Instead of blocking the whole day,
-- it may be retried: up to 3 attempts, at least 30 minutes apart (see forecast.Generator).
ALTER TABLE morning_messages
    ADD COLUMN IF NOT EXISTS attempts        INT         NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
