// Package admin serves prompt management on a separate, local-only port.
//
// Security model: the listener binds to 127.0.0.1 (ADMIN_ADDR), Caddy never proxies it,
// so it is reachable only from the server itself — `ssh` + curl, or deploy/prompt.sh.
// ADMIN_TOKEN is a second lock in case the port is ever exposed by mistake.
// Every change is announced in Telegram, so a change you didn't make is noticed.
package admin

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/mock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

type Server struct {
	token    string
	store    *prompts.Store
	daily    *repo.DailyRepo
	checkin  *repo.CheckInRepo
	users    *repo.UserRepo
	llm      *llm.Client
	callLog  *analytics.Logger
	feedback *repo.FeedbackRepo
	devIDs   []string
	notify   *telegram
}

func New(db *pgxpool.Pool, store *prompts.Store, llmClient *llm.Client, callLog *analytics.Logger, devIDs []string) *Server {
	return &Server{
		token:    os.Getenv("ADMIN_TOKEN"),
		store:    store,
		daily:    repo.NewDailyRepo(db),
		users:    repo.NewUserRepo(db),
		checkin:  repo.NewCheckInRepo(db),
		llm:      llmClient,
		callLog:  callLog,
		feedback: repo.NewFeedbackRepo(db),
		devIDs:   devIDs,
		notify:   newTelegram(),
	}
}

// Start listens on ADMIN_ADDR (default 127.0.0.1:9090). Disabled without ADMIN_TOKEN.
func (s *Server) Start(ctx context.Context) {
	if len(s.token) < 32 {
		slog.Warn("admin: disabled (ADMIN_TOKEN missing or shorter than 32 chars)")
		return
	}
	addr := os.Getenv("ADMIN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:9090"
	}
	if host, _, _ := strings.Cut(addr, ":"); host != "127.0.0.1" && host != "localhost" {
		slog.Warn("admin: ADMIN_ADDR is not loopback — make sure the firewall blocks it", "addr", addr)
	}

	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(s.auth)
	r.Get("/admin/prompts/{name}", s.list)
	r.Post("/admin/prompts/{name}", s.create)
	r.Post("/admin/prompts/{name}/activate/{id}", s.activate)
	r.Post("/admin/prompts/{name}/reset", s.reset)
	r.Post("/admin/prompts/{name}/preview", s.preview)
	r.Post("/admin/prompts/{name}/eval", s.eval)
	r.Post("/admin/mock/seed", s.seedMock)
	r.Post("/admin/mock/purge", s.purgeMock)
	r.Get("/admin/accuracy", s.accuracy)
	r.Post("/admin/reset-errors", s.resetErrors)

	srv := &http.Server{Addr: addr, Handler: r, ReadTimeout: 10 * time.Second, WriteTimeout: 150 * time.Second} // eval runs several LLM calls
	go func() {
		slog.Info("admin: listening", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("admin: server error", "err", err)
		}
	}()
	go func() {
		<-ctx.Done()
		shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shut)
	}()
}

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if subtle.ConstantTimeCompare([]byte(got), []byte(s.token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		next.ServeHTTP(w, r)
	})
}

// knownName limits the API to prompts the code actually uses.
func knownName(name string) bool {
	return name == prompts.MorningSystem || name == prompts.DayReviewSystem || name == prompts.WeeklySystem
}

type listResponse struct {
	Name          string           `json:"name"`
	ActiveVersion int              `json:"active_version"` // 0 = built-in default
	Default       string           `json:"default"`
	Versions      []prompts.Prompt `json:"versions"`
}

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if !knownName(name) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown prompt"})
		return
	}
	versions, err := s.store.List(r.Context(), name)
	if err != nil {
		s.fail(w, err)
		return
	}
	_, active := s.store.Active(r.Context(), name)
	writeJSON(w, http.StatusOK, listResponse{Name: name, ActiveVersion: active, Default: s.store.Default(name), Versions: versions})
}

type createRequest struct {
	Body     string `json:"body"`
	Note     string `json:"note"`
	Activate bool   `json:"activate"`
}

