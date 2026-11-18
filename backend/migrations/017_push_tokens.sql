-- Where a server-side notification can be sent. FCM is gone; the channel is RuStore Push
-- (https://vkpns.rustore.ru), addressed by the token the app's push client hands out.
--
-- One device per user (user id = hash of the device), so the token lives on the user row.
-- The token is a delivery address, not a credential: it cannot read anything, only receive a
-- message sent with our own service token.
-- NULL means «this phone never registered one» — builds before 1.1.0, or push unavailable.
ALTER TABLE users ADD COLUMN IF NOT EXISTS push_token         TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS push_provider      TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS push_token_seen_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS users_push_token ON users(push_token) WHERE push_token IS NOT NULL;
