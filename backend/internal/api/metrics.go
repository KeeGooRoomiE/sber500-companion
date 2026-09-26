package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
)

// DAUPoint is one bar of the 7-day chart on web/metrics.html.
type DAUPoint struct {
	Date string `json:"date"`
	DAU  int    `json:"dau"`
}

// MetricsResponse matches the keys web/metrics.html renders. Null = not enough data yet.
type MetricsResponse struct {
	UpdatedAt string `json:"updated_at"`

	UsersTotal     int        `json:"users_total"`
	NewUsersToday  int        `json:"new_users_today"`
	DAUToday       int        `json:"dau_today"`
	DAU7dAvg       float64    `json:"dau_7d_avg"`
	DAUHistory     []DAUPoint `json:"dau_history"`
	RetentionD1Pct *float64   `json:"retention_d1_pct"`
	RetentionD7Pct *float64   `json:"retention_d7_pct"`

	// Retention funnel — absolute counts for the visual funnel
	FunnelTotal     int `json:"funnel_total"`
	FunnelActivated int `json:"funnel_activated"` // users with ≥1 activity day
	FunnelD1        int `json:"funnel_d1"`        // retained at D1
	FunnelD7        int `json:"funnel_d7"`        // retained at D7
	FunnelDAU       int `json:"funnel_dau"`       // active today

	CheckinRatePct        *float64 `json:"checkin_rate_pct"`
	MorningDeliveredToday int      `json:"morning_delivered_today"`
	CallsPerDAU           *float64 `json:"calls_per_dau"`

	// scenario_completed = morning received + evening check-in on the same day
	ScenarioCompletedToday int `json:"scenario_completed_today"`
	ScenarioCompletedTotal int `json:"scenario_completed_total"`

	// LLM budget from the proxy key/info (cached 15 min, no tokens burned)
	BudgetSpend float64 `json:"budget_spend"`
	BudgetMax   float64 `json:"budget_max"`

	LLMCallsTotal    int      `json:"llm_calls_total"`
	LLMCallsToday    int      `json:"llm_calls_today"`
	LLMCostRubTotal  float64  `json:"llm_cost_rub_total"`
	LLMCostRubPerDAU *float64 `json:"llm_cost_rub_per_dau"`
	P50LatencyMs     *float64 `json:"p50_latency_ms"`
	P95LatencyMs     *float64 `json:"p95_latency_ms"`
	ErrorRatePct     *float64 `json:"error_rate_pct"`
	Uptime24hPct     *float64 `json:"uptime_24h_pct"`
}

// Metrics serves aggregated, anonymous numbers for the public metrics page.
// No per-user data leaves this handler. Results are cached for a minute.
type Metrics struct {
	db       *pgxpool.Pool
	devIDs   []string
	priceIn  float64 // ₽ per 1M prompt tokens
	priceOut float64 // ₽ per 1M completion tokens
	origin   string

	mu       sync.Mutex
	cached   *MetricsResponse
	cachedAt time.Time

	budgetMu       sync.Mutex
	budgetSpend    float64
	budgetMax      float64
	budgetCachedAt time.Time

	httpClient *http.Client
}

func NewMetrics(db *pgxpool.Pool, devIDs []string) *Metrics {
	origin := os.Getenv("METRICS_ALLOWED_ORIGIN")
	if origin == "" {
		origin = "*" // aggregate numbers only; tighten to the Pages origin if wanted
	}
	if devIDs == nil {
		devIDs = []string{}
	}
	return &Metrics{
		db:         db,
		devIDs:     devIDs,
		priceIn:    envFloat("LLM_PRICE_IN_PER_1M", 73.03),
		priceOut:   envFloat("LLM_PRICE_OUT_PER_1M", 176.39),
		origin:     origin,
		httpClient: &http.Client{Timeout: 5 * time.Second},
	}
}

func (m *Metrics) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", m.origin)
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Cache-Control", "no-store")

	m.mu.Lock()
	defer m.mu.Unlock()
	if m.cached == nil || time.Since(m.cachedAt) > time.Minute {
		ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
		defer cancel()
		resp, err := m.compute(ctx)
		if err != nil {
			slog.Error("metrics compute", "err", err)
			writeError(w, http.StatusInternalServerError, "metrics unavailable")
			return
		}
		m.cached, m.cachedAt = resp, time.Now()
	}
	writeJSON(w, http.StatusOK, m.cached)
}

