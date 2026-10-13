package api

import (
	"bytes"
	"context"
	"encoding/csv"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// exportTTL is how long a built CSV is served before a new request rebuilds it.
const exportTTL = 5 * time.Minute

// Export serves the raw call_log as a public CSV for transparency — the same anti-fraud log the
// aggregate /metrics are computed from, so anyone (judges included) can recompute Calls/DAU etc.
// Rows are anonymous (user_id is a SHA256 fingerprint); dev/mock/admin rows are excluded so the
// download matches the public metrics. Rebuilt at most once every 5 minutes, cached in memory.
type Export struct {
	db     *pgxpool.Pool
	devIDs []string
	origin string

	mu      sync.Mutex
	csv     []byte
	builtAt time.Time
}

func NewExport(db *pgxpool.Pool, devIDs []string) *Export {
	origin := os.Getenv("METRICS_ALLOWED_ORIGIN")
	if origin == "" {
		origin = "*"
	}
	if devIDs == nil {
		devIDs = []string{}
	}
	return &Export{db: db, devIDs: devIDs, origin: origin}
}

func (e *Export) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", e.origin)
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	e.mu.Lock()
	if e.csv == nil || time.Since(e.builtAt) > exportTTL {
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		data, err := e.build(ctx)
		cancel()
		if err != nil {
			e.mu.Unlock()
			slog.Error("export call_log", "err", err)
			writeError(w, http.StatusServiceUnavailable, "export unavailable")
			return
		}
		e.csv, e.builtAt = data, time.Now()
	}
	data, builtAt := e.csv, e.builtAt
	e.mu.Unlock()

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="call_log.csv"`)
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("Last-Modified", builtAt.UTC().Format(http.TimeFormat))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// build renders the whole call_log (minus dev/mock/admin) to CSV bytes.
func (e *Export) build(ctx context.Context) ([]byte, error) {
	rows, err := e.db.Query(ctx, `
		SELECT ts, user_id, session_id, call_type, component, trigger,
		       user_visible, result, latency_ms
		FROM call_log
		WHERE user_id <> 'admin'
		  AND NOT (user_id = ANY($1))
		  AND user_id NOT IN (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%')
		ORDER BY ts
	`, e.devIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var buf bytes.Buffer
	cw := csv.NewWriter(&buf)
	_ = cw.Write([]string{
		"ts", "user_id", "session_id", "call_type", "component",
		"trigger", "user_visible", "result", "latency_ms",
	})

	for rows.Next() {
		var (
			ts                          time.Time
			userID, sessionID, callType string
			component, trigger, result  string
			userVisible                 bool
			latencyMs                   *int64
		)
		if err := rows.Scan(&ts, &userID, &sessionID, &callType, &component,
			&trigger, &userVisible, &result, &latencyMs); err != nil {
			return nil, err
		}
		lat := ""
		if latencyMs != nil {
			lat = strconv.FormatInt(*latencyMs, 10)
		}
		_ = cw.Write([]string{
			ts.UTC().Format(time.RFC3339), userID, sessionID, callType, component,
			trigger, strconv.FormatBool(userVisible), result, lat,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	cw.Flush()
	return buf.Bytes(), cw.Error()
}
