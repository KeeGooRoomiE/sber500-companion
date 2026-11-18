package api

import (
	"testing"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
)

func TestInsightTrigger(t *testing.T) {
	cases := []struct {
		kind       string
		background bool
		want       analytics.Trigger
	}{
		{forecast.InsightMidday, true, analytics.TriggerScheduled},
		{forecast.InsightMidday, false, analytics.TriggerUserAction},
		// A person's own question stays a person's, whatever the client claims.
		{forecast.InsightQuestion, true, analytics.TriggerUserAction},
		{forecast.InsightStat, true, analytics.TriggerUserAction},
	}
	for _, c := range cases {
		if got := insightTrigger(c.kind, c.background); got != c.want {
			t.Errorf("insightTrigger(%q, %v) = %q, want %q", c.kind, c.background, got, c.want)
		}
	}
}
