-- Where the person actually lives in time. 70% of the users whose zone could be worked out
-- sit at MSK+4, so «the morning batch runs at 04:10 Moscow» means their phone asks for a
-- forecast before the server has written one: 26% of forecasts are generated on demand, and
-- 70% of those are someone opening the app ahead of the batch.
--
-- The app sends an IANA name («Asia/Yekaterinburg»); the offset is kept beside it because
-- scheduling needs arithmetic, not a name, and because a name the server's tzdata does not
-- know must not silently become UTC.
-- NULL means unknown — every reader has to keep working for people on builds that never send it.
ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone       TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tz_offset_min  INTEGER;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tz_seen_at     TIMESTAMPTZ;
