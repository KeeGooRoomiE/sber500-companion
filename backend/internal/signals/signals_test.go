package signals

import (
	"strings"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func hours(work, morning int) []int {
	h := make([]int, 24)
	for i := 7; i < 12; i++ {
		h[i] = morning
	}
	for i := 12; i < 18; i++ {
		h[i] = work
	}
	return h
}

func ip(v int) *int       { return &v }
func sp(v string) *string { return &v }

func day(n int, hourly []int, unlocks, sleep int, last string, apps ...repo.AppUsage) *repo.DailyData {
	return &repo.DailyData{
		Date: time.Date(2026, 9, n, 0, 0, 0, 0, time.UTC), HourlyUnlocks: hourly,
		Unlocks: ip(unlocks), SleepMin: ip(sleep), LastUnlock: sp(last), ScreenMin: ip(200), TopApps: apps,
	}
}

func label(p string) string { return map[string]string{"com.slack": "Slack"}[p] }

// Арина: «с утра 15 звонков — через 2 часа настроение не очень»
func TestBusyWorkDay(t *testing.T) {
	var days []*repo.DailyData
	for i := 1; i <= 6; i++ {
		days = append(days, day(i, hours(3, 2), 50, 450, "23:30"))
	}
	days = append(days, day(7, hours(8, 7), 110, 330, "01:10",
		repo.AppUsage{Package: "com.slack", Minutes: 95},
		repo.AppUsage{Package: "com.google.android.dialer", Minutes: 50},
	))

	got := ForLastDay(days, nil, label)
	keys := map[string]Signal{}
	for _, s := range got {
		keys[s.Key] = s
	}
	// Top 4 by distance from the norm; calls (weaker here) are cut by the limit
	for _, want := range []string{"work_load", "morning_storm", "late_phone", "short_sleep"} {
		if _, ok := keys[want]; !ok {
			t.Errorf("missing %s in %+v", want, got)
		}
	}
	if len(got) > maxSignals {
		t.Errorf("more than %d signals", maxSignals)
	}
	if d := keys["work_load"].Detail; !strings.Contains(d, "Slack") {
		t.Errorf("work load should name Slack: %q", d)
	}
	if d := keys["late_phone"].Detail; !strings.Contains(d, "01:10") || !strings.Contains(d, "23:30") {
		t.Errorf("late phone evidence: %q", d)
	}
}

func TestCallsDay(t *testing.T) {
	var days []*repo.DailyData
	for i := 1; i <= 6; i++ {
		days = append(days, day(i, hours(3, 2), 50, 450, "23:30"))
	}
	days = append(days, day(7, hours(3, 2), 50, 450, "23:30", repo.AppUsage{Package: "com.samsung.android.incallui", Minutes: 75}))
	got := ForLastDay(days, nil, label)
	if len(got) != 1 || got[0].Key != "calls" || !strings.Contains(got[0].Detail, "1 ч 15 м") {
		t.Errorf("want a single calls signal, got %+v", got)
	}
}

func TestQuietDay(t *testing.T) {
	var days []*repo.DailyData
	for i := 1; i <= 7; i++ {
		days = append(days, day(i, hours(3, 2), 50, 450, "23:30"))
	}
	if got := ForLastDay(days, nil, label); len(got) != 0 {
		t.Errorf("ordinary day produced signals: %+v", got)
	}
	if !strings.Contains(PromptBlock(nil), "ровный") {
		t.Error("empty block should say the day was even")
	}
}

func stepsDay(n, steps int, hourly []int) *repo.DailyData {
	return &repo.DailyData{Date: time.Date(2026, 9, n, 0, 0, 0, 0, time.UTC), Steps: ip(steps), HourlySteps: hourly}
}

func walkHours(morning, peakAt, peak int) []int {
	h := make([]int, 24)
	for i := 7; i < 12; i++ {
		h[i] = morning
	}
	if peakAt >= 0 {
		h[peakAt] = peak
	}
	return h
}

func keys(s []Signal) string {
	var k []string
	for _, x := range s {
		k = append(k, x.Key)
	}
	return strings.Join(k, ",")
}

func TestSteps(t *testing.T) {
	var base []*repo.DailyData
	for i := 1; i <= 6; i++ {
		base = append(base, stepsDay(i, 6000, walkHours(400, -1, 0)))
	}
	// Barely moved, nothing before noon
	low := ForLastDay(append(base, stepsDay(7, 1800, walkHours(20, -1, 0))), nil, label)
	if !strings.Contains(keys(low), "low_move") || !strings.Contains(keys(low), "still_morning") {
		t.Fatalf("want low_move + still_morning, got %s", keys(low))
	}
	for _, s := range low {
		if s.Key == "low_move" && s.Detail != "1800 шагов — обычно 6000" {
			t.Errorf("detail: %q", s.Detail)
		}
	}
	// A long evening walk
	walk := ForLastDay(append(base, stepsDay(7, 12400, walkHours(400, 18, 4200))), nil, label)
	if !strings.Contains(keys(walk), "walk") || !strings.Contains(keys(walk), "active_day") {
		t.Fatalf("want walk + active_day, got %s", keys(walk))
	}
	for _, s := range walk {
		if s.Key == "walk" && s.Detail != "4200 шагов с 18 до 19" {
			t.Errorf("walk detail: %q", s.Detail)
		}
	}
	// No Health Connect: steps 0 everywhere — say nothing about movement
	var none []*repo.DailyData
	for i := 1; i <= 7; i++ {
		none = append(none, stepsDay(i, 0, nil))
	}
	if got := ForLastDay(none, nil, label); len(got) != 0 {
		t.Fatalf("no steps data must give no step signals, got %s", keys(got))
	}
}

func TestThousands(t *testing.T) {
	for in, want := range map[int]string{980: "980", 7240: "7240", 12400: "12 400", 1234567: "1 234 567"} {
		if got := thousands(in); got != want {
			t.Errorf("thousands(%d) = %q, want %q", in, got, want)
		}
	}
}
