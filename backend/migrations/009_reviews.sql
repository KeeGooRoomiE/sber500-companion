-- On-demand LLM texts the person asks for in the app: a day review and a weekly summary.
-- One row per (user, kind, date); a review of today may be refreshed (at most hourly).
CREATE TABLE IF NOT EXISTS reviews (
    id                BIGSERIAL PRIMARY KEY,
    user_id           TEXT NOT NULL REFERENCES users(id),
    kind              TEXT NOT NULL,          -- 'day' | 'week'
    date              DATE NOT NULL,          -- reviewed day; for 'week' — the day it was made
    text              TEXT NOT NULL,
    prompt_tokens     INT  NOT NULL DEFAULT 0,
    completion_tokens INT  NOT NULL DEFAULT 0,
    latency_ms        INT  NOT NULL DEFAULT 0,
    model             TEXT NOT NULL DEFAULT '',
    prompt_version    INT  NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, kind, date)
);
CREATE INDEX IF NOT EXISTS reviews_created ON reviews(created_at);