func (s *Server) create(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	var req createRequest
	if !knownName(name) || json.NewDecoder(r.Body).Decode(&req) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unknown prompt or invalid json"})
		return
	}
	req.Body = strings.TrimSpace(req.Body)
	if req.Body == "" || len([]rune(req.Body)) > prompts.MaxBodyLen {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": fmt.Sprintf("body must be 1..%d chars", prompts.MaxBodyLen)})
		return
	}
	p, err := s.store.Create(r.Context(), name, req.Body, req.Note, req.Activate)
	if err != nil {
		s.fail(w, err)
		return
	}
	state := "сохранена (не активна)"
	if p.IsActive {
		state = "сохранена и активирована"
	}
	s.announce(fmt.Sprintf("Промпт %s v%d %s. %s", name, p.Version, state, req.Note))
	writeJSON(w, http.StatusCreated, p)
}

func (s *Server) activate(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil || !knownName(name) {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad id or name"})
		return
	}
	p, err := s.store.Activate(r.Context(), id)
	if errors.Is(err, prompts.ErrNotFound) || (err == nil && p.Name != name) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no such version"})
		return
	}
	if err != nil {
		s.fail(w, err)
		return
	}
	s.announce(fmt.Sprintf("Промпт %s: активна версия v%d", name, p.Version))
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) reset(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if !knownName(name) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown prompt"})
		return
	}
	if err := s.store.Deactivate(r.Context(), name); err != nil {
		s.fail(w, err)
		return
	}
	s.announce(fmt.Sprintf("Промпт %s: возврат к встроенной версии", name))
	writeJSON(w, http.StatusOK, map[string]string{"status": "default"})
}

type previewRequest struct {
	UserID  string `json:"user_id"`
	Body    string `json:"body"`     // draft system prompt; empty = the active one
	CallLLM bool   `json:"call_llm"` // false = only render the prompts (free)
}

type previewResponse struct {
	UserID  string           `json:"user_id,omitempty"`
	Title   string           `json:"title,omitempty"` // mock persona: the situation it tests
	Truth   string           `json:"truth,omitempty"` // mock persona: what really happened yesterday
	System  string           `json:"system,omitempty"`
	User    string           `json:"user,omitempty"`
	Signals []signals.Signal `json:"signals"`
	Output  string           `json:"output,omitempty"`
	Chars   int              `json:"chars,omitempty"`
	Safe    *bool            `json:"safe,omitempty"`
	Tokens  int              `json:"tokens,omitempty"`
	Latency int              `json:"latency_ms,omitempty"`
	Error   string           `json:"error,omitempty"`
}

// rendered is one prompt ready to send: what the model would get for this user this morning.
type rendered struct {
	user    string
	signals []signals.Signal
	limits  forecast.Limits
	morning *llm.MorningInput // morning goes through GenerateMorning (same trimming as production)
}

// render builds the user prompt of `name` for a user's real data, as of today:
// the morning forecast and the day review look at yesterday, the weekly one at the last 7 days.
func (s *Server) render(ctx context.Context, name, userID string) (*rendered, error) {
	yesterday := clock.Today().AddDate(0, 0, -1)
	days, err := s.daily.LastN(ctx, userID, 8, yesterday)
	if err != nil {
		return nil, err
	}
	if len(days) == 0 {
		return nil, forecast.ErrNoData
	}
	profile, err := s.users.Profile(ctx, userID)
	if err != nil {
		return nil, err
	}
	sig := forecast.DaySignals(days, profile)
	switch name {
	case prompts.MorningSystem:
		week := days
		if len(week) > 7 {
			week = week[len(week)-7:]
		}
		last, err := s.checkin.Latest(ctx, userID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}
		in := llm.MorningInput{Days: week, Last: last, Profile: profile, Signals: sig, First: len(days) <= 2}
		return &rendered{user: llm.BuildMorningPrompt(in), signals: sig, morning: &in}, nil
	case prompts.DayReviewSystem:
		checkins, err := s.checkin.Range(ctx, userID, yesterday, yesterday)
		if err != nil {
			return nil, err
		}
		user := forecast.DayReviewPrompt(days, sig, checkins[yesterday.Format("2006-01-02")], profile, false)
		return &rendered{user: user, signals: sig, limits: forecast.DayReviewLimits}, nil
	default: // weekly
		week := days
		if len(week) > 7 {
			week = week[len(week)-7:]
		}
		checkins, err := s.checkin.Range(ctx, userID, week[0].Date, yesterday)
		if err != nil {
			return nil, err
		}
		return &rendered{user: forecast.WeeklyPrompt(week, checkins, profile), signals: []signals.Signal{}, limits: forecast.WeeklyLimits}, nil
	}
}

