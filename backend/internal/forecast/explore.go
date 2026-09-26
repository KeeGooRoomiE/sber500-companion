package forecast

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/explore"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

var (
	// ErrExploreLimit: the person has used today's «Хочу ещё» answers.
	ErrExploreLimit = errors.New("explore daily limit")
	// ErrUnknownQuestion: the question isn't answerable from this person's data (or doesn't exist).
	ErrUnknownQuestion = errors.New("unknown question")
)

const (
	// ExploreDailyLimit answers per person per day: enough to satisfy curiosity, cheap for the budget.
	ExploreDailyLimit = 5
	exploreDays       = 31 // «похожие дни» look back a month
)

var ExploreLimits = Limits{Tokens: 260, Chars: 420}

// ExploreState is what the «Хочу ещё» sheet shows: today's answers, what can be asked next.
type ExploreState struct {
	Answered []repo.ExploreAnswer
	Next     []explore.Question
	Left     int
}

// ExploreInput loads the data explore questions are computed from (a month up to yesterday).
func (g *Generator) ExploreInput(ctx context.Context, userID string) (explore.Input, map[string]string, error) {
	yesterday := clock.Today().AddDate(0, 0, -1)
	days, err := g.daily.LastN(ctx, userID, exploreDays, yesterday)
	if err != nil || len(days) == 0 {
		return explore.Input{}, nil, err
	}
	checkins, err := g.checkin.Range(ctx, userID, days[0].Date, yesterday)
	if err != nil {
		return explore.Input{}, nil, err
	}
	profile, err := g.users.Profile(ctx, userID)
	if err != nil {
		return explore.Input{}, nil, err
	}
	return explore.Input{Days: days, CheckIns: checkins, WorkApps: WorkApps(profile), LabelOf: llm.AppLabel}, profile, nil
}

// ExploreToday returns today's answers and up to 3 questions not asked yet.
func (g *Generator) ExploreToday(ctx context.Context, userID string) (*ExploreState, error) {
	in, _, err := g.ExploreInput(ctx, userID)
	if err != nil {
		return nil, err
	}
	answered, err := g.explore.Today(ctx, userID, clock.Today())
	if err != nil {
		return nil, err
	}
	return &ExploreState{Answered: answered, Next: nextQuestions(in, answered, 3), Left: left(answered)}, nil
}

// ExploreAnswer answers one question (cached for the day) and returns the new state.
func (g *Generator) ExploreAnswer(ctx context.Context, userID, questionID string) (*repo.ExploreAnswer, *ExploreState, error) {
	unlock := g.lockUser(userID)
	defer unlock()

	today := clock.Today()
	in, profile, err := g.ExploreInput(ctx, userID)
	if err != nil {
		return nil, nil, err
	}
	answered, err := g.explore.Today(ctx, userID, today)
	if err != nil {
		return nil, nil, err
	}
	for i := range answered {
		if answered[i].QuestionID == questionID { // asked again: same answer, no new call
			return &answered[i], &ExploreState{Answered: answered, Next: nextQuestions(in, answered, 2), Left: left(answered)}, nil
		}
	}
	if left(answered) == 0 {
		return nil, nil, ErrExploreLimit
	}
	q := explore.Find(in, questionID)
	if q == nil {
		return nil, nil, ErrUnknownQuestion
	}
	if ok, err := g.withinBudget(ctx); err != nil {
		return nil, nil, err
	} else if !ok {
		slog.Warn("explore: daily LLM cap reached", "cap", g.dailyCap, "user", userID)
		return nil, nil, ErrBudget
	}

	system, version := g.prompts.Active(ctx, prompts.ExploreSystem)
	lctx, cancel := context.WithTimeout(ctx, llmTimeout)
	defer cancel()
	start := time.Now()
	res, err := g.llm.Complete(lctx, system, llm.BuildExplore(q.Text, q.Facts, profile), ExploreLimits.Tokens, ExploreLimits.Chars)
	if err == nil && !SafeOutput(res.Message) {
		slog.Error("explore: unsafe model output rejected", "user", userID)
		err = ErrUnsafeOutput
	}
	event := analytics.CallEvent{
		UserID: userID, Timestamp: start, CallType: analytics.CallTypeLLM, Component: analytics.ComponentLLMExplore,
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
		return nil, nil, err
	}
	g.callLog.Log(ctx, event)

	a := &repo.ExploreAnswer{
		UserID: userID, Date: today, QuestionID: q.ID, Question: q.Text, Text: res.Message, Facts: q.Facts,
		PromptTokens: res.PromptTokens, CompletionTokens: res.CompletionTokens, LatencyMs: res.LatencyMs,
		Model: g.llm.Model(), PromptVersion: version,
	}
	if err := g.explore.Save(ctx, a); err != nil {
		return nil, nil, err
	}
	answered = append(answered, *a)
	return a, &ExploreState{Answered: answered, Next: nextQuestions(in, answered, 2), Left: left(answered)}, nil
}

func nextQuestions(in explore.Input, answered []repo.ExploreAnswer, n int) []explore.Question {
	if left(answered) == 0 {
		return []explore.Question{}
	}
	asked := map[string]bool{}
	for _, a := range answered {
		asked[a.QuestionID] = true
	}
	out := []explore.Question{}
	for _, q := range explore.Build(in) {
		if !asked[q.ID] && len(out) < n {
			out = append(out, q.Question)
		}
	}
	return out
}

func left(answered []repo.ExploreAnswer) int {
	if n := ExploreDailyLimit - len(answered); n > 0 {
		return n
	}
	return 0
}
