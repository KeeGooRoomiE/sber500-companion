-- Conversation history stays on the device. Receipts cache the reply for retries;
-- only an explicit memory request writes a reusable personal fact.
CREATE TABLE IF NOT EXISTS chat_requests (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    fingerprint BYTEA NOT NULL,
    source TEXT NOT NULL,
    daily_question_id TEXT NOT NULL DEFAULT '',
    claimed_at TIMESTAMPTZ,
    attempt INTEGER NOT NULL DEFAULT 1,
    response JSONB,
    memory_cursor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, request_id)
);
CREATE INDEX IF NOT EXISTS chat_requests_recent ON chat_requests(user_id, finished_at DESC) WHERE response IS NOT NULL;

CREATE TABLE IF NOT EXISTS chat_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fact_key TEXT NOT NULL,
    fact TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, fact_key)
);

-- Accounting per provider attempt, including fallbacks and failures.
CREATE TABLE IF NOT EXISTS chat_model_usage (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS chat_usage_created ON chat_model_usage(created_at);
