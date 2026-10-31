-- Where an install came from. The app reads it once at first launch (Install Referrer, deep
-- link) and sends it with /register; later cohort metrics are a plain GROUP BY on it.
-- First touch only: a returning device keeps what it was registered with.
-- source: slug of the channel (rustore, yandex_direct, telegram_ads, organic, ...); NULL = unknown.
-- campaign: campaign / link label within the channel.
ALTER TABLE users ADD COLUMN IF NOT EXISTS source   TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS campaign TEXT;
CREATE INDEX IF NOT EXISTS users_source ON users(source) WHERE source IS NOT NULL;
