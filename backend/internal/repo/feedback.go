package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrNothingToRate: there is no delivered text for this (user, kind, date).
var ErrNothingToRate = errors.New("nothing to rate")

type FeedbackRepo struct{ db *pgxpool.Pool }

func NewFeedbackRepo(db *pgxpool.Pool) *FeedbackRepo { return &FeedbackRepo{db: db} }

// Set stores «Совпало» (hit) / «Не совсем» (miss) for a forecast or review; a second tap
// changes the answer. The prompt version is taken from the rated text itself.
func (r *FeedbackRepo) Set(ctx context.Context, userID, kind string, date time.Time, verdict string) error {
	var src string
	switch kind {
	case "morning":
		src = `SELECT prompt_version FROM morning_messages WHERE user_id = $1 AND date = $3 AND message <> ''`
	case "day", "week":
		src = `SELECT prompt_version FROM reviews WHERE user_id = $1 AND kind = $2 AND date = $3`
	default:
		return ErrNothingToRate
	}
	tag, err := r.db.Exec(ctx, `
		INSERT INTO feedback (user_id, kind, date, verdict, prompt_version)
		SELECT $1, $2, $3, $4, coalesce(v.prompt_version, 0) FROM (`+src+`) v
		ON CONFLICT (user_id, kind, date) DO UPDATE SET
		  verdict = EXCLUDED.verdict, updated_at = NOW()
	`, userID, kind, date, verdict)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNothingToRate
	}
	return nil
}

// Get returns the verdict for one text, or "" if not rated.
func (r *FeedbackRepo) Get(ctx context.Context, userID, kind string, date time.Time) (string, error) {
	var v string
	err := r.db.QueryRow(ctx,
		`SELECT verdict FROM feedback WHERE user_id = $1 AND kind = $2 AND date = $3`, userID, kind, date,
	).Scan(&v)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return v, err
}

// Morning returns morning verdicts in [from, to] keyed by date ("2006-01-02").
func (r *FeedbackRepo) Morning(ctx context.Context, userID string, from, to time.Time) (map[string]string, error) {
	rows, err := r.db.Query(ctx, `
		SELECT date, verdict FROM feedback
		WHERE user_id = $1 AND kind = 'morning' AND date BETWEEN $2 AND $3
	`, userID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var d time.Time
		var v string
		if err := rows.Scan(&d, &v); err != nil {
			return nil, err
		}
		out[d.Format("2006-01-02")] = v
	}
	return out, rows.Err()
}

// Accuracy is hits / (hits + misses) for one kind and prompt version.
type Accuracy struct {
	Kind          string   `json:"kind"`
	PromptVersion int      `json:"prompt_version"` // 0 = built-in default
	Hits          int      `json:"hits"`
	Misses        int      `json:"misses"`
	Pct           *float64 `json:"pct"`
}

// AccuracyByVersion groups verdicts since `since` by kind and prompt version (dev users excluded).
func (r *FeedbackRepo) AccuracyByVersion(ctx context.Context, since time.Time, devIDs []string) ([]Accuracy, error) {
	if devIDs == nil {
		devIDs = []string{}
	}
	rows, err := r.db.Query(ctx, `
		SELECT kind, prompt_version,
		       count(*) FILTER (WHERE verdict = 'hit'), count(*) FILTER (WHERE verdict = 'miss')
		FROM feedback
		WHERE updated_at >= $1 AND NOT (user_id = ANY($2)) AND user_id NOT LIKE 'mock\_%'
		GROUP BY kind, prompt_version ORDER BY kind, prompt_version
	`, since, devIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Accuracy
	for rows.Next() {
		var a Accuracy
		if err := rows.Scan(&a.Kind, &a.PromptVersion, &a.Hits, &a.Misses); err != nil {
			return nil, err
		}
		if n := a.Hits + a.Misses; n > 0 {
			p := float64(int(float64(a.Hits)/float64(n)*1000+0.5)) / 10
			a.Pct = &p
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
