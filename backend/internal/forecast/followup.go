package forecast

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// ErrBadQuestion: the chip does not exist, or does not belong to this kind of insight.
var ErrBadQuestion = errors.New("unknown follow-up question")

// FollowupLimits is deliberately tighter than a review: this answers one detail, and a long
// reply would bury the insight it hangs under.
var FollowupLimits = Limits{Tokens: 200, Chars: 380}

// FollowupDailyLimit caps new follow-up answers per person per day.
//
// The catalogue alone bounds a single day to ten, but follow-ups are keyed by the insight's
// date, so without this someone could walk back through a month of day reviews and spend a
// call per question per day. Cached taps do not count — only answers that cost a call.
const FollowupDailyLimit = 12

// FollowupQuestion is one chip under an insight.
//
// Unlike «Хочу ещё», these need no data thresholds: a chip is only ever shown under an insight
// that already exists, so the data behind it exists by construction. That is the whole point of
// hanging them off insights instead of offering them standalone.
type FollowupQuestion struct {
	ID    string   `json:"id"`
	Label string   `json:"label"`
	Kinds []string `json:"-"` // insight kinds this chip appears under
}

// FollowupQuestions is the whole catalogue; the app asks by ID, never by free text, so the
// prompt surface stays closed.
var FollowupQuestions = []FollowupQuestion{
	{ID: "why", Label: "Почему ты так решил?", Kinds: []string{"morning", "day", "week"}},
	{ID: "context", Label: "С чем это связано?", Kinds: []string{"morning", "day", "week"}},
	{ID: "today", Label: "Что тогда сегодня?", Kinds: []string{"morning"}},
	{ID: "prev_days", Label: "Это из-за прошлых дней?", Kinds: []string{"day"}},
	{ID: "similar", Label: "Как обычно проходят такие дни?", Kinds: []string{"day"}},
	{ID: "next_week", Label: "Что со следующей неделей?", Kinds: []string{"week"}},
}

// FollowupsFor lists the chips offered under one kind of insight.
func FollowupsFor(kind string) []FollowupQuestion {
	out := []FollowupQuestion{}
	for _, q := range FollowupQuestions {
		for _, k := range q.Kinds {
			if k == kind {
				out = append(out, q)
				break
			}
		}
	}
	return out
}

func findFollowup(kind, id string) (FollowupQuestion, bool) {
	for _, q := range FollowupsFor(kind) {
		if q.ID == id {
			return q, true
		}
	}
	return FollowupQuestion{}, false
}

// followupKind is the cache key in the reviews table: one row per (user, insight, question).
// A composite kind avoids a migration, and the reviews table is already what the daily budget
// and the cost metrics count, so follow-ups land in both for free.
func followupKind(kind, questionID string) string { return "fu:" + kind + ":" + questionID }

// Followup answers one chip under an existing insight.
//
// The insight's own prompt is reused as the data block, so the answer cannot drift from what
// the person is looking at. Answers are cached per (user, insight, date, question): tapping the
// same chip twice is free, tapping a different one is a new call.
func (g *Generator) Followup(ctx context.Context, userID, kind string, date time.Time, questionID string) (*repo.Review, error) {
	q, ok := findFollowup(kind, questionID)
	if !ok {
		return nil, ErrBadQuestion
	}
	today := clock.Today()
	if date.After(today) || date.Before(today.AddDate(0, 0, -reviewMaxBack)) {
		return nil, ErrBadDate
	}

	unlock := g.lockUser(userID)
	defer unlock()

	cacheKind := followupKind(kind, questionID)
	if cached, err := g.reviews.Get(ctx, userID, cacheKind, date); err != nil || cached != nil {
		return cached, err
	}

	// Only uncached answers cost anything, so the cap is checked after the cache lookup.
	used, err := g.reviews.CountKindPrefixToday(ctx, userID, "fu:")
	if err != nil {
		return nil, err
	}
	if used >= FollowupDailyLimit {
		return nil, ErrBudget
	}

	insight, insightPrompt, err := g.insightFor(ctx, userID, kind, date)
	if err != nil {
		return nil, err
	}

	user := llm.BuildFollowup(insightPrompt, insight, q.Label)
	return g.complete(ctx, userID, prompts.FollowupSystem, analytics.ComponentLLMFollowup,
		analytics.TriggerFollowup, cacheKind, date, user, FollowupLimits)
}

// insightFor returns the text the person is looking at and the prompt it was built from.
// ErrNoData when the insight itself is not there — a chip cannot be answered without it.
func (g *Generator) insightFor(ctx context.Context, userID, kind string, date time.Time) (string, string, error) {
	profile, err := g.users.Profile(ctx, userID)
	if err != nil {
		return "", "", err
	}

	switch kind {
	case "morning":
		msg, err := g.morning.ForDate(ctx, userID, date)
		if err != nil {
			return "", "", err
		}
		if msg == nil || msg.Message == "" {
			return "", "", ErrNoData
		}
		// The forecast for `date` was built from the days before it.
		days, err := g.daily.LastN(ctx, userID, historyDays, date.AddDate(0, 0, -1))
		if err != nil {
			return "", "", err
		}
		if len(days) == 0 {
			return "", "", ErrNoData
		}
		last, err := g.checkin.Latest(ctx, userID)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return "", "", err
		}
		in := llm.MorningInput{
			Days: days, Last: last, Profile: profile,
			Signals: DaySignals(days, profile), First: len(days) <= 2,
		}
		return msg.Message, llm.BuildMorningPrompt(in), nil

	case "day":
		cached, err := g.reviews.Get(ctx, userID, "day", date)
		if err != nil {
			return "", "", err
		}
		if cached == nil {
			return "", "", ErrNoData
		}
		days, err := g.daily.LastN(ctx, userID, historyDays+1, date)
		if err != nil {
			return "", "", err
		}
		if len(days) == 0 || !days[len(days)-1].Date.Equal(date) {
			return "", "", ErrNoData
		}
		checkins, err := g.checkin.Range(ctx, userID, date, date)
		if err != nil {
			return "", "", err
		}
		sig := DaySignals(days, profile)
		prompt := DayReviewPrompt(days, sig, checkins[date.Format("2006-01-02")], profile, date.Equal(clock.Today()))
		return cached.Text, prompt, nil

	case "week":
		cached, err := g.reviews.Get(ctx, userID, "week", date)
		if err != nil {
			return "", "", err
		}
		if cached == nil {
			return "", "", ErrNoData
		}
		days, err := g.daily.LastN(ctx, userID, 7, date.AddDate(0, 0, -1))
		if err != nil {
			return "", "", err
		}
		if len(days) < weeklyMinDays {
			return "", "", ErrNoData
		}
		checkins, err := g.checkin.Range(ctx, userID, days[0].Date, days[len(days)-1].Date)
		if err != nil {
			return "", "", err
		}
		return cached.Text, WeeklyPrompt(days, checkins, profile), nil
	}
	return "", "", ErrBadQuestion
}
