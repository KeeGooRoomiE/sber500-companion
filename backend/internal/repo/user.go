package repo

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/jackc/pgx/v5/pgxpool"
)

type User struct {
	ID        string
	CreatedAt time.Time
	LastSeen  time.Time
}

type UserRepo struct{ db *pgxpool.Pool }

func NewUserRepo(db *pgxpool.Pool) *UserRepo { return &UserRepo{db: db} }

// Register returns (id, token, returning). With a device key the phone keeps its identity across
// reinstalls: a known key gets a fresh token for the same user (the old token stops working).
// Only hashes are stored; the token itself is shown once.
func (r *UserRepo) Register(ctx context.Context, deviceKey string, isDev bool, source, campaign string) (string, string, bool, error) {
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
		INSERT INTO users (id, token_hash, device_hash, is_dev, source, campaign)
		VALUES ($1, $2, $3, $4, NULLIF($5, ''), NULLIF($6, ''))
		ON CONFLICT (device_hash) WHERE device_hash IS NOT NULL
		DO UPDATE SET token_hash = EXCLUDED.token_hash, last_seen = NOW()
		RETURNING id
	`, "u_"+id, HashToken(token), deviceHash, isDev, source, campaign).Scan(&id)
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

// SetAppVersion records which build this phone is running. Sent on every ping, because the
// app is distributed as an APK link and several versions are live at once.
func (r *UserRepo) SetAppVersion(ctx context.Context, userID, version string) error {
	_, err := r.db.Exec(ctx,
		`UPDATE users SET app_version = $2 WHERE id = $1 AND app_version IS DISTINCT FROM $2`,
		userID, version)
	return err
}

// SetTimezone records where the phone thinks it is. Sent with every snapshot, because people
// travel and because a build that never sent one may start after an update.
//
// The name is stored as given, but the offset is resolved here against the server's tzdata:
// a name this server cannot load would otherwise be written next to a silent zero offset and
// schedule that person's morning in Moscow time while claiming otherwise.
func (r *UserRepo) SetTimezone(ctx context.Context, userID, name string) error {
	loc, err := time.LoadLocation(name)
	if err != nil {
		return fmt.Errorf("unknown timezone %q: %w", name, err)
	}
	_, offset := time.Now().In(loc).Zone()
	_, err = r.db.Exec(ctx, `
		UPDATE users SET timezone = $2, tz_offset_min = $3, tz_seen_at = NOW()
		WHERE id = $1 AND (timezone IS DISTINCT FROM $2 OR tz_offset_min IS DISTINCT FROM $3)`,
		userID, name, offset/60)
	return err
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

// SetPushToken records where this phone can be reached by a server-sent notification.
// Called by the app whenever its push client gives out a token (first start, rotation).
func (r *UserRepo) SetPushToken(ctx context.Context, userID, provider, token string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET push_token = $3, push_provider = $2, push_token_seen_at = NOW()
		WHERE id = $1`, userID, provider, token)
	return err
}

// ClearPushToken drops a token the provider says is dead (app removed, token rotated). It only
// matches the exact token, so a fresh one registered in the meantime is left alone.
func (r *UserRepo) ClearPushToken(ctx context.Context, userID, token string) error {
	_, err := r.db.Exec(ctx, `
		UPDATE users SET push_token = NULL, push_provider = NULL
		WHERE id = $1 AND push_token = $2`, userID, token)
	return err
}

// PushTarget is one reachable phone.
type PushTarget struct {
	UserID string
	Token  string
}

// PushTargetsByIDs returns the registered phones among the given users. Dev users are included
// on purpose: naming someone explicitly is how the owner tests on their own phone.
func (r *UserRepo) PushTargetsByIDs(ctx context.Context, ids []string) ([]PushTarget, error) {
	return r.pushTargets(ctx, `
		SELECT id, push_token FROM users
		WHERE id = ANY($1) AND push_token IS NOT NULL`, ids)
}

