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
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type Server struct {
	token   string
	store   *prompts.Store
	daily   *repo.DailyRepo
	checkin *repo.CheckInRepo
	users   *repo.UserRepo
	llm     *llm.Client
	callLog *analytics.Logger
	notify  *telegram
}

func New(db *pgxpool.Pool, store *prompts.Store, llmClient *llm.Client, callLog *analytics.Logger) *Server {
	return &Server{
		token:   os.Getenv("ADMIN_TOKEN"),
		store:   store,
		daily:   repo.NewDailyRepo(db),
		users:   repo.NewUserRepo(db),
		checkin: repo.NewCheckInRepo(db),
		llm:     llmClient,
		callLog: callLog,
		notify:  newTelegram(),
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
	r.Post("/admin/reset-errors", s.resetErrors)

	srv := &http.Server{Addr: addr, Handler: r, ReadTimeout: 10 * time.Second, WriteTimeout: 60 * time.Second}
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
func knownName(name string) bool { return name == prompts.MorningSystem }

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
	System  string `json:"system"`
	User    string `json:"user"`
	Output  string `json:"output,omitempty"`
	Safe    *bool  `json:"safe,omitempty"`
	Tokens  int    `json:"tokens,omitempty"`
	Latency int    `json:"latency_ms,omitempty"`
}

// preview renders the prompt for a real user's data and, if asked, runs the model once.
// Nothing is stored; the LLM call is logged to call_log as a non-user-visible admin call.
func (s *Server) preview(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	var req previewRequest
	if !knownName(name) || json.NewDecoder(r.Body).Decode(&req) != nil || req.UserID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "need user_id"})
		return
	}
	system := strings.TrimSpace(req.Body)
	if system == "" {
		system, _ = s.store.Active(r.Context(), name)
	}
	days, err := s.daily.LastN(r.Context(), req.UserID, 7, clock.Today().AddDate(0, 0, -1))
	if err != nil {
		s.fail(w, err)
		return
	}
	if len(days) == 0 {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "no data for this user"})
		return
	}
	last, err := s.checkin.Latest(r.Context(), req.UserID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		s.fail(w, err)
		return
	}
	profile, err := s.users.Profile(r.Context(), req.UserID)
	if err != nil {
		s.fail(w, err)
		return
	}
	input := llm.MorningInput{Days: days, Last: last, Profile: profile}
	resp := previewResponse{System: system, User: llm.BuildMorningPrompt(input)}
	if req.CallLLM {
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		start := time.Now()
		res, err := s.llm.GenerateMorning(ctx, system, input)
		event := analytics.CallEvent{
			UserID: "admin", Timestamp: start, CallType: analytics.CallTypeLLM,
			Component: analytics.ComponentLLMMorning, Trigger: analytics.TriggerUserAction,
			UserVisible: false, Result: "ok", LatencyMs: time.Since(start).Milliseconds(),
		}
		if err != nil {
			event.Result = "error"
			s.callLog.Log(r.Context(), event)
			s.fail(w, err)
			return
		}
		s.callLog.Log(r.Context(), event)
		safe := forecast.SafeOutput(res.Message)
		resp.Output, resp.Safe = res.Message, &safe
		resp.Tokens, resp.Latency = res.PromptTokens+res.CompletionTokens, res.LatencyMs
	}
	writeJSON(w, http.StatusOK, resp)
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
