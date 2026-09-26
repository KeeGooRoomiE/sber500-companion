-- «Совпало / Не совсем» under the morning forecast and under day / week reviews.
-- prompt_version is copied from the rated text, so accuracy can be compared per prompt version.
CREATE TABLE IF NOT EXISTS feedback (
    user_id        TEXT        NOT NULL REFERENCES users(id),
    kind           TEXT        NOT NULL CHECK (kind IN ('morning', 'day', 'week')),
    date           DATE        NOT NULL,
    verdict        TEXT        NOT NULL CHECK (verdict IN ('hit', 'miss')),
    prompt_version INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, kind, date)
);
CREATE INDEX IF NOT EXISTS feedback_created ON feedback(created_at);
