package scheduler

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// MorningHour / MorningMinute define when generation runs (04:10 local server time).
const MorningHour = 4
const MorningMinute = 10

type Scheduler struct {
	daily   *repo.DailyRepo
	checkin *repo.CheckInRepo
	morning *repo.MorningRepo
	llm     *llm.Client
	callLog *analytics.Logger
}

func New(
	daily *repo.DailyRepo,
	checkin *repo.CheckInRepo,
	morning *repo.MorningRepo,
	llm *llm.Client,
	callLog *analytics.Logger,
) *Scheduler {
	return &Scheduler{daily, checkin, morning, llm, callLog}
}

// Start runs the morning generation loop in a goroutine.
func (s *Scheduler) Start(ctx context.Context) {
	go s.loop(ctx)
}

func (s *Scheduler) loop(ctx context.Context) {
	for {
		next := nextFiring(MorningHour, MorningMinute)
		slog.Info("scheduler: next morning run", "at", next.Format(time.RFC3339))

		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Until(next)):
		}

		slog.Info("scheduler: running morning generation")
		s.runMorning(ctx)
	}
}

func (s *Scheduler) runMorning(ctx context.Context) {
	today := time.Now().Truncate(24 * time.Hour)

	userIDs, err := s.morning.UsersWithoutMessage(ctx, today)
	if err != nil {
		slog.Error("scheduler: fetch pending users", "err", err)
		return
	}
	slog.Info("scheduler: users to process", "count", len(userIDs))

	for _, uid := range userIDs {
		if err := s.generateForUser(ctx, uid, today); err != nil {
			slog.Error("scheduler: generate failed", "user", uid, "err", err)
		}
	}
}

func (s *Scheduler) generateForUser(ctx context.Context, userID string, date time.Time) error {
	days, err := s.daily.LastN(ctx, userID, 7)
	if err != nil {
		return err
	}

	last, err := s.checkin.Latest(ctx, userID)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}

	result, err := s.llm.GenerateMorning(ctx, days, last)
	if err != nil {
		s.callLog.Log(ctx, analytics.CallEvent{
			UserID:      userID,
			Timestamp:   time.Now(),
			CallType:    analytics.CallTypeLLM,
			Component:   analytics.ComponentLLMMorning,
			Trigger:     analytics.TriggerScheduled,
			UserVisible: true,
			Result:      "error",
		})
		return err
	}

	s.callLog.Log(ctx, analytics.CallEvent{
		UserID:      userID,
		Timestamp:   time.Now(),
		CallType:    analytics.CallTypeLLM,
		Component:   analytics.ComponentLLMMorning,
		Trigger:     analytics.TriggerScheduled,
		UserVisible: true,
		Result:      "ok",
		LatencyMs:   int64(result.LatencyMs),
	})

	return s.morning.Insert(ctx, &repo.MorningMessage{
		UserID:           userID,
		Date:             date,
		Message:          result.Message,
		PromptTokens:     result.PromptTokens,
		CompletionTokens: result.CompletionTokens,
		LatencyMs:        result.LatencyMs,
		Model:            os.Getenv("LLM_MODEL"),
	})
}

// nextFiring returns the next wall-clock time for hour:minute.
func nextFiring(hour, minute int) time.Time {
	now := time.Now()
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, minute, 0, 0, now.Location())
	if !next.After(now) {
		next = next.Add(24 * time.Hour)
	}
	return next
}
