package signals

import (
	"fmt"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/mock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Only four signals reach the person, chosen by strength. With every signal added, the
// calibration — not the computation — decides what is actually said. This prints what each
// persona would hear, so a miscalibrated newcomer crowding out a better signal is visible
// instead of silent. Run: go test ./internal/signals/ -run Calibration -v
func TestSignalCalibrationAcrossPersonas(t *testing.T) {
	personas, err := mock.Personas()
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)

	for _, p := range personas {
		days := make([]*repo.DailyData, 0, len(p.Days))
		for _, d := range p.Days {
			days = append(days, &repo.DailyData{
				Date: base.AddDate(0, 0, d.Offset+1), SleepMin: d.SleepMin,
				Bedtime: d.Bedtime, Wakeup: d.Wakeup, Steps: d.Steps, BatteryMorning: d.BatteryMorning,
				ScreenMin: d.ScreenMin, Unlocks: d.Unlocks,
				FirstUnlock: d.FirstUnlock, LastUnlock: d.LastUnlock, TopApps: d.TopApps,
				HourlyUnlocks: d.HourlyUnlocks, HourlyScreen: d.HourlyScreen, HourlySteps: d.HourlySteps,
			})
		}
		got := ForLastDay(days, map[string]bool{}, func(s string) string { return s })
		line := ""
		for _, g := range got {
			line += fmt.Sprintf("%s(%.1f) ", g.Key, g.strength)
		}
		if line == "" {
			line = "— ничего —"
		}
		t.Logf("%-20s %s", p.ID, line)
	}
}

// Each of these was a real false positive found by reading the calibration output above.
// They are cheap to reintroduce by widening a window, and invisible without a test.
func TestNoFalsePositivesAcrossPersonas(t *testing.T) {
	personas, err := mock.Personas()
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	fired := map[string]map[string]bool{}

	for _, p := range personas {
		days := make([]*repo.DailyData, 0, len(p.Days))
		for _, d := range p.Days {
			days = append(days, &repo.DailyData{
				Date: base.AddDate(0, 0, d.Offset+1), SleepMin: d.SleepMin,
				Bedtime: d.Bedtime, Wakeup: d.Wakeup, Steps: d.Steps, BatteryMorning: d.BatteryMorning,
				ScreenMin: d.ScreenMin, Unlocks: d.Unlocks,
				FirstUnlock: d.FirstUnlock, LastUnlock: d.LastUnlock, TopApps: d.TopApps,
				HourlyUnlocks: d.HourlyUnlocks, HourlyScreen: d.HourlyScreen, HourlySteps: d.HourlySteps,
			})
		}
		set := map[string]bool{}
		for _, g := range ForLastDay(days, map[string]bool{}, func(s string) string { return s }) {
			set[g.Key] = true
		}
		fired[p.ID] = set
	}

	mustNot := []struct{ persona, key, why string }{
		{"mock_nightowl", "night_checks", "сова не просыпалась — она ещё не ложилась, экран в час ночи идёт с вечера"},
		{"mock_nocharge", "night_checks", "уснул с телефоном в 01:05 — это не ночное пробуждение"},
		{"mock_week", "night_checks", "подъём в 05:48 — ранний, но это утро, а не ночь"},
		{"mock_nightchecks", "false_start", "это пробуждение среди ночи, а не ложный подъём утром"},
		{"mock_awayday", "desk_day", "14 000 шагов — человек был на ногах"},
		{"mock_deskday", "away_day", "900 шагов — человек никуда не ходил"},
	}
	for _, c := range mustNot {
		if fired[c.persona][c.key] {
			t.Errorf("%s не должен давать %s: %s", c.persona, c.key, c.why)
		}
	}

	mustFire := []struct{ persona, key string }{
		{"mock_falsestart", "false_start"},
		{"mock_nightchecks", "night_checks"},
		{"mock_checker", "checking_day"},
		{"mock_immersed", "immersed_day"},
		{"mock_awayday", "away_day"},
		{"mock_deskday", "desk_day"},
	}
	for _, c := range mustFire {
		if !fired[c.persona][c.key] {
			t.Errorf("%s должен давать %s, но его нет среди четырёх сильнейших", c.persona, c.key)
		}
	}
}

// A signal nothing can trigger is not a feature, it is dead code that reads as one. Four of
// them shipped in the second batch before this test existed: quiet_block, no_break,
// evening_gap and sedentary_streak all looked finished and fired for nobody, because the
// personas had no gaps for them to find and one threshold asked for more than any day had.
func TestEverySignalFiresForSomePersona(t *testing.T) {
	personas, err := mock.Personas()
	if err != nil {
		t.Fatal(err)
	}
	base := time.Date(2026, 10, 7, 0, 0, 0, 0, time.UTC)
	seen := map[string]bool{}
	for _, p := range personas {
		days := make([]*repo.DailyData, 0, len(p.Days))
		for _, d := range p.Days {
			days = append(days, &repo.DailyData{
				Date: base.AddDate(0, 0, d.Offset+1), SleepMin: d.SleepMin,
				Bedtime: d.Bedtime, Wakeup: d.Wakeup, Steps: d.Steps, BatteryMorning: d.BatteryMorning,
				ScreenMin: d.ScreenMin, Unlocks: d.Unlocks,
				FirstUnlock: d.FirstUnlock, LastUnlock: d.LastUnlock, TopApps: d.TopApps,
				HourlyUnlocks: d.HourlyUnlocks, HourlyScreen: d.HourlyScreen, HourlySteps: d.HourlySteps,
			})
		}
		for _, g := range ForLastDay(days, map[string]bool{}, func(s string) string { return s }) {
			seen[g.Key] = true
		}
	}

	// Signals added for the behavioural work. The older ones are covered by signals_test.go.
	for _, key := range []string{
		"checking_day", "immersed_day", "false_start", "night_checks",
		"long_day", "short_day", "away_day", "desk_day",
		"long_stretch", "quiet_block", "no_break", "evening_gap", "day_shift",
		"late_start", "start_jitter", "steady_rhythm", "bedtime_drift",
		"new_app", "one_app_day", "commute_screen", "sedentary_streak", "not_charged",
	} {
		if !seen[key] {
			t.Errorf("%s не срабатывает ни на одной из %d персон — нужна персона или порог завышен",
				key, len(personas))
		}
	}
}
