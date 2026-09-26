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
