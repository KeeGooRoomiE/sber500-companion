-- Hour-by-hour unlocks and screen minutes (24 values, local hours) for the "why" signals:
-- busy work hours, a morning storm of notifications, late-night phone.
ALTER TABLE daily_data
    ADD COLUMN IF NOT EXISTS hourly_unlocks INT[],
    ADD COLUMN IF NOT EXISTS hourly_screen  INT[];
