// Package forecast produces the morning message for a user and date.
// Used by the nightly scheduler (pre-generation) and by GET /morning (on demand),
// so a user whose night run failed or who had no data at 04:10 still gets a forecast.
package forecast

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"regexp"
	"strconv"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

var (
	// ErrNoData: nothing collected for the days before `date` yet.
	ErrNoData = errors.New("no data for forecast")
	// ErrBudget: the daily LLM call cap is reached.
	ErrBudget = errors.New("daily llm budget reached")
	// ErrUnsafeOutput: the model answered with a link or a phone number.
	ErrUnsafeOutput = errors.New("unsafe llm output")
)

// Links, messenger handles and phone numbers never belong in a forecast.
var unsafeOutput = regexp.MustCompile(`(?i)(https?://|www\.|t\.me/|@[a-z0-9_]{4,}|\.(ru|com|net|org|рф)\b|\+?\d[\d\s()-]{8,}\d)`)

// SafeOutput reports whether a model answer can be shown to the user.
func SafeOutput(s string) bool { return !unsafeOutput.MatchString(s) }

const (
	historyDays = 7
	llmTimeout  = 25 * time.Second
)

type Generator struct {
	db       *pgxpool.Pool
	daily    *repo.DailyRepo
	checkin  *repo.CheckInRepo
	morning  *repo.MorningRepo
	llm      *llm.Client
	callLog  *analytics.Logger
	prompts  *prompts.Store
	dailyCap int

	locks sync.Map // userID → *sync.Mutex: one generation per user at a time
}

func NewGenerator(db *pgxpool.Pool, daily *repo.DailyRepo, checkin *repo.CheckInRepo,
	morning *repo.MorningRepo, llmClient *llm.Client, callLog *analytics.Logger, store *prompts.Store) *Generator {
	dailyCap, err := strconv.Atoi(os.Getenv("LLM_DAILY_CAP"))
	if err != nil || dailyCap <= 0 {
		dailyCap = 300
	}
	return &Generator{db: db, daily: daily, checkin: checkin, morning: morning, llm: llmClient, callLog: callLog, prompts: store, dailyCap: dailyCap}
}

// Ensure returns the message for (user, date), generating it if missing.
// The forecast for `date` is built from the days before it (yesterday and earlier).
func (g *Generator) Ensure(ctx context.Context, userID string, date time.Time, trigger analytics.Trigger) (*repo.MorningMessage, error) {
	mu, _ := g.locks.LoadOrStore(userID, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()

	if msg, err := g.morning.ForDate(ctx, userID, date); err != nil || msg != nil {
		return msg, err
	}

	days, err := g.daily.LastN(ctx, userID, historyDays, date.AddDate(0, 0, -1))
	if err != nil {
		return nil, err
	}
	if len(days) == 0 {
		return nil, ErrNoData
	}

	if ok, err := g.withinBudget(ctx); err != nil {
		return nil, err
	} else if !ok {
		slog.Warn("forecast: daily LLM cap reached", "cap", g.dailyCap, "user", userID)
		return nil, ErrBudget
	}

	last, err := g.checkin.Latest(ctx, userID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	system, promptVersion := g.prompts.Active(ctx, prompts.MorningSystem)

	lctx, cancel := context.WithTimeout(ctx, llmTimeout)
	defer cancel()
	start := time.Now()
	result, err := g.llm.GenerateMorning(lctx, system, days, last)
	if err == nil && !SafeOutput(result.Message) {
		// A tampered prompt or a model slip must not put links/phones in front of users
		slog.Error("forecast: unsafe model output rejected", "user", userID, "prompt_version", promptVersion)
		err = ErrUnsafeOutput
	}

	event := analytics.CallEvent{
		UserID:      userID,
		Timestamp:   start,
		CallType:    analytics.CallTypeLLM,
		Component:   analytics.ComponentLLMMorning,
		Trigger:     trigger,
		UserVisible: true,
		Result:      "ok",
		LatencyMs:   time.Since(start).Milliseconds(),
	}
	if err != nil {
		code := "llm_error"
		switch {
		case errors.Is(err, context.DeadlineExceeded):
			code = "timeout"
		case errors.Is(err, ErrUnsafeOutput):
			code = "unsafe_output"
		}
		event.Result, event.ErrorCode = "error", &code
		g.callLog.Log(ctx, event)
		// Store an empty placeholder so we don't retry the LLM for the rest of the day.
		// ON CONFLICT DO NOTHING semantics: if Insert fails (race or prior attempt), ignore.
		_ = g.morning.Insert(ctx, &repo.MorningMessage{UserID: userID, Date: date, Message: ""})
		return nil, err
	}
	g.callLog.Log(ctx, event)

	msg := &repo.MorningMessage{
		UserID:           userID,
		Date:             date,
		Message:          result.Message,
		PromptTokens:     result.PromptTokens,
		CompletionTokens: result.CompletionTokens,
		LatencyMs:        result.LatencyMs,
		Model:            g.llm.Model(),
		PromptVersion:    promptVersion,
	}
	if err := g.morning.Insert(ctx, msg); err != nil {
		return nil, err
	}
	return msg, nil
}

// withinBudget counts today's generated messages (product time zone) against LLM_DAILY_CAP.
// It reads morning_messages, written synchronously, not the async call_log — so a burst of
// requests can't slip past the cap before the log catches up.
func (g *Generator) withinBudget(ctx context.Context) (bool, error) {
	now := clock.Now()
	dayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, clock.Location())
	var n int
	err := g.db.QueryRow(ctx,
		`SELECT count(*) FROM morning_messages WHERE created_at >= $1`, dayStart,
	).Scan(&n)
	return n < g.dailyCap, err
}