// run sends a rendered prompt once. Nothing is stored; the call goes to call_log as a
// non-user-visible admin call (anti-fraud log stays complete).
func (s *Server) run(ctx context.Context, name, system string, rd *rendered, out *previewResponse) {
	ctx, cancel := context.WithTimeout(ctx, 40*time.Second)
	defer cancel()
	start := time.Now()
	var res *llm.GenerateResult
	var err error
	component := analytics.ComponentLLMMorning
	switch name {
	case prompts.MorningSystem:
		res, err = s.llm.GenerateMorning(ctx, system, *rd.morning)
	case prompts.DayReviewSystem:
		component = analytics.ComponentLLMDay
		res, err = s.llm.Complete(ctx, system, rd.user, rd.limits.Tokens, rd.limits.Chars)
	default:
		component = analytics.ComponentLLMWeekly
		res, err = s.llm.Complete(ctx, system, rd.user, rd.limits.Tokens, rd.limits.Chars)
	}
	event := analytics.CallEvent{
		UserID: "admin", Timestamp: start, CallType: analytics.CallTypeLLM,
		Component: component, Trigger: analytics.TriggerUserAction,
		UserVisible: false, Result: "ok", LatencyMs: time.Since(start).Milliseconds(),
	}
	if err != nil {
		code := "llm_error"
		event.Result, event.ErrorCode = "error", &code
		s.callLog.Log(ctx, event)
		out.Error = err.Error()
		return
	}
	s.callLog.Log(ctx, event)
	safe := forecast.SafeOutput(res.Message)
	out.Output, out.Safe, out.Chars = res.Message, &safe, len([]rune(res.Message))
	out.Tokens, out.Latency = res.PromptTokens+res.CompletionTokens, res.LatencyMs
}

// systemFor returns the draft body if given, else the active version.
func (s *Server) systemFor(ctx context.Context, name, draft string) string {
	if d := strings.TrimSpace(draft); d != "" {
		return d
	}
	system, _ := s.store.Active(ctx, name)
	return system
}

// preview renders a prompt for one real user's data and, if asked, runs the model once.
func (s *Server) preview(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	var req previewRequest
	if !knownName(name) || json.NewDecoder(r.Body).Decode(&req) != nil || req.UserID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "need a known prompt and user_id"})
		return
	}
	rd, err := s.render(r.Context(), name, req.UserID)
	if errors.Is(err, forecast.ErrNoData) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no data for this user"})
		return
	}
	if err != nil {
		s.fail(w, err)
		return
	}
	system := s.systemFor(r.Context(), name, req.Body)
	resp := previewResponse{UserID: req.UserID, System: system, User: rd.user, Signals: rd.signals}
	if req.CallLLM {
		s.run(r.Context(), name, system, rd, &resp)
	}
	writeJSON(w, http.StatusOK, resp)
}

type evalRequest struct {
	Body       string `json:"body"`        // draft system prompt; empty = the active one
	CallLLM    bool   `json:"call_llm"`    // false = only render (free)
	WithPrompt bool   `json:"with_prompt"` // include the rendered user prompt in the answer
}

