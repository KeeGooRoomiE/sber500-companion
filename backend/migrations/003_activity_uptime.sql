-- One row per user per day they actually used the app (app opened, check-in, forecast read).
-- Background uploads don't count. Source of DAU and retention for /api/v1/metrics.
CREATE TABLE IF NOT EXISTS user_activity (
    user_id  TEXT NOT NULL REFERENCES users(id),
    date     DATE NOT NULL,
    PRIMARY KEY (user_id, date)
);
CREATE INDEX IF NOT EXISTS user_activity_date ON user_activity(date);

-- The server writes one row per minute while it's up; uptime_24h = rows / 1440.
CREATE TABLE IF NOT EXISTS heartbeats (
    minute TIMESTAMPTZ PRIMARY KEY
);

CREATE INDEX IF NOT EXISTS morning_messages_date ON morning_messages(date);
CREATE INDEX IF NOT EXISTS checkins_date ON checkins(date);
