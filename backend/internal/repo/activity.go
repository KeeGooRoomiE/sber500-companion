package repo

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type ActivityRepo struct{ db *pgxpool.Pool }

func NewActivityRepo(db *pgxpool.Pool) *ActivityRepo { return &ActivityRepo{db: db} }

// Touch marks the user as active on `date` (idempotent).
func (r *ActivityRepo) Touch(ctx context.Context, userID string, date time.Time) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO user_activity (user_id, date) VALUES ($1, $2)
		ON CONFLICT DO NOTHING
	`, userID, date)
	return err
}

// Heartbeat records that the server is up during the current minute.
func (r *ActivityRepo) Heartbeat(ctx context.Context, now time.Time) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO heartbeats (minute) VALUES (date_trunc('minute', $1::timestamptz))
		ON CONFLICT DO NOTHING
	`, now)
	return err
}