// PushTargetsSilent returns the registered phones of people with no activity for the last
// [days] days (today included): the audience of a «come back» notification. Dev users and
// anyone who never produced a day of activity are out — the first have no use for it, the
// second were never a user in the first place.
func (r *UserRepo) PushTargetsSilent(ctx context.Context, days int) ([]PushTarget, error) {
	return r.pushTargets(ctx, `
		SELECT u.id, u.push_token FROM users u
		WHERE u.push_token IS NOT NULL AND NOT u.is_dev
		  AND EXISTS (SELECT 1 FROM user_activity a WHERE a.user_id = u.id)
		  AND NOT EXISTS (
		      SELECT 1 FROM user_activity a
		      WHERE a.user_id = u.id AND a.date > CURRENT_DATE - $1::int)`, days)
}

func (r *UserRepo) pushTargets(ctx context.Context, query string, arg any) ([]PushTarget, error) {
	rows, err := r.db.Query(ctx, query, arg)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PushTarget
	for rows.Next() {
		var t PushTarget
		if err := rows.Scan(&t.UserID, &t.Token); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// CampaignQuery picks the audience of one automatic notification at this very hour.
type CampaignQuery struct {
	Campaign string
	// Silence is counted in the person's own calendar days since the last day in user_activity
	// (app opened, check-in, forecast read: requests a person caused, not background uploads).
	MinSilentDays int
	MaxSilentDays int // beyond this the person is gone, not silent: no more notifications
	LocalHour     int // the hour of the person's own clock at which the campaign goes out
	Weekday       int // 0 = Sunday … 6 = Saturday in the person's zone; -1 = any day
	// OncePerStreak: never again until the person shows up and goes silent anew.
	// Otherwise the campaign repeats, but not sooner than MinGapDays after its last send.
	OncePerStreak bool
	MinGapDays    int
}

// defaultOffsetMin is Moscow: where a phone that never reported its zone most likely is.
const defaultOffsetMin = 180

// PushTargetsCampaign returns the phones [q] applies to right now. Dev users are out, people
// who were never active are out, and nobody gets two automatic notifications within 20 hours,
// whatever the campaigns.
func (r *UserRepo) PushTargetsCampaign(ctx context.Context, q CampaignQuery) ([]PushTarget, error) {
	rows, err := r.db.Query(ctx, `
		WITH u AS (
		    SELECT u.id, u.push_token,
		           (NOW() AT TIME ZONE 'UTC') + make_interval(mins => COALESCE(u.tz_offset_min, $8)) AS local_ts,
		           (SELECT MAX(a.date) FROM user_activity a WHERE a.user_id = u.id) AS last_active
		    FROM users u
		    WHERE u.push_token IS NOT NULL AND NOT u.is_dev
		)
		SELECT u.id, u.push_token FROM u
		WHERE u.last_active IS NOT NULL
		  AND (u.local_ts::date - u.last_active) BETWEEN $2 AND $3
		  AND EXTRACT(HOUR FROM u.local_ts)::int = $4
		  AND ($5 < 0 OR EXTRACT(DOW FROM u.local_ts)::int = $5)
		  AND NOT EXISTS (
		      SELECT 1 FROM push_campaign_sends s
		      WHERE s.user_id = u.id AND s.sent_at > NOW() - INTERVAL '20 hours')
		  AND NOT EXISTS (
		      SELECT 1 FROM push_campaign_sends s
		      WHERE s.user_id = u.id AND s.campaign = $1
		        AND CASE WHEN $6 THEN s.sent_at::date > u.last_active
		                 ELSE s.sent_at > NOW() - make_interval(days => $7) END)`,
		q.Campaign, q.MinSilentDays, q.MaxSilentDays, q.LocalHour, q.Weekday, q.OncePerStreak, q.MinGapDays, defaultOffsetMin)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PushTarget
	for rows.Next() {
		var t PushTarget
		if err := rows.Scan(&t.UserID, &t.Token); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// MarkCampaignSent records the send before it happens: a crash mid-batch then costs a person
// one missed notification rather than a repeated one.
func (r *UserRepo) MarkCampaignSent(ctx context.Context, campaign string, userIDs []string) error {
	_, err := r.db.Exec(ctx, `
		INSERT INTO push_campaign_sends (user_id, campaign)
		SELECT unnest($2::text[]), $1::text`, campaign, userIDs)
	return err
}
