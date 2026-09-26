-- «Хочу ещё»: answers to follow-up questions about the person's own data.
-- One answer per (user, day, question); counts toward LLM_DAILY_CAP and the per-user daily limit.
CREATE TABLE IF NOT EXISTS explore_answers (
    user_id           TEXT        NOT NULL REFERENCES users(id),
    date              DATE        NOT NULL,
    question_id       TEXT        NOT NULL,
    question          TEXT        NOT NULL,
    text              TEXT        NOT NULL,
    facts             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    latency_ms        INT         NOT NULL DEFAULT 0,
    model             TEXT        NOT NULL DEFAULT '',
    prompt_version    INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, date, question_id)
);
CREATE INDEX IF NOT EXISTS explore_answers_created ON explore_answers(created_at);
