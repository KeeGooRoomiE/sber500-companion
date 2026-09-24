package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type MorningMessage struct {
	UserID           string
	Date             time.Time
	Message          string
	SentAt           *time.Time
	PromptTokens     int
	CompletionTokens int
	LatencyMs        int
	Model            string
	PromptVersion    int
}

type MorningRepo struct{ db *pgxpool.Pool }

func NewMorningRepo(db *pgxpool.Pool) *MorningRepo { return &MorningRepo{db: db} }

func (r *MorningRepo) Insert(ctx context.Context, m *MorningMessage) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO morning_messages
		  (user_id, date, message, prompt_tokens, completion_tokens, latency_ms, model, prompt_version)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (user_id, date) DO NOTHING
	`, m.UserID, m.Date, m.Message, m.PromptTokens, m.CompletionTokens, m.LatencyMs, m.Model, m.PromptVersion)
	return err
}

func (r *MorningRepo) ForDate(ctx context.Context, userID string, date time.Time) (*MorningMessage, error) {
	m := &MorningMessage{}
	err := r.db.QueryRow(ctx, `
		SELECT user_id, date, message, sent_at, prompt_tokens, completion_tokens, latency_ms, model
		FROM morning_messages
		WHERE user_id = $1 AND date = $2
	`, userID, date).Scan(
		&m.UserID, &m.Date, &m.Message, &m.SentAt,
		&m.PromptTokens, &m.CompletionTokens, &m.LatencyMs, &m.Model,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return m, err
}

func (r *MorningRepo) MarkSent(ctx context.Context, userID string, date time.Time) error {
	_, err := r.db.Exec(ctx, `
		UPDATE morning_messages SET sent_at = NOW()
		WHERE user_id = $1 AND date = $2
	`, userID, date)
	return err
}

// UsersToGenerate returns users who sent data for the last two days before `date`
// and have no message for `date` yet. Stale or never-active ids are skipped —
// they would only burn LLM budget.
func (r *MorningRepo) UsersToGenerate(ctx context.Context, date time.Time) ([]string, error) {
	rows, err := r.db.Query(ctx, `
		SELECT DISTINCT d.user_id
		FROM daily_data d
		LEFT JOIN morning_messages m ON m.user_id = d.user_id AND m.date = $1
		WHERE d.date BETWEEN $1::date - 2 AND $1::date - 1
		  AND m.id IS NULL
	`, date)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