// eval runs one prompt over every mock persona (seed them first) — a quick regression check
// of a prompt change on known situations. 6 personas ≈ 6 LLM calls, run 3 at a time.
func (s *Server) eval(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	var req evalRequest
	if !knownName(name) || json.NewDecoder(r.Body).Decode(&req) != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unknown prompt or invalid json"})
		return
	}
	personas, err := mock.Personas()
	if err != nil {
		s.fail(w, err)
		return
	}
	system := s.systemFor(r.Context(), name, req.Body)
	results := make([]previewResponse, len(personas))
	sem := make(chan struct{}, 3)
	var wg sync.WaitGroup
	for i, p := range personas {
		results[i] = previewResponse{UserID: p.ID, Title: p.Title, Truth: p.Truth()}
		rd, err := s.render(r.Context(), name, p.ID)
		if err != nil {
			results[i].Error = "not seeded? " + err.Error()
			continue
		}
		results[i].Signals = rd.signals
		if req.WithPrompt {
			results[i].User = rd.user
		}
		if !req.CallLLM {
			continue
		}
		wg.Add(1)
		go func(out *previewResponse, rd *rendered) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			s.run(r.Context(), name, system, rd, out)
		}(&results[i], rd)
	}
	wg.Wait()
	writeJSON(w, http.StatusOK, map[string]any{"prompt": name, "draft": strings.TrimSpace(req.Body) != "", "results": results})
}

// seedMock (re)writes the mock personas with dates ending yesterday.
func (s *Server) seedMock(w http.ResponseWriter, r *http.Request) {
	personas, err := mock.Seed(r.Context(), s.daily.DB(), clock.Today())
	if err != nil {
		s.fail(w, err)
		return
	}
	type row struct {
		ID    string `json:"id"`
		Title string `json:"title"`
		Days  int    `json:"days"`
	}
	out := make([]row, 0, len(personas))
	for _, p := range personas {
		out = append(out, row{p.ID, p.Title, len(p.Days)})
	}
	writeJSON(w, http.StatusOK, map[string]any{"seeded": out})
}

func (s *Server) purgeMock(w http.ResponseWriter, r *http.Request) {
	if err := mock.Purge(r.Context(), s.daily.DB()); err != nil {
		s.fail(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "purged"})
}

// accuracy: «Совпало / Не совсем» by prompt version — did the new prompt do better?
func (s *Server) accuracy(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days <= 0 || days > 365 {
		days = 30
	}
	rows, err := s.feedback.AccuracyByVersion(r.Context(), time.Now().AddDate(0, 0, -days), s.devIDs)
	if err != nil {
		s.fail(w, err)
		return
	}
	if rows == nil {
		rows = []repo.Accuracy{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"days": days, "by_version": rows})
}

// resetErrors inserts an errors_reset marker into call_log so the metrics
// error rate window starts fresh from this moment.
func (s *Server) resetErrors(w http.ResponseWriter, r *http.Request) {
	_, err := s.daily.DB().Exec(r.Context(), `
		INSERT INTO call_log (user_id, ts, call_type, component, trigger, user_visible, result, latency_ms)
		VALUES ('admin', NOW(), 'tool', 'errors_reset', 'user_action', false, 'ok', 0)
	`)
	if err != nil {
		s.fail(w, err)
		return
	}
	s.announce("Счётчик ошибок сброшен")
	writeJSON(w, http.StatusOK, map[string]string{"status": "reset", "ts": clock.Now().Format(time.RFC3339)})
}

func (s *Server) announce(text string) {
	slog.Info("admin: " + text)
	s.notify.send("🛠 " + text)
}

func (s *Server) fail(w http.ResponseWriter, err error) {
	slog.Error("admin", "err", err)
	writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

// telegram posts admin events to the dev chat; no-op without TG_BOT_TOKEN / TG_CHAT_ID.
type telegram struct {
	token, chat string
	client      *http.Client
}

func newTelegram() *telegram {
	return &telegram{token: os.Getenv("TG_BOT_TOKEN"), chat: os.Getenv("TG_CHAT_ID"), client: &http.Client{Timeout: 5 * time.Second}}
}

func (t *telegram) send(text string) {
	if t.token == "" || t.chat == "" {
		return
	}
	go func() {
		resp, err := t.client.PostForm("https://api.telegram.org/bot"+t.token+"/sendMessage",
			url.Values{"chat_id": {t.chat}, "text": {text}})
		if err != nil {
			slog.Warn("admin: telegram notify failed", "err", err)
			return
		}
		resp.Body.Close()
	}()
}
