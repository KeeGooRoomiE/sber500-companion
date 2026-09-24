package scheduler

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Morning pre-generation runs at 04:10 in the product time zone (APP_TZ, default Moscow),
// well before the 07:40 morning notification. Users missed here get generated on demand
// by GET /morning.
const (
	MorningHour   = 4
	MorningMinute = 10
	workers       = 4
	retryPause    = 2 * time.Minute
)

type Scheduler struct {
	morning   *repo.MorningRepo
	generator *forecast.Generator
}

func New(morning *repo.MorningRepo, generator *forecast.Generator) *Scheduler {
	return &Scheduler{morning: morning, generator: generator}
}

// Start runs the morning generation loop in a goroutine.
func (s *Scheduler) Start(ctx context.Context) {
	go s.loop(ctx)
}

func (s *Scheduler) loop(ctx context.Context) {
	for {
		next := clock.NextAt(MorningHour, MorningMinute)
		slog.Info("scheduler: next morning run", "at", next.Format(time.RFC3339))

		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Until(next)):
		}

		s.RunMorning(ctx)
	}
}

// RunMorning generates today's messages; users that failed get one more try after a pause.
func (s *Scheduler) RunMorning(ctx context.Context) {
	today := clock.Today()
	failed := s.pass(ctx, today)
	if len(failed) == 0 || ctx.Err() != nil {
		return
	}
	slog.Info("scheduler: retrying failed users", "count", len(failed))
	select {
	case <-ctx.Done():
		return
	case <-time.After(retryPause):
	}
	if still := s.pass(ctx, today); len(still) > 0 {
		slog.Error("scheduler: users left without a morning message", "count", len(still))
	}
}

func (s *Scheduler) pass(ctx context.Context, date time.Time) []string {
	userIDs, err := s.morning.UsersToGenerate(ctx, date)
	if err != nil {
		slog.Error("scheduler: fetch pending users", "err", err)
		return nil
	}
	slog.Info("scheduler: users to process", "count", len(userIDs), "date", date.Format("2006-01-02"))

	jobs := make(chan string)
	var (
		mu     sync.Mutex
		failed []string
		wg     sync.WaitGroup
	)
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for uid := range jobs {
				if _, err := s.generator.Ensure(ctx, uid, date, analytics.TriggerScheduled); err != nil {
					slog.Error("scheduler: generate failed", "user", uid, "err", err)
					if err != forecast.ErrBudget && err != forecast.ErrNoData {
						mu.Lock()
						failed = append(failed, uid)
						mu.Unlock()
					}
				}
			}
		}()
	}
	for _, uid := range userIDs {
		select {
		case <-ctx.Done():
		case jobs <- uid:
			continue
		}
		break
	}
	close(jobs)
	wg.Wait()
	return failed
}
