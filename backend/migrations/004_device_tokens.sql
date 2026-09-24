-- Server-issued device credentials. The app gets a random token at registration;
-- only its SHA-256 is stored, so a DB leak doesn't hand out working tokens.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_hash TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS users_token_hash ON users(token_hash) WHERE token_hash IS NOT NULL;
