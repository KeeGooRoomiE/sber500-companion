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
	Attempts         int // LLM attempts for this day (placeholder rows have Message == "")
	LastAttemptAt    time.Time
}

type MorningRepo struct{ db *pgxpool.Pool }

func NewMorningRepo(db *pgxpool.Pool) *MorningRepo { return &MorningRepo{db: db} }

func (r *MorningRepo) Insert(ctx context.Context, m *MorningMessage) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO morning_messages
		  (user_id, date, message, prompt_tokens, completion_tokens, latency_ms, model, prompt_version)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		-- A successful retry replaces an empty placeholder; a real message is never overwritten.
		ON CONFLICT (user_id, date) DO UPDATE SET
		  message           = EXCLUDED.message,
		  prompt_tokens     = EXCLUDED.prompt_tokens,
		  completion_tokens = EXCLUDED.completion_tokens,
		  latency_ms        = EXCLUDED.latency_ms,
		  model             = EXCLUDED.model,
		  prompt_version    = EXCLUDED.prompt_version,
		  attempts          = morning_messages.attempts + 1,
		  last_attempt_at   = NOW()
		WHERE morning_messages.message = ''
	`, m.UserID, m.Date, m.Message, m.PromptTokens, m.CompletionTokens, m.LatencyMs, m.Model, m.PromptVersion)
	return err
}

// RecordFailure stores (or bumps) the empty placeholder for a failed LLM attempt.
func (r *MorningRepo) RecordFailure(ctx context.Context, userID string, date time.Time) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO morning_messages (user_id, date, message) VALUES ($1, $2, '')
		ON CONFLICT (user_id, date) DO UPDATE SET
		  attempts = morning_messages.attempts + 1, last_attempt_at = NOW()
		WHERE morning_messages.message = ''
	`, userID, date)
	return err
}

// HasDelivered reports whether the user ever got a real (non-empty) morning message.
func (r *MorningRepo) HasDelivered(ctx context.Context, userID string) (bool, error) {
	var ok bool
	err := r.db.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM morning_messages WHERE user_id = $1 AND message <> '')`, userID,
	).Scan(&ok)
	return ok, err
}

func (r *MorningRepo) ForDate(ctx context.Context, userID string, date time.Time) (*MorningMessage, error) {
	m := &MorningMessage{}
	err := r.db.QueryRow(ctx, `
		SELECT user_id, date, message, sent_at, prompt_tokens, completion_tokens, latency_ms, model,
		       attempts, last_attempt_at
		FROM morning_messages
		WHERE user_id = $1 AND date = $2
	`, userID, date).Scan(
		&m.UserID, &m.Date, &m.Message, &m.SentAt,
		&m.PromptTokens, &m.CompletionTokens, &m.LatencyMs, &m.Model,
		&m.Attempts, &m.LastAttemptAt,
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
		  AND (m.id IS NULL OR (m.message = '' AND m.attempts < 3))
		  AND d.user_id NOT LIKE 'mock\_%' -- test personas never burn the daily budget
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

// History returns the user's delivered morning messages, newest first.
func (r *MorningRepo) History(ctx context.Context, userID string, limit int) ([]MorningMessage, error) {
	rows, err := r.db.Query(ctx, `
		SELECT date, message FROM morning_messages
		WHERE user_id = $1 AND message <> ''
		ORDER BY date DESC LIMIT $2
	`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MorningMessage
	for rows.Next() {
		m := MorningMessage{UserID: userID}
		if err := rows.Scan(&m.Date, &m.Message); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