func (m *Metrics) compute(ctx context.Context) (*MetricsResponse, error) {
	now := clock.Now()
	today := clock.Today()
	dayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, clock.Location())
	tz := clock.Location().String()
	devs := m.devIDs

	out := &MetricsResponse{UpdatedAt: now.Format(time.RFC3339), DAUHistory: []DAUPoint{}}

	// Growth
	if err := m.db.QueryRow(ctx, `
		SELECT count(*),
		       count(*) FILTER (WHERE (created_at AT TIME ZONE $2)::date = $3)
		FROM users WHERE NOT (id = ANY($1))
	`, devs, tz, today).Scan(&out.UsersTotal, &out.NewUsersToday); err != nil {
		return nil, err
	}

	rows, err := m.db.Query(ctx, `
		SELECT d::date, count(a.user_id)
		FROM generate_series($2::date - 6, $2::date, interval '1 day') d
		LEFT JOIN user_activity a ON a.date = d::date AND NOT (a.user_id = ANY($1))
		GROUP BY d ORDER BY d
	`, devs, today)
	if err != nil {
		return nil, err
	}
	sum := 0
	for rows.Next() {
		var d time.Time
		var n int
		if err := rows.Scan(&d, &n); err != nil {
			rows.Close()
			return nil, err
		}
		out.DAUHistory = append(out.DAUHistory, DAUPoint{Date: d.Format("2006-01-02"), DAU: n})
		sum += n
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out.DAUHistory) > 0 {
		out.DAUToday = out.DAUHistory[len(out.DAUHistory)-1].DAU
		out.DAU7dAvg = round(float64(sum)/float64(len(out.DAUHistory)), 1)
	}

	// Retention D1: users who signed up 1–6 days ago and had any activity after registration day
	var retD1, cohortD1 int
	if err := m.db.QueryRow(ctx, `
		SELECT count(*) FILTER (WHERE EXISTS (
		           SELECT 1 FROM user_activity a
		           WHERE a.user_id = u.id AND a.date > (u.created_at AT TIME ZONE $2)::date)),
		       count(*)
		FROM users u
		WHERE (u.created_at AT TIME ZONE $2)::date BETWEEN $3::date - 6 AND $3::date - 1
		  AND NOT (u.id = ANY($1))
	`, devs, tz, today).Scan(&retD1, &cohortD1); err != nil {
		return nil, err
	}
	out.RetentionD1Pct = pct(retD1, cohortD1)

	// Retention D7: users who signed up 7–13 days ago and came back on day 7 or later
	var retD7, cohortD7 int
	if err := m.db.QueryRow(ctx, `
		SELECT count(*) FILTER (WHERE EXISTS (
		           SELECT 1 FROM user_activity a
		           WHERE a.user_id = u.id AND a.date >= (u.created_at AT TIME ZONE $2)::date + 7)),
		       count(*)
		FROM users u
		WHERE (u.created_at AT TIME ZONE $2)::date BETWEEN $3::date - 13 AND $3::date - 7
		  AND NOT (u.id = ANY($1))
	`, devs, tz, today).Scan(&retD7, &cohortD7); err != nil {
		return nil, err
	}
	out.RetentionD7Pct = pct(retD7, cohortD7)

	// Retention funnel — absolute counts across all cohorts
	var activated int
	if err := m.db.QueryRow(ctx, `
		SELECT count(DISTINCT user_id) FROM user_activity WHERE NOT (user_id = ANY($1))
	`, devs).Scan(&activated); err != nil {
		return nil, err
	}
	out.FunnelTotal = out.UsersTotal
	out.FunnelActivated = activated
	out.FunnelD1 = retD1
	out.FunnelD7 = retD7
	out.FunnelDAU = out.DAUToday

	// Engagement
	var checkinsToday, callsToday int
	if err := m.db.QueryRow(ctx, `
		SELECT (SELECT count(*) FROM checkins WHERE date = $2 AND NOT (user_id = ANY($1))),
		       (SELECT count(*) FROM morning_messages WHERE date = $2 AND sent_at IS NOT NULL AND NOT (user_id = ANY($1))),
		       (SELECT count(*) FROM call_log WHERE ts >= $3),
		       (SELECT count(*) FROM call_log WHERE component = 'scenario_completed' AND ts >= $3),
		       (SELECT count(*) FROM call_log WHERE component = 'scenario_completed')
	`, devs, today, dayStart).Scan(
		&checkinsToday, &out.MorningDeliveredToday, &callsToday,
		&out.ScenarioCompletedToday, &out.ScenarioCompletedTotal,
	); err != nil {
		return nil, err
	}
	out.CheckinRatePct = pct(checkinsToday, out.DAUToday)
	out.CallsPerDAU = ratio(float64(callsToday), out.DAUToday, 1)

	// LLM cost (tokens are stored per morning message — only successful calls have tokens)
	var inTotal, outTotal, inToday, outToday int64
	if err := m.db.QueryRow(ctx, `
		SELECT coalesce(sum(prompt_tokens), 0), coalesce(sum(completion_tokens), 0),
		       coalesce(sum(prompt_tokens)    FILTER (WHERE created_at >= $2), 0),
		       coalesce(sum(completion_tokens) FILTER (WHERE created_at >= $2), 0)
		FROM (
		    SELECT user_id, prompt_tokens, completion_tokens, created_at FROM morning_messages
		    UNION ALL
		    SELECT user_id, prompt_tokens, completion_tokens, created_at FROM reviews
		) t WHERE NOT (user_id = ANY($1))
	`, devs, dayStart).Scan(&inTotal, &outTotal, &inToday, &outToday); err != nil {
		return nil, err
	}
	out.LLMCostRubTotal = round(m.cost(inTotal, outTotal), 2)
	out.LLMCostRubPerDAU = ratio(m.cost(inToday, outToday), out.DAUToday, 3)

	// LLM calls today — count from call_log so errored calls are included
	if err := m.db.QueryRow(ctx, `
		SELECT count(*) FROM call_log
		WHERE call_type = 'llm' AND ts >= $1 AND NOT (user_id = ANY($2))
	`, dayStart, devs).Scan(&out.LLMCallsToday); err != nil {
		return nil, err
	}

	// LLM quality; error window starts from the last admin reset or 24 h ago, whichever is later.
	var p50, p95 *float64
	var llmErr, llm24 int
	if err := m.db.QueryRow(ctx, `
		SELECT (SELECT count(*) FROM call_log WHERE call_type = 'llm'),
		       percentile_cont(0.5)  WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE result = 'ok'),
		       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE result = 'ok'),
		       count(*) FILTER (WHERE result = 'error'),
		       count(*)
		FROM call_log
		WHERE call_type = 'llm' AND ts >= GREATEST(
		    now() - interval '24 hours',
		    COALESCE((SELECT max(ts) FROM call_log WHERE component = 'errors_reset'), now() - interval '24 hours')
		)
	`).Scan(&out.LLMCallsTotal, &p50, &p95, &llmErr, &llm24); err != nil {
		return nil, err
	}
	out.P50LatencyMs, out.P95LatencyMs = roundPtr(p50), roundPtr(p95)
	out.ErrorRatePct = pct(llmErr, llm24)

	// Realtime LLM budget from proxy (cached 15 min, no tokens burned).
	out.BudgetSpend, out.BudgetMax = m.budget(ctx)

	// Uptime: minutes with a heartbeat / minutes observed in the window
	var beats int
	var first *time.Time
	if err := m.db.QueryRow(ctx, `
		SELECT count(*), min(minute) FROM heartbeats WHERE minute >= now() - interval '24 hours'
	`).Scan(&beats, &first); err != nil {
		return nil, err
	}
	if first != nil {
		window := math.Min(1440, math.Max(1, time.Since(*first).Minutes()+1))
		v := round(math.Min(100, float64(beats)/window*100), 1)
		out.Uptime24hPct = &v
	}

	return out, nil
}

