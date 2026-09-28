package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Review struct {
	UserID           string
	Kind             string // "day" | "week"
	Date             time.Time
	Text             string
	PromptTokens     int
	CompletionTokens int
	LatencyMs        int
	Model            string
	PromptVersion    int
	CreatedAt        time.Time
}

type ReviewRepo struct{ db *pgxpool.Pool }

func NewReviewRepo(db *pgxpool.Pool) *ReviewRepo { return &ReviewRepo{db: db} }

func (r *ReviewRepo) Get(ctx context.Context, userID, kind string, date time.Time) (*Review, error) {
	v := &Review{}
	err := r.db.QueryRow(ctx, `
		SELECT user_id, kind, date, text, created_at FROM reviews
		WHERE user_id = $1 AND kind = $2 AND date = $3
	`, userID, kind, date).Scan(&v.UserID, &v.Kind, &v.Date, &v.Text, &v.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return v, err
}

// CountKindPrefixToday counts what this user generated today whose kind starts with prefix.
// Follow-ups use it: they are keyed by the insight's date, not today's, so a person could
// otherwise walk back through a month of past days and run up a call per question per day.
func (r *ReviewRepo) CountKindPrefixToday(ctx context.Context, userID, prefix string) (int, error) {
	var n int
	err := r.db.QueryRow(ctx, `
		SELECT count(*) FROM reviews
		WHERE user_id = $1 AND kind LIKE $2 || '%' AND created_at >= date_trunc('day', NOW())
	`, userID, prefix).Scan(&n)
	return n, err
}

// Save inserts or refreshes the review for (user, kind, date).
func (r *ReviewRepo) Save(ctx context.Context, v *Review) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO reviews (user_id, kind, date, text, prompt_tokens, completion_tokens, latency_ms, model, prompt_version)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		ON CONFLICT (user_id, kind, date) DO UPDATE SET
		  text = EXCLUDED.text, prompt_tokens = EXCLUDED.prompt_tokens,
		  completion_tokens = EXCLUDED.completion_tokens, latency_ms = EXCLUDED.latency_ms,
		  model = EXCLUDED.model, prompt_version = EXCLUDED.prompt_version, created_at = NOW()
	`, v.UserID, v.Kind, v.Date, v.Text, v.PromptTokens, v.CompletionTokens, v.LatencyMs, v.Model, v.PromptVersion)
	return err
}
