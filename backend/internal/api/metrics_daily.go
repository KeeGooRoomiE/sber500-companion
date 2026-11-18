package api

import (
	"context"
	"crypto/subtle"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/mock"
)

// Daily product metrics for the programme's collector (their spec «ежедневная выгрузка
// продуктовых метрик»): GET /api/v1/metrics/daily?date_from=…&date_to=…, Bearer token.
//
// Aggregates only, one record per calendar day in Moscow time, zeros for a quiet day.
//
// How each number is counted — the same rules as the public /metrics page, so the two can
// never disagree. Where this differs from the spec's wording it is stated at connection:
//   - dau: people with a row in user_activity that day — the app was opened or an action was
//     taken (check-in, forecast read, review, question). Background uploads do not count.
//   - llm_requests: calls the person set off. Everything with call_type = 'llm' except the
//     nightly batch, plus the batch forecasts they actually picked up — a forecast is written
//     at 03:00 and fetched when the phone is first unlocked, so the model call is an
//     optimisation of a request the person does make. Generated and never fetched does not
//     count: over the week before this was written, 52% of batch forecasts were never
//     collected, and the people they belonged to did nothing in the app that day.
//   - tool_requests: 0. The spec counts tool/function calls made by an agent; this product
//     has no function calling at all — the prompt is assembled in Go and the model returns
//     text. The spec covers this case: «если инструментов нет, передаётся 0». What we log as
//     call_type = 'tool' is the app talking to its own backend (usage upload, reading the
//     prepared forecast, saving a check-in), which the spec excludes by name.
//   - total_users / new_users: users by registration date.
//
// Everywhere: dev users (DEV_USER_IDS, debug builds), mock personas and the operator's own
// 'admin' rows are out.
const (
	dailyMaxRangeDays = 90
	dailyMinTokenLen  = 32
	dailyDateLayout   = "2006-01-02"
)

type DailyMetricsResponse struct {
	StartupID   string        `json:"startup_id"`
	Timezone    string        `json:"timezone"`
	GeneratedAt string        `json:"generated_at"`
	Data        []DailyRecord `json:"data"`
}

type DailyRecord struct {
	Date         string `json:"date"`
	DAU          int    `json:"dau"`
	LLMRequests  int    `json:"llm_requests"`
	ToolRequests int    `json:"tool_requests"`
	TotalUsers   int    `json:"total_users"`
	NewUsers     int    `json:"new_users"`
	IsFinal      bool   `json:"is_final"`
}

type DailyMetrics struct {
	db        *pgxpool.Pool
	devIDs    []string
	token     string
	startupID string
}

// NewDailyMetrics reads METRICS_DAILY_TOKEN (a token for this method alone, read-only by
// construction: the handler only selects) and METRICS_STARTUP_ID. A missing or short token
// leaves the method closed: every request gets 401.
func NewDailyMetrics(db *pgxpool.Pool, devIDs []string) *DailyMetrics {
	token := strings.TrimSpace(os.Getenv("METRICS_DAILY_TOKEN"))
	if token != "" && len(token) < dailyMinTokenLen {
		slog.Warn("METRICS_DAILY_TOKEN is shorter than 32 characters, /metrics/daily stays closed")
		token = ""
	}
	id := strings.TrimSpace(os.Getenv("METRICS_STARTUP_ID"))
	if id == "" {
		id = "29754" // the participant id in the programme
	}
	return &DailyMetrics{db: db, devIDs: devIDs, token: token, startupID: id}
}

func (m *DailyMetrics) authorized(r *http.Request) bool {
	if m.token == "" {
		return false
	}
	got, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(strings.TrimSpace(got)), []byte(m.token)) == 1
}

// parseDailyRange validates the two dates: both required, YYYY-MM-DD, from ≤ to, at most 90 days.
func parseDailyRange(from, to string) (time.Time, time.Time, error) {
	if from == "" || to == "" {
		return time.Time{}, time.Time{}, errors.New("date_from and date_to are required (YYYY-MM-DD)")
	}
	f, err := time.Parse(dailyDateLayout, from)
	if err != nil {
		return time.Time{}, time.Time{}, errors.New("date_from must be YYYY-MM-DD")
	}
	t, err := time.Parse(dailyDateLayout, to)
	if err != nil {
		return time.Time{}, time.Time{}, errors.New("date_to must be YYYY-MM-DD")
	}
	if t.Before(f) {
		return time.Time{}, time.Time{}, errors.New("date_to is earlier than date_from")
	}
	if days := int(t.Sub(f).Hours()/24) + 1; days > dailyMaxRangeDays {
		return time.Time{}, time.Time{}, errors.New("the range is longer than 90 days")
	}
	return f, t, nil
}