// budget returns (spend, max) from the LLM proxy key/info, cached for 15 minutes.
// Returns (0, 0) on error so the metrics page shows "--" gracefully.
func (m *Metrics) budget(ctx context.Context) (spend, max float64) {
	m.budgetMu.Lock()
	defer m.budgetMu.Unlock()
	if time.Since(m.budgetCachedAt) < 15*time.Minute {
		return m.budgetSpend, m.budgetMax
	}
	baseURL := os.Getenv("LLM_BASE_URL")
	if baseURL == "" {
		baseURL = "https://shared1.multitool.works:4000/v1"
	}
	// Strip trailing /v1 to get the proxy root.
	proxyRoot := baseURL
	if len(proxyRoot) > 3 && proxyRoot[len(proxyRoot)-3:] == "/v1" {
		proxyRoot = proxyRoot[:len(proxyRoot)-3]
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, proxyRoot+"/key/info", nil)
	if err != nil {
		return 0, 0
	}
	req.Header.Set("Authorization", "Bearer "+os.Getenv("LLM_API_KEY"))
	resp, err := m.httpClient.Do(req)
	if err != nil {
		slog.Warn("budget fetch failed", "err", err)
		return 0, 0
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	var payload struct {
		Info struct {
			Spend     float64 `json:"spend"`
			MaxBudget float64 `json:"max_budget"`
		} `json:"info"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return 0, 0
	}
	m.budgetSpend, m.budgetMax, m.budgetCachedAt = payload.Info.Spend, payload.Info.MaxBudget, time.Now()
	return m.budgetSpend, m.budgetMax
}

func (m *Metrics) cost(in, out int64) float64 {
	return float64(in)/1e6*m.priceIn + float64(out)/1e6*m.priceOut
}

func pct(part, whole int) *float64 {
	if whole == 0 {
		return nil
	}
	v := round(float64(part)/float64(whole)*100, 1)
	return &v
}

func ratio(num float64, den int, digits int) *float64 {
	if den == 0 {
		return nil
	}
	v := round(num/float64(den), digits)
	return &v
}

func round(v float64, digits int) float64 {
	p := math.Pow(10, float64(digits))
	return math.Round(v*p) / p
}

func roundPtr(v *float64) *float64 {
	if v == nil {
		return nil
	}
	r := math.Round(*v)
	return &r
}

func envFloat(key string, def float64) float64 {
	if v, err := strconv.ParseFloat(os.Getenv(key), 64); err == nil {
		return v
	}
	return def
}
