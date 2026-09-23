-- Users
CREATE TABLE IF NOT EXISTS users (
    id          TEXT PRIMARY KEY,           -- SHA256(device_id + salt)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Daily passive data snapshot
CREATE TABLE IF NOT EXISTS daily_data (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    date            DATE NOT NULL,
    sleep_min       INT,
    bedtime         TIME,
    wakeup          TIME,
    steps           INT,
    screen_min      INT,
    unlocks         INT,
    first_unlock    TIME,
    last_unlock     TIME,
    meetings        INT,
    meeting_min     INT,
    top_apps        JSONB,
    battery_morning INT,
    dnd_active      BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, date)
);

-- Evening check-ins
CREATE TABLE IF NOT EXISTS checkins (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id),
    date        DATE NOT NULL,
    day_feel    TEXT NOT NULL,             -- 'ok' | 'meh' | 'hard'
    tags        TEXT[],
    note_text   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, date)
);

-- Morning messages sent to users
CREATE TABLE IF NOT EXISTS morning_messages (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id),
    date        DATE NOT NULL,
    message     TEXT NOT NULL,
    sent_at     TIMESTAMPTZ,
    opened_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, date)
);

-- Component call log (антифрод)
CREATE TABLE IF NOT EXISTS call_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      TEXT NOT NULL,
    ts           TIMESTAMPTZ NOT NULL,
    session_id   TEXT,
    call_type    TEXT NOT NULL,            -- llm | tool | background
    component    TEXT NOT NULL,
    trigger      TEXT NOT NULL,            -- user_action | scheduled | followup
    user_visible BOOLEAN NOT NULL,
    result       TEXT NOT NULL,            -- ok | error
    error_code   TEXT,
    latency_ms   INT
);

CREATE INDEX IF NOT EXISTS call_log_user_ts ON call_log(user_id, ts);
CREATE INDEX IF NOT EXISTS call_log_ts ON call_log(ts);

-- Weekly feedback loop
CREATE TABLE IF NOT EXISTS weekly_feedback (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    week_start      DATE NOT NULL,
    recommendation  TEXT NOT NULL,
    feedback        TEXT,                  -- 'yes' | 'no' | 'partially'
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, week_start)
);
