-- Reinstall = same person: the app sends a hash of its per-device id (ANDROID_ID, which survives
-- a reinstall as long as the APK is signed with the same key). Stored hashed once more, like tokens.
ALTER TABLE users ADD COLUMN IF NOT EXISTS device_hash TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS users_device_hash ON users(device_hash) WHERE device_hash IS NOT NULL;

-- Steps per local hour (Health Connect): walks, a still morning, movement vs the person's norm.
ALTER TABLE daily_data ADD COLUMN IF NOT EXISTS hourly_steps INT[];
