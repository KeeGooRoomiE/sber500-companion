package forecast

import (
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func TestReuseExisting(t *testing.T) {
	now := time.Date(2026, 10, 5, 4, 13, 0, 0, time.UTC)
	failed := func(attempts int, ago time.Duration) *repo.MorningMessage {
		return &repo.MorningMessage{Attempts: attempts, LastAttemptAt: now.Add(-ago)}
	}
	cases := []struct {
		name    string
		msg     *repo.MorningMessage
		trigger analytics.Trigger
		want    bool
	}{
		{"real message", &repo.MorningMessage{Message: "x"}, analytics.TriggerScheduled, true},
		{"attempts exhausted, scheduled", failed(maxAttempts, time.Hour), analytics.TriggerScheduled, true},
		{"attempts exhausted, user", failed(maxAttempts, time.Hour), analytics.TriggerUserAction, true},
		// The 05.10 defect: scheduler retry 2 min after a failure must generate again.
		{"fresh failure, scheduled retry", failed(1, 2*time.Minute), analytics.TriggerScheduled, false},
		{"fresh failure, user request waits", failed(1, 2*time.Minute), analytics.TriggerUserAction, true},
		{"old failure, user request retries", failed(1, 31*time.Minute), analytics.TriggerUserAction, false},
	}
	for _, c := range cases {
		if got := reuseExisting(c.msg, c.trigger, now); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}
