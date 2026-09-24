// Package prompts keeps versioned LLM prompts in the database with a built-in fallback.
package prompts

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// MorningSystem is the system prompt of the morning forecast.
const MorningSystem = "morning_system"

// MaxBodyLen keeps a prompt within a sane size (≈ a few hundred tokens of instructions).
const MaxBodyLen = 8000

type Prompt struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	Version   int       `json:"version"`
	Body      string    `json:"body"`
	IsActive  bool      `json:"is_active"`
	Note      string    `json:"note"`
	CreatedAt time.Time `json:"created_at"`
}

var ErrNotFound = errors.New("prompt not found")

type Store struct {
	db       *pgxpool.Pool
	defaults map[string]string

	mu    sync.Mutex
	cache map[string]cached
}

type cached struct {
	body    string
	version int
	at      time.Time
}

const cacheTTL = 30 * time.Second

func NewStore(db *pgxpool.Pool, defaults map[string]string) *Store {
	return &Store{db: db, defaults: defaults, cache: map[string]cached{}}
}

// Active returns the active prompt for name, or the built-in default with version 0.
// Cached for 30 s, so an activation takes effect within half a minute.
func (s *Store) Active(ctx context.Context, name string) (string, int) {
	s.mu.Lock()
	c, ok := s.cache[name]
	s.mu.Unlock()
	if ok && time.Since(c.at) < cacheTTL {
		return c.body, c.version
	}

	body, version := s.defaults[name], 0
	err := s.db.QueryRow(ctx,
		`SELECT body, version FROM prompts WHERE name = $1 AND is_active`, name,
	).Scan(&body, &version)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		// DB hiccup: keep serving the last known prompt rather than failing generation
		if ok {
			return c.body, c.version
		}
		return s.defaults[name], 0
	}

	s.mu.Lock()
	s.cache[name] = cached{body: body, version: version, at: time.Now()}
	s.mu.Unlock()
	return body, version
}

func (s *Store) Default(name string) string { return s.defaults[name] }

func (s *Store) List(ctx context.Context, name string) ([]Prompt, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, name, version, body, is_active, note, created_at
		FROM prompts WHERE name = $1 ORDER BY version DESC
	`, name)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[Prompt])
}

// Create stores a new version (max+1) and optionally activates it, in one transaction.
func (s *Store) Create(ctx context.Context, name, body, note string, activate bool) (*Prompt, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	// Serialize concurrent creates of the same name
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext($1))`, name); err != nil {
		return nil, err
	}
	if activate {
		if _, err := tx.Exec(ctx, `UPDATE prompts SET is_active = FALSE WHERE name = $1 AND is_active`, name); err != nil {
			return nil, err
		}
	}
	p := &Prompt{}
	err = tx.QueryRow(ctx, `
		INSERT INTO prompts (name, version, body, is_active, note)
		VALUES ($1, COALESCE((SELECT max(version) FROM prompts WHERE name = $1), 0) + 1, $2, $3, $4)
		RETURNING id, name, version, body, is_active, note, created_at
	`, name, body, activate, note).Scan(&p.ID, &p.Name, &p.Version, &p.Body, &p.IsActive, &p.Note, &p.CreatedAt)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	s.invalidate(name)
	return p, nil
}

// Activate makes version id the active one for its name (rollback = activate an older id).
func (s *Store) Activate(ctx context.Context, id int64) (*Prompt, error) {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	p := &Prompt{}
	err = tx.QueryRow(ctx,
		`SELECT id, name, version, body, is_active, note, created_at FROM prompts WHERE id = $1`, id,
	).Scan(&p.ID, &p.Name, &p.Version, &p.Body, &p.IsActive, &p.Note, &p.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE prompts SET is_active = FALSE WHERE name = $1 AND is_active`, p.Name); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE prompts SET is_active = TRUE WHERE id = $1`, id); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	p.IsActive = true
	s.invalidate(p.Name)
	return p, nil
}

// Deactivate switches name back to the built-in default.
func (s *Store) Deactivate(ctx context.Context, name string) error {
	_, err := s.db.Exec(ctx, `UPDATE prompts SET is_active = FALSE WHERE name = $1 AND is_active`, name)
	s.invalidate(name)
	return err
}

func (s *Store) invalidate(name string) {
	s.mu.Lock()
	delete(s.cache, name)
	s.mu.Unlock()
}
