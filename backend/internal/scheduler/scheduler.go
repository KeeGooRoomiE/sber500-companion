package scheduler

import (
	"context"
	"log/slog"
	"math/rand/v2"
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
	// Extra passes after the first, each after a longer (doubling) pause. The LLM proxy is a
	// shared, sometimes-flaky test endpoint — a short dip should recover within a couple of
	// retries well before the 07:40 notification, without a human re-running anything.
	retryAttempts = 2

	// Jitter between dispatching users to the worker pool. Without it, every user becomes a
	// job the instant the run starts, so all `workers` immediately open fresh connections to
	// the LLM proxy at once. Spreading dispatch over a few hundred ms per user lets the proxy
	// (and connection pool) breathe instead of taking the whole batch as a single spike.
	dispatchJitterMin = 200 * time.Millisecond
	dispatchJitterMax = 1500 * time.Millisecond
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

// RunMorning generates today's messages; users left over after a pass get up to retryAttempts
// more tries, with the pause between them doubling each time.
func (s *Scheduler) RunMorning(ctx context.Context) {
	today := clock.Today()
	failed := s.pass(ctx, today)
	pause := retryPause
	for attempt := 1; len(failed) > 0 && ctx.Err() == nil; attempt++ {
		if attempt > retryAttempts {
			slog.Error("scheduler: users left without a morning message", "count", len(failed))
			return
		}
		slog.Info("scheduler: retrying failed users", "count", len(failed), "attempt", attempt, "pause", pause.String())
		select {
		case <-ctx.Done():
			return
		case <-time.After(pause):
		}
		failed = s.pass(ctx, today)
		pause *= 2
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
	for i, uid := range userIDs {
		if i > 0 {
			jitter := dispatchJitterMin + time.Duration(rand.Int64N(int64(dispatchJitterMax-dispatchJitterMin)))
			select {
			case <-ctx.Done():
			case <-time.After(jitter):
			}
		}
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
