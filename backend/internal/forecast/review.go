package forecast

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

// ErrBadDate: a day review was asked for the future or too far back.
var ErrBadDate = errors.New("date out of range")

const (
	reviewMaxBack = 30        // days back a day review may look
	todayRefresh  = time.Hour // a review of today is refreshed at most hourly
	weeklyMinDays = 3         // fewer days make a summary meaningless
)

// Limits caps one on-demand text: model tokens and characters after trimming.
type Limits struct{ Tokens, Chars int }

var (
	DayReviewLimits = Limits{Tokens: 260, Chars: 480} // ≈450 characters in Russian
	WeeklyLimits    = Limits{Tokens: 380, Chars: 700} // ≈650 characters
)

// DaySignals explains the last day in `days` (oldest first) for this person.
func DaySignals(days []*repo.DailyData, profile map[string]string) []signals.Signal {
	return signals.ForLastDay(days, WorkApps(profile), llm.AppLabel)
}

// DayReviewPrompt builds the user prompt for «Разбор дня»; days end with the reviewed day.
func DayReviewPrompt(days []*repo.DailyData, sig []signals.Signal, checkin *repo.CheckIn, profile map[string]string, partial bool) string {
	return llm.BuildDayReview(llm.DayReviewInput{
		Day: days[len(days)-1], Usual: days[:len(days)-1], CheckIn: checkin,
		Signals: sig, Profile: profile, Partial: partial,
	})
}

// WeeklyPrompt builds the user prompt for «Итоги недели» with per-day signals.
func WeeklyPrompt(days []*repo.DailyData, checkins map[string]*repo.CheckIn, profile map[string]string) string {
	perDay := map[string][]signals.Signal{}
	for i := range days {
		perDay[days[i].Date.Format("2006-01-02")] = DaySignals(days[:i+1], profile)
	}
	return llm.BuildWeekly(llm.WeeklyInput{Days: days, CheckIns: checkins, Signals: perDay, Profile: profile})
}

// DayReview explains one day on request (cached; today's is refreshed at most hourly).
func (g *Generator) DayReview(ctx context.Context, userID string, date time.Time) (*repo.Review, []signals.Signal, error) {
	today := clock.Today()
	if date.After(today) || date.Before(today.AddDate(0, 0, -reviewMaxBack)) {
		return nil, nil, ErrBadDate
	}
	unlock := g.lockUser(userID)
	defer unlock()

	days, err := g.daily.LastN(ctx, userID, historyDays+1, date)
	if err != nil {
		return nil, nil, err
	}
	if len(days) == 0 || !days[len(days)-1].Date.Equal(date) {
		return nil, nil, ErrNoData
	}
	profile, err := g.users.Profile(ctx, userID)
	if err != nil {
		return nil, nil, err
	}
	sig := DaySignals(days, profile)

	cached, err := g.reviews.Get(ctx, userID, "day", date)
	if err != nil {
		return nil, nil, err
	}
	if cached != nil && (date.Before(today) || time.Since(cached.CreatedAt) < todayRefresh) {
		return cached, sig, nil
	}

	checkins, err := g.checkin.Range(ctx, userID, date, date)
	if err != nil {
		return nil, nil, err
	}
	user := DayReviewPrompt(days, sig, checkins[date.Format("2006-01-02")], profile, date.Equal(today))
	rv, err := g.complete(ctx, userID, prompts.DayReviewSystem, analytics.ComponentLLMDay, "day", date, user, DayReviewLimits)
	return rv, sig, err
}

// WeeklyReview sums up the last 7 days (up to yesterday) on request; one per day.
func (g *Generator) WeeklyReview(ctx context.Context, userID string) (*repo.Review, error) {
	today := clock.Today()
	unlock := g.lockUser(userID)
	defer unlock()

	if cached, err := g.reviews.Get(ctx, userID, "week", today); err != nil || cached != nil {
		return cached, err
	}
	days, err := g.daily.LastN(ctx, userID, 7, today.AddDate(0, 0, -1))
	if err != nil {
		return nil, err
	}
	if len(days) < weeklyMinDays {
		return nil, ErrNoData
	}
	profile, err := g.users.Profile(ctx, userID)
	if err != nil {
		return nil, err
	}
	checkins, err := g.checkin.Range(ctx, userID, days[0].Date, days[len(days)-1].Date)
	if err != nil {
		return nil, err
	}
	user := WeeklyPrompt(days, checkins, profile)
	return g.complete(ctx, userID, prompts.WeeklySystem, analytics.ComponentLLMWeekly, "week", today, user, WeeklyLimits)
}

// complete runs one on-demand LLM text: budget → prompt → call → safety → log → save.
func (g *Generator) complete(ctx context.Context, userID, promptName string, component analytics.Component,
	kind string, date time.Time, user string, lim Limits) (*repo.Review, error) {
	if ok, err := g.withinBudget(ctx); err != nil {
		return nil, err
	} else if !ok {
		slog.Warn("review: daily LLM cap reached", "cap", g.dailyCap, "user", userID, "kind", kind)
		return nil, ErrBudget
	}
	system, version := g.prompts.Active(ctx, promptName)

	lctx, cancel := context.WithTimeout(ctx, llmTimeout)
	defer cancel()
	start := time.Now()
	res, err := g.llm.Complete(lctx, system, user, lim.Tokens, lim.Chars)
	if err == nil && !SafeOutput(res.Message) {
		slog.Error("review: unsafe model output rejected", "user", userID, "kind", kind)
		err = ErrUnsafeOutput
	}
	event := analytics.CallEvent{
		UserID: userID, Timestamp: start, CallType: analytics.CallTypeLLM, Component: component,
		Trigger: analytics.TriggerUserAction, UserVisible: true, Result: "ok", LatencyMs: time.Since(start).Milliseconds(),
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
		return nil, err
	}
	g.callLog.Log(ctx, event)

	rv := &repo.Review{
		UserID: userID, Kind: kind, Date: date, Text: res.Message,
		PromptTokens: res.PromptTokens, CompletionTokens: res.CompletionTokens, LatencyMs: res.LatencyMs,
		Model: g.llm.Model(), PromptVersion: version, CreatedAt: time.Now(),
	}
	if err := g.reviews.Save(ctx, rv); err != nil {
		return nil, err
	}
	return rv, nil
}

func (g *Generator) lockUser(userID string) func() {
	mu, _ := g.locks.LoadOrStore(userID, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	return mu.(*sync.Mutex).Unlock
}
