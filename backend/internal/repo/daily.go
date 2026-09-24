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
}

type DailyRepo struct{ db *pgxpool.Pool }

func NewDailyRepo(db *pgxpool.Pool) *DailyRepo { return &DailyRepo{db: db} }

func (r *DailyRepo) Upsert(ctx context.Context, d *DailyData) error {
	topApps, _ := json.Marshal(d.TopApps)
	_, err := r.db.Exec(ctx, `
		INSERT INTO daily_data
		  (user_id, date, sleep_min, bedtime, wakeup, steps,
		   screen_min, unlocks, first_unlock, last_unlock, top_apps, battery_morning)
		VALUES
		  ($1, $2, $3, $4::time, $5::time, $6,
		   $7, $8, $9::time, $10::time, $11, $12)
		ON CONFLICT (user_id, date) DO UPDATE SET
		  sleep_min       = EXCLUDED.sleep_min,
		  bedtime         = EXCLUDED.bedtime,
		  wakeup          = EXCLUDED.wakeup,
		  steps           = EXCLUDED.steps,
		  screen_min      = EXCLUDED.screen_min,
		  unlocks         = EXCLUDED.unlocks,
		  first_unlock    = EXCLUDED.first_unlock,
		  last_unlock     = EXCLUDED.last_unlock,
		  top_apps        = EXCLUDED.top_apps,
		  battery_morning = EXCLUDED.battery_morning
	`, d.UserID, d.Date, d.SleepMin, d.Bedtime, d.Wakeup, d.Steps,
		d.ScreenMin, d.Unlocks, d.FirstUnlock, d.LastUnlock, topApps, d.BatteryMorning)
	return err
}

// LastN returns the last n days of data for a user, newest first.
func (r *DailyRepo) LastN(ctx context.Context, userID string, n int) ([]*DailyData, error) {
	rows, err := r.db.Query(ctx, `
		SELECT user_id, date, sleep_min,
		       bedtime::text, wakeup::text,
		       steps, screen_min, unlocks,
		       first_unlock::text, last_unlock::text,
		       top_apps, battery_morning
		FROM daily_data
		WHERE user_id = $1
		ORDER BY date DESC
		LIMIT $2
	`, userID, n)
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
			&topAppsRaw, &d.BatteryMorning,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(topAppsRaw, &d.TopApps)
		out = append(out, d)
	}
	return out, rows.Err()
}