func (m *DailyMetrics) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !m.authorized(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, http.StatusUnauthorized, "missing or invalid token")
		return
	}
	from, to, err := parseDailyRange(r.URL.Query().Get("date_from"), r.URL.Query().Get("date_to"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	data, err := m.compute(ctx, from, to)
	if err != nil {
		slog.Error("metrics daily failed", "err", err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, DailyMetricsResponse{
		StartupID:   m.startupID,
		Timezone:    clock.Location().String(),
		GeneratedAt: clock.Now().Format(time.RFC3339),
		Data:        data,
	})
}

func (m *DailyMetrics) compute(ctx context.Context, from, to time.Time) ([]DailyRecord, error) {
	tz := clock.Location().String()
	today := clock.Today()

	// The same exclusion list as /metrics: DEV_USER_IDS, debug-build users, mock personas.
	excluded := append([]string{}, m.devIDs...)
	var more []string
	if err := m.db.QueryRow(ctx,
		`SELECT coalesce(array_agg(id), '{}') FROM users WHERE id LIKE $1 OR is_dev`,
		mock.Prefix+"%").Scan(&more); err != nil {
		return nil, err
	}
	excluded = append(excluded, more...)

	// Days are calendar days in the product zone (Moscow): a call at 23:30 UTC belongs to the
	// next Moscow day. The call_log scan is bounded by the range, so 90 days stay one index scan.
	rows, err := m.db.Query(ctx, `
		WITH days AS (
		    SELECT d::date AS day FROM generate_series($2::date, $3::date, interval '1 day') d
		), scoped AS (
		    SELECT user_id, call_type, component, trigger, (ts AT TIME ZONE $4)::date AS day
		    FROM call_log
		    WHERE ts >= ($2::date::timestamp AT TIME ZONE $4)
		      AND ts <  (($3::date + 1)::timestamp AT TIME ZONE $4)
		      AND user_id <> 'admin' AND NOT (user_id = ANY($1))
		), fetched AS (
		    -- (person, day) pairs where the prepared forecast was actually collected
		    SELECT DISTINCT user_id, day FROM scoped
		    WHERE call_type = 'tool' AND component = 'morning_api'
		), calls AS (
		    SELECT s.day,
		           count(*) FILTER (
		               WHERE s.call_type = 'llm' AND (
		                   s.trigger <> 'scheduled'
		                   OR EXISTS (SELECT 1 FROM fetched f
		                              WHERE f.user_id = s.user_id AND f.day = s.day)
		               )
		           ) AS llm
		    FROM scoped s
		    GROUP BY 1
		), active AS (
		    SELECT date AS day, count(*) AS dau
		    FROM user_activity
		    WHERE date BETWEEN $2::date AND $3::date AND NOT (user_id = ANY($1))
		    GROUP BY 1
		), reg AS (
		    SELECT (created_at AT TIME ZONE $4)::date AS day, count(*) AS n
		    FROM users WHERE NOT (id = ANY($1))
		    GROUP BY 1
		)
		SELECT days.day,
		       coalesce(active.dau, 0),
		       coalesce(calls.llm, 0),
		       0,
		       (SELECT coalesce(sum(reg.n), 0)::bigint FROM reg WHERE reg.day <= days.day),
		       coalesce((SELECT reg.n FROM reg WHERE reg.day = days.day), 0)
		FROM days
		LEFT JOIN calls  ON calls.day  = days.day
		LEFT JOIN active ON active.day = days.day
		ORDER BY days.day`, excluded, from, to, tz)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []DailyRecord{}
	for rows.Next() {
		var day time.Time
		var rec DailyRecord
		if err := rows.Scan(&day, &rec.DAU, &rec.LLMRequests, &rec.ToolRequests, &rec.TotalUsers, &rec.NewUsers); err != nil {
			return nil, err
		}
		rec.Date = day.Format(dailyDateLayout)
		// A day is closed once the Moscow calendar has moved past it.
		rec.IsFinal = day.Before(today)
		out = append(out, rec)
	}
	return out, rows.Err()
}
