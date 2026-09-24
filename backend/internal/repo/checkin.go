package repo

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type CheckIn struct {
	UserID   string
	Date     time.Time
	DayFeel  string // "ok" | "meh" | "hard"
	Tags     []string
	NoteText *string
}

type CheckInRepo struct{ db *pgxpool.Pool }

func NewCheckInRepo(db *pgxpool.Pool) *CheckInRepo { return &CheckInRepo{db: db} }

func (r *CheckInRepo) Upsert(ctx context.Context, c *CheckIn) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO checkins (user_id, date, day_feel, tags, note_text)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (user_id, date) DO UPDATE SET
		  day_feel  = EXCLUDED.day_feel,
		  tags      = EXCLUDED.tags,
		  note_text = EXCLUDED.note_text
	`, c.UserID, c.Date, c.DayFeel, c.Tags, c.NoteText)
	return err
}

func (r *CheckInRepo) Latest(ctx context.Context, userID string) (*CheckIn, error) {
	c := &CheckIn{}
	err := r.db.QueryRow(ctx, `
		SELECT user_id, date, day_feel, tags, note_text
		FROM checkins
		WHERE user_id = $1
		ORDER BY date DESC
		LIMIT 1
	`, userID).Scan(&c.UserID, &c.Date, &c.DayFeel, &c.Tags, &c.NoteText)
	if err != nil {
		return nil, err
	}
	return c, nil
}
