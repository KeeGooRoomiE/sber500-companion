package signals

import (
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Someone whose weekends genuinely differ from their weekdays. A mixed baseline puts their
// «обычно» between the two regimes, so an ordinary Saturday looks like a remarkable drop and
// an ordinary Tuesday looks like a spike — the signal fires on the person being themselves.
func TestWeekendBaselineDoesNotMistakeRoutineForChange(t *testing.T) {
	ip := func(v int) *int { return &v }
	// Sep 19 2026 is a Saturday.
	start := time.Date(2026, 9, 19, 0, 0, 0, 0, time.UTC)

	build := func(days int) []*repo.DailyData {
		var out []*repo.DailyData
		for i := 0; i < days; i++ {
			date := start.AddDate(0, 0, i)
			screen, unlocks := 300, 80 // a working day
			if isWeekend(date) {
				screen, unlocks = 100, 30 // weekends are quiet for this person
			}
			out = append(out, &repo.DailyData{
				Date: date, ScreenMin: ip(screen), Unlocks: ip(unlocks),
				HourlyUnlocks: make([]int, 24), HourlyScreen: make([]int, 24),
			})
		}
		return out
	}

	has := func(days []*repo.DailyData, key string) bool {
		for _, s := range ForLastDay(days, map[string]bool{}, func(s string) string { return s }) {
			if s.Key == key {
				return true
			}
		}
		return false
	}

	// 22 days ending on a Saturday: an utterly ordinary weekend for this person.
	days := build(22)
	last := days[len(days)-1]
	if !isWeekend(last.Date) {
		t.Fatalf("фикстура должна заканчиваться выходным, а это %s", last.Date.Weekday())
	}
	if has(days, "calm_screen") {
		t.Error("обычная для человека суббота не должна читаться как «спокойный день» — " +
			"сравнивать её надо с другими выходными, а не со смесью")
	}

	// The same person on an ordinary Tuesday must not read as a jump either.
	days = build(18) // ends on a Tuesday
	last = days[len(days)-1]
	if isWeekend(last.Date) {
		t.Fatalf("фикстура должна заканчиваться будним днём, а это %s", last.Date.Weekday())
	}
	if has(days, "jumpy") {
		t.Error("обычный вторник не должен читаться как «дёрганый день»")
	}

	// And the split must not blind us: a genuinely quiet Saturday still has to be noticed.
	days = build(22)
	quiet := 30
	days[len(days)-1].ScreenMin = &quiet
	if !has(days, "calm_screen") {
		t.Error("суббота втрое тише обычной субботы — это настоящий сигнал, он должен остаться")
	}
}
