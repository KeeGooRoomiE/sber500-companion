package repo

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type ExploreAnswer struct {
	UserID           string
	Date             time.Time
	QuestionID       string
	Question         string
	Text             string
	Facts            []string
	PromptTokens     int
	CompletionTokens int
	LatencyMs        int
	Model            string
	PromptVersion    int
}

type ExploreRepo struct{ db *pgxpool.Pool }

func NewExploreRepo(db *pgxpool.Pool) *ExploreRepo { return &ExploreRepo{db: db} }

// Today returns the day's answers in the order they were asked.
func (r *ExploreRepo) Today(ctx context.Context, userID string, date time.Time) ([]ExploreAnswer, error) {
	rows, err := r.db.Query(ctx, `
		SELECT question_id, question, text, facts FROM explore_answers
		WHERE user_id = $1 AND date = $2 ORDER BY created_at
	`, userID, date)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ExploreAnswer
	for rows.Next() {
		a := ExploreAnswer{UserID: userID, Date: date}
		var facts []byte
		if err := rows.Scan(&a.QuestionID, &a.Question, &a.Text, &facts); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(facts, &a.Facts)
		out = append(out, a)
	}
	return out, rows.Err()
}

func (r *ExploreRepo) Save(ctx context.Context, a *ExploreAnswer) error {
	facts, _ := json.Marshal(a.Facts)
	_, err := r.db.Exec(ctx, `
		INSERT INTO explore_answers (user_id, date, question_id, question, text, facts,
		                             prompt_tokens, completion_tokens, latency_ms, model, prompt_version)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		ON CONFLICT (user_id, date, question_id) DO NOTHING
	`, a.UserID, a.Date, a.QuestionID, a.Question, a.Text, facts,
		a.PromptTokens, a.CompletionTokens, a.LatencyMs, a.Model, a.PromptVersion)
	return err
}
