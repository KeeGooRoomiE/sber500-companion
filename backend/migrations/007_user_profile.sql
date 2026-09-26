-- Answers from «Расскажи о себе» that shape the forecast (never the name — that stays on the phone).
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile JSONB NOT NULL DEFAULT '{}'::jsonb;
