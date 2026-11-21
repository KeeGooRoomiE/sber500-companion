package api

import (
	"testing"
	"time"
)

func day(s string) time.Time { d, _ := time.Parse("2006-01-02", s); return d }

// «Устойчиво» is 14 qualifying days inside any 28 consecutive days. The point of the window is
// that a 34-day period cannot pool its first and last days, so these cases pin that down.
func TestBestWindow(t *testing.T) {
	// Builds a series where the named dates met the DAU threshold.
	series := func(dates ...string) []dayRow {
		out := make([]dayRow, 0, len(dates))
		for _, s := range dates {
			out = append(out, dayRow{date: day(s), dauOK: true})
		}
		return out
	}
	from, to := day("2026-11-05"), day("2026-12-08") // 34 days, the extended period

	t.Run("days too far apart never share a window", func(t *testing.T) {
		// 7 days at the very start and 7 at the very end: 14 qualifying days in the period,
		// yet spread over all 34 days. The best any 28-day window can hold is 8 — the one
		// starting 11.11 reaches 08.12, taking the last day of the opening block and all
		// seven closing days. Counting all 14 would be the bug this guards against.
		s := series(
			"2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11",
			"2026-12-02", "2026-12-03", "2026-12-04", "2026-12-05", "2026-12-06", "2026-12-07", "2026-12-08",
		)
		got := bestWindow(s, from, to, 28)
		if got.dau != 8 {
			t.Fatalf("best window = %d, want 8 (14 would mean the window is being ignored)", got.dau)
		}
		// Several windows tie at 8 here (05.11–02.12 and 11.11–08.12 among them) and the
		// earliest wins, which is arbitrary but deterministic. Only the span is asserted.
		if d := day(got.to).Sub(day(got.from)).Hours() / 24; d != 27 {
			t.Fatalf("winning window %s..%s spans %.0f days, want 28", got.from, got.to, d+1)
		}
	})

	t.Run("a run inside one window counts in full", func(t *testing.T) {
		var days []string
		for d := day("2026-11-20"); !d.After(day("2026-12-03")); d = d.AddDate(0, 0, 1) {
			days = append(days, d.Format("2006-01-02"))
		}
		got := bestWindow(series(days...), from, to, 28)
		if got.dau != 14 {
			t.Fatalf("best window = %d, want 14", got.dau)
		}
		if got.from == "" || got.to == "" {
			t.Fatal("the winning window must be named")
		}
	})

	t.Run("window reaching past the period is still the best one so far", func(t *testing.T) {
		// Three days at the end of what has happened; the window that holds them runs on into
		// days yet to come, which contribute nothing.
		s := series("2026-11-18", "2026-11-19", "2026-11-20")
		if got := bestWindow(s, from, to, 28).dau; got != 3 {
			t.Fatalf("best window = %d, want 3", got)
		}
	})

	t.Run("a period shorter than the window has one window: itself", func(t *testing.T) {
		s := series("2026-11-06", "2026-11-20")
		got := bestWindow(s, day("2026-11-05"), day("2026-11-25"), 28)
		if got.dau != 2 {
			t.Fatalf("best window = %d, want 2", got.dau)
		}
		if got.from != "2026-11-05" {
			t.Fatalf("window starts at %s, want the period start", got.from)
		}
	})

	t.Run("call thresholds take their own maximum", func(t *testing.T) {
		// DAU peaks late, calls peaked early: each metric reports its own best window.
		s := []dayRow{
			{date: day("2026-11-05"), c10: true}, {date: day("2026-11-06"), c10: true},
			{date: day("2026-12-07"), dauOK: true}, {date: day("2026-12-08"), dauOK: true},
		}
		got := bestWindow(s, from, to, 28)
		if got.dau != 2 || got.c10 != 2 {
			t.Fatalf("dau = %d, c10 = %d, want 2 and 2", got.dau, got.c10)
		}
	})

	t.Run("no qualifying days", func(t *testing.T) {
		got := bestWindow(nil, from, to, 28)
		if got.dau != 0 || got.from == "" {
			t.Fatalf("got %+v, want zero days but a named window", got)
		}
	})
}
