package repo

import (
	"bytes"
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrChatConflict = errors.New("chat request id used for another message")
var ErrChatBusy = errors.New("chat request in progress")

type ChatRepo struct{ db *pgxpool.Pool }

func NewChatRepo(db *pgxpool.Pool) *ChatRepo { return &ChatRepo{db} }

type ChatFact struct {
	ID   int64  `json:"id"`
	Key  string `json:"key"`
	Text string `json:"fact"`
}

// Claim reserves a provider call before making it. Stale leases recover after a process restart.
func (r *ChatRepo) Claim(ctx context.Context, user, id string, digest []byte, source, question string) (int, []byte, error) {
	var attempt int
	err := r.db.QueryRow(ctx, `
      INSERT INTO chat_requests(user_id, request_id, fingerprint, source, daily_question_id, claimed_at)
      VALUES ($1,$2,$3,$4,$5,now())
      ON CONFLICT (user_id, request_id) DO UPDATE
      SET claimed_at=now(), attempt=chat_requests.attempt+1
      WHERE chat_requests.fingerprint=EXCLUDED.fingerprint AND chat_requests.response IS NULL
      AND (chat_requests.claimed_at IS NULL OR chat_requests.claimed_at<now()-interval '2 minutes')
      RETURNING attempt`, user, id, digest, source, question).Scan(&attempt)
	if !errors.Is(err, pgx.ErrNoRows) {
		return attempt, nil, err
	}
	var existing, response []byte
	if err = r.db.QueryRow(ctx, `SELECT fingerprint,response FROM chat_requests WHERE user_id=$1 AND request_id=$2`, user, id).Scan(&existing, &response); err != nil {
		return 0, nil, err
	}
	if !bytes.Equal(existing, digest) {
		return 0, nil, ErrChatConflict
	}
	if response == nil {
		return 0, nil, ErrChatBusy
	}
	return 0, response, nil
}

func (r *ChatRepo) Release(ctx context.Context, user, id string, attempt int) error {
	_, err := r.db.Exec(ctx, `UPDATE chat_requests SET claimed_at=NULL WHERE user_id=$1 AND request_id=$2 AND attempt=$3 AND response IS NULL`, user, id, attempt)
	return err
}

func (r *ChatRepo) Facts(ctx context.Context, user string, after int64, limit int) ([]ChatFact, error) {
	rows, err := r.db.Query(ctx, `SELECT id,fact_key,fact FROM chat_memory WHERE user_id=$1 AND id>$2 ORDER BY id LIMIT $3`, user, after, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[ChatFact])
}

// RecentFacts prioritizes new corrections when the bounded interpretation context fills.
func (r *ChatRepo) RecentFacts(ctx context.Context, user string, limit int) ([]ChatFact, error) {
	rows, err := r.db.Query(ctx, `SELECT id,fact_key,fact FROM chat_memory WHERE user_id=$1 ORDER BY updated_at DESC,id DESC LIMIT $2`, user, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[ChatFact])
}

func (r *ChatRepo) LastCursor(ctx context.Context, user string) (int64, error) {
	var cursor int64
	err := r.db.QueryRow(ctx, `SELECT coalesce((SELECT memory_cursor FROM chat_requests WHERE user_id=$1 AND response IS NOT NULL ORDER BY finished_at DESC,request_id DESC LIMIT 1),0)`, user).Scan(&cursor)
	return cursor, err
}

func (r *ChatRepo) Finish(ctx context.Context, user, id string, attempt int, response []byte, cursor int64, change *ChatFact, forget bool) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	updated, err := tx.Exec(ctx, `UPDATE chat_requests SET response=$4, memory_cursor=$5,finished_at=now()
       WHERE user_id=$1 AND request_id=$2 AND attempt=$3 AND response IS NULL`, user, id, attempt, response, cursor)
	if err != nil {
		return err
	}
	if updated.RowsAffected() != 1 {
		return ErrChatBusy
	}
	if change != nil {
		if forget {
			updated, err = tx.Exec(ctx, `DELETE FROM chat_memory WHERE user_id=$1 AND id=$2 AND fact=$3`, user, change.ID, change.Text)
		} else if change.ID != 0 {
			updated, err = tx.Exec(ctx, `UPDATE chat_memory SET fact=$3,updated_at=now() WHERE user_id=$1 AND id=$2 AND fact_key=$4`, user, change.ID, change.Text, change.Key)
		} else {
			updated, err = tx.Exec(ctx, `INSERT INTO chat_memory(user_id,fact_key,fact) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, user, change.Key, change.Text)
		}
		if err != nil {
			return err
		}
		if updated.RowsAffected() != 1 {
			return ErrChatBusy
		}
	}
	return tx.Commit(ctx)
}

func (r *ChatRepo) RecordUsage(ctx context.Context, user, id, model string, input, output int) error {
	_, err := r.db.Exec(ctx, `INSERT INTO chat_model_usage(user_id,request_id,model,prompt_tokens,completion_tokens) VALUES ($1,$2,$3,$4,$5)`, user, id, model, input, output)
	return err
}
