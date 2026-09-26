// Package mock holds test people for checking prompts on the server before real users see them.
//
// Each persona is a week of phone data shaped around one situation from the interviews
// (morning calls, night phone without a watch, a calm day, work in Telegram, day 0…).
// Dates are relative: the last day is always yesterday, so a seed on any day gives a fresh
// "this morning" for the prompt. Ids start with "mock_" — metrics, the scheduler and the
// accuracy report skip them.
package mock

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Prefix marks mock users everywhere they must be excluded.
const Prefix = "mock_"

//go:embed personas.json
var personasJSON []byte

type Persona struct {
	ID      string            `json:"id"`
	Title   string            `json:"title"`
	Profile map[string]string `json:"profile"`
	Days    []Day             `json:"days"`
}

type Day struct {
	Offset        int             `json:"offset"` // -1 = yesterday
	SleepMin      *int            `json:"sleep_min"`
	Bedtime       *string         `json:"bedtime"`
	Wakeup        *string         `json:"wakeup"`
	Steps         *int            `json:"steps"`
	ScreenMin     *int            `json:"screen_min"`
	Unlocks       *int            `json:"unlocks"`
	FirstUnlock   *string         `json:"first_unlock"`
	LastUnlock    *string         `json:"last_unlock"`
	TopApps       []repo.AppUsage `json:"top_apps"`
	HourlyUnlocks []int           `json:"hourly_unlocks"`
	HourlyScreen  []int           `json:"hourly_screen"`
	HourlySteps   []int           `json:"hourly_steps"`
	Feel          string          `json:"feel"` // evening check-in; "" = none
	Tags          []string        `json:"tags"`
	Note          string          `json:"note"` // what really happened — for the reviewer, never seeded
}

// Personas returns the built-in test people.
func Personas() ([]Persona, error) {
	var p []Persona
	if err := json.Unmarshal(personasJSON, &p); err != nil {
		return nil, fmt.Errorf("mock personas: %w", err)
	}
	for _, x := range p {
		if !strings.HasPrefix(x.ID, Prefix) {
			return nil, fmt.Errorf("mock persona %q must start with %q", x.ID, Prefix)
		}
	}
	return p, nil
}

// IDs returns the persona ids (for DEV_USER_IDS-style exclusion).
func IDs() []string {
	p, _ := Personas()
	out := make([]string, 0, len(p))
	for _, x := range p {
		out = append(out, x.ID)
	}
	return out
}

// Truth returns the "what really happened" note for yesterday, if the persona has one.
func (p Persona) Truth() string {
	for _, d := range p.Days {
		if d.Offset == -1 {
			return d.Note
		}
	}
	return ""
}

// Seed writes all personas relative to `today`, replacing their earlier data.
// Forecasts, reviews and feedback of mock users are wiped so every run starts clean.
func Seed(ctx context.Context, db *pgxpool.Pool, today time.Time) ([]Persona, error) {
	personas, err := Personas()
	if err != nil {
		return nil, err
	}
	tx, err := db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	if err := purge(ctx, tx); err != nil {
		return nil, err
	}
	for _, p := range personas {
		profile, _ := json.Marshal(p.Profile)
		if p.Profile == nil {
			profile = []byte("{}")
		}
		first := today.AddDate(0, 0, p.Days[0].Offset)
		if _, err := tx.Exec(ctx,
			`INSERT INTO users (id, created_at, last_seen, profile) VALUES ($1, $2, NOW(), $3)`,
			p.ID, first, profile); err != nil {
			return nil, err
		}
		for _, d := range p.Days {
			date := today.AddDate(0, 0, d.Offset)
			apps, _ := json.Marshal(d.TopApps)
			if _, err := tx.Exec(ctx, `
				INSERT INTO daily_data (user_id, date, sleep_min, bedtime, wakeup, steps, screen_min, unlocks,
				                        first_unlock, last_unlock, top_apps, hourly_unlocks, hourly_screen,
				                        hourly_steps)
				VALUES ($1, $2, $3, $4::time, $5::time, $6, $7, $8, $9::time, $10::time, $11, $12, $13, $14)
			`, p.ID, date, d.SleepMin, d.Bedtime, d.Wakeup, d.Steps, d.ScreenMin, d.Unlocks,
				d.FirstUnlock, d.LastUnlock, apps, d.HourlyUnlocks, d.HourlyScreen, d.HourlySteps); err != nil {
				return nil, fmt.Errorf("%s %s: %w", p.ID, date.Format("2006-01-02"), err)
			}
			if d.Feel != "" {
				if _, err := tx.Exec(ctx,
					`INSERT INTO checkins (user_id, date, day_feel, tags) VALUES ($1, $2, $3, $4)`,
					p.ID, date, d.Feel, d.Tags); err != nil {
					return nil, err
				}
			}
		}
	}
	return personas, tx.Commit(ctx)
}

// Purge removes every mock user and everything attached to them.
func Purge(ctx context.Context, db *pgxpool.Pool) error {
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	if err := purge(ctx, tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Tables that reference users(id); order matters only for the final users delete.
var userTables = []string{"feedback", "reviews", "morning_messages", "checkins", "daily_data", "user_activity", "weekly_feedback"}

func purge(ctx context.Context, tx pgx.Tx) error {
	for _, t := range userTables {
		if _, err := tx.Exec(ctx, `DELETE FROM `+t+` WHERE user_id LIKE 'mock\_%'`); err != nil {
			return fmt.Errorf("purge %s: %w", t, err)
		}
	}
	_, err := tx.Exec(ctx, `DELETE FROM users WHERE id LIKE 'mock\_%'`)
	return err
}
