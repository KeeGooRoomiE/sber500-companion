package repo

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type User struct {
	ID        string
	CreatedAt time.Time
	LastSeen  time.Time
	FCMToken  *string
}

type UserRepo struct{ db *pgxpool.Pool }

func NewUserRepo(db *pgxpool.Pool) *UserRepo { return &UserRepo{db: db} }

// Upsert creates a user on first call, updates last_seen on subsequent calls.
func (r *UserRepo) Upsert(ctx context.Context, id string, fcmToken *string) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO users (id, fcm_token)
		VALUES ($1, $2)
		ON CONFLICT (id) DO UPDATE
		  SET last_seen  = NOW(),
		      fcm_token  = COALESCE($2, users.fcm_token)
	`, id, fcmToken)
	return err
}

func (r *UserRepo) ActiveSince(ctx context.Context, since time.Time) ([]string, error) {
	rows, err := r.db.Query(ctx, `
		SELECT id FROM users WHERE last_seen >= $1
	`, since)
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

func (r *UserRepo) FCMToken(ctx context.Context, userID string) (*string, error) {
	var token *string
	err := r.db.QueryRow(ctx, `SELECT fcm_token FROM users WHERE id = $1`, userID).Scan(&token)
	return token, err
}
