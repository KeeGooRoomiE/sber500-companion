package scheduler

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
)

// In-slot retries: the first well after the failure, the second later still. Both fit in a
// 5-minute slot together with the pass itself; ErrBudget/ErrNoData are never retried.
var slotRetryPauses = []time.Duration{90 * time.Second, 3 * time.Minute}

func (s *Scheduler) slotLoop(ctx context.Context) {
	for {
		next := nextBoundary(clock.Now(), s.cfg.Slot)
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Until(next)):
		}
		s.tick(ctx, next)
	}
}

// nextBoundary is the next wall-clock multiple of slot (counted from local midnight).
func nextBoundary(now time.Time, slot time.Duration) time.Time {
	n := int(slot / time.Minute)
	mins := now.Hour()*60 + now.Minute()
	next := (mins/n + 1) * n
	return time.Date(now.Year(), now.Month(), now.Day(), 0, next, 0, 0, now.Location())
}

// tick serves one slot: whoever is due and still without a message, at most PackSize of them.
// At the control time (the legacy 04:10) everyone due is taken without the limit, with the
// legacy retry pauses.
func (s *Scheduler) tick(ctx context.Context, at time.Time) {
	nowMin := at.Hour()*60 + at.Minute()
	if nowMin < windowStart || nowMin >= tickEnd {
		return
	}
	date := clock.DateOf(at)
	if !s.refreshTargets(ctx, date) {
		return
	}
	pending, err := s.morning.UsersToGenerate(ctx, date)
	if err != nil {
		slog.Error("scheduler: fetch pending users", "err", err)
		return
	}

	control := nowMin == defaultHabitMinute
	pack := s.cfg.PackSize
	pauses := slotRetryPauses
	if control {
		pack, pauses = len(pending), []time.Duration{retryPause, 2 * retryPause}
	}
	batch, carried := Plan(pending, s.targets, Target(nil, s.cfg.Lead), nowMin, s.cfg.Slot, pack)
	slog.Info("scheduler: slot", "at", at.Format("15:04"), "control", control,
		"pending", len(pending), "batch", len(batch), "carried", carried)
	if len(batch) == 0 {
		return
	}

	ctx = analytics.WithSession(ctx, fmt.Sprintf("sched:%s:%02d%02d", date.Format("2006-01-02"), at.Hour(), at.Minute()))
	failed := s.process(ctx, date, batch)
	for _, pause := range pauses {
		if len(failed) == 0 {
			return
		}
		slog.Info("scheduler: retrying failed users", "count", len(failed), "pause", pause.String())
		select {
		case <-ctx.Done():
			return
		case <-time.After(pause):
		}
		failed = s.process(ctx, date, failed)
	}
	if len(failed) > 0 {
		slog.Warn("scheduler: slot left users without a message", "count", len(failed))
	}
}

// refreshTargets recomputes everyone's generation minute once per day, in memory. Without a
// successful refresh the slot is skipped — better late than generated at the wrong time.
func (s *Scheduler) refreshTargets(ctx context.Context, date time.Time) bool {
	if s.targets != nil && s.targetsDate.Equal(date) {
		return true
	}
	opens, err := s.morning.HabitualOpenMinutes(ctx, clock.Location().String(), habitWindow)
	if err != nil {
		slog.Error("scheduler: habitual open times", "err", err)
		return s.targets != nil // yesterday's targets are still a fair guess
	}
	targets := make(map[string]int, len(opens))
	for id, mins := range opens {
		targets[id] = Target(mins, s.cfg.Lead)
	}
	s.targets, s.targetsDate = targets, date
	slog.Info("scheduler: targets refreshed", "users", len(targets), "date", date.Format("2006-01-02"))
	return true
}
