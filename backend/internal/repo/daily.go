package repo

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type AppUsage struct {
	Package string `json:"package"`
	Minutes int    `json:"minutes"`
}

type DailyData struct {
	UserID         string
	Date           time.Time
	SleepMin       *int
	Bedtime        *string // "HH:MM"
	Wakeup         *string
	Steps          *int
	ScreenMin      *int
	Unlocks        *int
	FirstUnlock    *string
	LastUnlock     *string
	TopApps        []AppUsage
	BatteryMorning *int
	HourlyUnlocks  []int // 24 values or nil
	HourlyScreen   []int // minutes per hour, 24 values or nil
}

type DailyRepo struct{ db *pgxpool.Pool }

func NewDailyRepo(db *pgxpool.Pool) *DailyRepo { return &DailyRepo{db: db} }

func (r *DailyRepo) DB() *pgxpool.Pool { return r.db }

func (r *DailyRepo) Upsert(ctx context.Context, d *DailyData) error {
	topApps, _ := json.Marshal(d.TopApps)
	_, err := r.db.Exec(ctx, `
		INSERT INTO daily_data
		  (user_id, date, sleep_min, bedtime, wakeup, steps,
		   screen_min, unlocks, first_unlock, last_unlock, top_apps, battery_morning,
		   hourly_unlocks, hourly_screen)
		VALUES
		  ($1, $2, $3, $4::time, $5::time, $6,
		   $7, $8, $9::time, $10::time, $11, $12,
		   $13, $14)
		-- A later snapshot of the same day may lack a field (e.g. yesterday's has no
		-- morning battery): keep what we already have instead of overwriting with NULL.
		ON CONFLICT (user_id, date) DO UPDATE SET
		  sleep_min       = COALESCE(EXCLUDED.sleep_min, daily_data.sleep_min),
		  bedtime         = COALESCE(EXCLUDED.bedtime, daily_data.bedtime),
		  wakeup          = COALESCE(EXCLUDED.wakeup, daily_data.wakeup),
		  steps           = COALESCE(EXCLUDED.steps, daily_data.steps),
		  screen_min      = COALESCE(EXCLUDED.screen_min, daily_data.screen_min),
		  unlocks         = COALESCE(EXCLUDED.unlocks, daily_data.unlocks),
		  first_unlock    = COALESCE(EXCLUDED.first_unlock, daily_data.first_unlock),
		  last_unlock     = COALESCE(EXCLUDED.last_unlock, daily_data.last_unlock),
		  top_apps        = CASE WHEN jsonb_array_length(COALESCE(EXCLUDED.top_apps, '[]')) > 0
		                         THEN EXCLUDED.top_apps ELSE daily_data.top_apps END,
		  battery_morning = COALESCE(EXCLUDED.battery_morning, daily_data.battery_morning),
		  hourly_unlocks  = COALESCE(EXCLUDED.hourly_unlocks, daily_data.hourly_unlocks),
		  hourly_screen   = COALESCE(EXCLUDED.hourly_screen, daily_data.hourly_screen)
	`, d.UserID, d.Date, d.SleepMin, d.Bedtime, d.Wakeup, d.Steps,
		d.ScreenMin, d.Unlocks, d.FirstUnlock, d.LastUnlock, topApps, d.BatteryMorning,
		d.HourlyUnlocks, d.HourlyScreen)
	return err
}

// LastN returns up to n days of data for a user ending at `until` (inclusive), oldest first.
// Times come back as "HH:MM" — the prompt parses them with "15:04".
func (r *DailyRepo) LastN(ctx context.Context, userID string, n int, until time.Time) ([]*DailyData, error) {
	rows, err := r.db.Query(ctx, `
		SELECT user_id, date, sleep_min,
		       to_char(bedtime, 'HH24:MI'), to_char(wakeup, 'HH24:MI'),
		       steps, screen_min, unlocks,
		       to_char(first_unlock, 'HH24:MI'), to_char(last_unlock, 'HH24:MI'),
		       top_apps, battery_morning, hourly_unlocks, hourly_screen
		FROM daily_data
		WHERE user_id = $1 AND date <= $3
		ORDER BY date DESC
		LIMIT $2
	`, userID, n, until)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*DailyData
	for rows.Next() {
		d := &DailyData{}
		var topAppsRaw []byte
		if err := rows.Scan(
			&d.UserID, &d.Date, &d.SleepMin,
			&d.Bedtime, &d.Wakeup,
			&d.Steps, &d.ScreenMin, &d.Unlocks,
			&d.FirstUnlock, &d.LastUnlock,
			&topAppsRaw, &d.BatteryMorning, &d.HourlyUnlocks, &d.HourlyScreen,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(topAppsRaw, &d.TopApps)
		out = append(out, d)
	}
	// Query is newest-first for LIMIT; callers (the prompt) expect oldest-first.
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out, rows.Err()
}
