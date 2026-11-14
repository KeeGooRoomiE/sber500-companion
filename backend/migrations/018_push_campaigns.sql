-- Automatic notifications the server sent on its own (reactivation after silence, the weekly
-- summary for people who stopped opening the app). One row per person per send.
--
-- It exists so a campaign can never repeat itself: «once per silence streak» and «once a week»
-- are answered from here, and a restart in the middle of a batch does not send twice.
-- call_log keeps the anti-fraud record of the same sends; this table is the scheduling memory.
CREATE TABLE IF NOT EXISTS push_campaign_sends (
    user_id  TEXT        NOT NULL REFERENCES users(id),
    campaign TEXT        NOT NULL,
    sent_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS push_campaign_sends_user ON push_campaign_sends(user_id, campaign, sent_at);
