-- Users created from a debug build — emulator runs and local testing.
--
-- They are indistinguishable from real drop-offs after the fact: a test install that registers
-- and uploads nothing looks exactly like a person who abandoned onboarding, which the funnel is
-- supposed to count. So the build says so at registration instead of being guessed at later.
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_dev BOOLEAN NOT NULL DEFAULT false;
