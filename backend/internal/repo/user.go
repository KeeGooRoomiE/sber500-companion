package repo

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

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

// Register returns (id, token, returning). With a device key the phone keeps its identity across
// reinstalls: a known key gets a fresh token for the same user (the old token stops working).
// Only hashes are stored; the token itself is shown once.
func (r *UserRepo) Register(ctx context.Context, deviceKey string) (string, string, bool, error) {
	token, err := randomString(32)
	if err != nil {
		return "", "", false, err
	}
	var deviceHash *string
	if deviceKey != "" {
		h := HashToken("device:" + deviceKey)
		deviceHash = &h
		var id string
		err := r.db.QueryRow(ctx, `
			UPDATE users SET token_hash = $2, last_seen = NOW() WHERE device_hash = $1 RETURNING id
		`, h, HashToken(token)).Scan(&id)
		if err == nil {
			return id, token, true, nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return "", "", false, err
		}
	}
	id, err := randomString(16)
	if err != nil {
		return "", "", false, err
	}
	// ON CONFLICT: two first launches racing with the same key — the second one takes over.
	err = r.db.QueryRow(ctx, `
		INSERT INTO users (id, token_hash, device_hash) VALUES ($1, $2, $3)
		ON CONFLICT (device_hash) WHERE device_hash IS NOT NULL
		DO UPDATE SET token_hash = EXCLUDED.token_hash, last_seen = NOW()
		RETURNING id
	`, "u_"+id, HashToken(token), deviceHash).Scan(&id)
	return id, token, false, err
}

// ByToken resolves a bearer token to a user id and bumps last_seen. ok=false if unknown.
func (r *UserRepo) ByToken(ctx context.Context, token string) (string, bool, error) {
	var id string
	err := r.db.QueryRow(ctx, `
		UPDATE users SET last_seen = NOW() WHERE token_hash = $1 RETURNING id
	`, HashToken(token)).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	return id, err == nil, err
}

// HashToken is the stored form of a device token.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

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

// SetProfile replaces the user's profile answers.
func (r *UserRepo) SetProfile(ctx context.Context, userID string, profile map[string]string) error {
	_, err := r.db.Exec(ctx, `UPDATE users SET profile = $2 WHERE id = $1`, userID, profile)
	return err
}

// Profile returns the user's profile answers (empty map if none).
func (r *UserRepo) Profile(ctx context.Context, userID string) (map[string]string, error) {
	p := map[string]string{}
	err := r.db.QueryRow(ctx, `SELECT profile FROM users WHERE id = $1`, userID).Scan(&p)
	if errors.Is(err, pgx.ErrNoRows) {
		return map[string]string{}, nil
	}
	return p, err
}
