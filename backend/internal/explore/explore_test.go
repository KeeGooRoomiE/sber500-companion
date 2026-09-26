package explore

import (
	"strings"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/mock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func ip(v int) *int       { return &v }
func sp(v string) *string { return &v }

func label(p string) string { return p }

// A month of calm days; days 8 and 15 and yesterday had a storm of unlocks in the morning.
func stormMonth() Input {
	start := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	storm := map[int]bool{8: true, 15: true, 20: true}
	in := Input{CheckIns: map[string]*repo.CheckIn{}, LabelOf: label}
	for i := 0; i <= 20; i++ {
		h := make([]int, 24)
		for k := 7; k < 22; k++ {
			h[k] = 3
		}
		sleep := 450
		if storm[i] {
			for k := 7; k < 12; k++ {
				h[k] = 12
			}
		}
		if i > 0 && storm[i-1] {
			sleep = 380 // the night after a stormy day is shorter
		}
		d := &repo.DailyData{
			Date: start.AddDate(0, 0, i), HourlyUnlocks: h, Unlocks: ip(sum(h)),
			ScreenMin: ip(200), SleepMin: ip(sleep), LastUnlock: sp("23:10"),
		}
		in.Days = append(in.Days, d)
		if storm[i] && i != 20 {
			in.CheckIns[key(d.Date)] = &repo.CheckIn{DayFeel: "hard"}
		}
	}
	return in
}

func sum(h []int) int {
	s := 0
	for _, v := range h {
		s += v
	}
	return s
}

func TestSimilarDays(t *testing.T) {
	a := Find(stormMonth(), "similar")
	if a == nil {
		t.Fatal("expected the «similar days» question")
	}
	all := strings.Join(a.Facts, "\n")
	for _, want := range []string{"16 сен", "9 сен", "«Плотное утро»", "тяжело — 2", "Ночь после таких дней: сон в среднем 6 ч 20 м"} {
		if !strings.Contains(all, want) {
			t.Errorf("facts miss %q:\n%s", want, all)
		}
	}
}

func TestNoSimilarDaysOnACalmMonth(t *testing.T) {
	in := stormMonth()
	for _, d := range in.Days { // flatten every morning
		for k := 7; k < 12; k++ {
			d.HourlyUnlocks[k] = 3
		}
		d.Unlocks = ip(sum(d.HourlyUnlocks))
	}
	if a := Find(in, "similar"); a != nil {
		t.Fatalf("calm month must not offer similar days, got %v", a.Facts)
	}
}

// Every persona produces some questions and no empty facts.
func TestPersonas(t *testing.T) {
	personas, err := mock.Personas()
	if err != nil {
		t.Fatal(err)
	}
	today := time.Date(2026, 9, 27, 0, 0, 0, 0, time.UTC)
	for _, p := range personas {
		in := Input{CheckIns: map[string]*repo.CheckIn{}, LabelOf: label, WorkApps: map[string]bool{}}
		for _, pkg := range strings.Split(p.Profile["work_apps"], ",") {
			in.WorkApps[pkg] = true
		}
		for _, d := range p.Days {
			date := today.AddDate(0, 0, d.Offset)
			in.Days = append(in.Days, &repo.DailyData{
				Date: date, SleepMin: d.SleepMin, Steps: d.Steps, ScreenMin: d.ScreenMin, Unlocks: d.Unlocks,
				FirstUnlock: d.FirstUnlock, LastUnlock: d.LastUnlock, Wakeup: d.Wakeup, Bedtime: d.Bedtime,
				TopApps: d.TopApps, HourlyUnlocks: d.HourlyUnlocks, HourlyScreen: d.HourlyScreen, HourlySteps: d.HourlySteps,
			})
			if d.Feel != "" {
				in.CheckIns[key(date)] = &repo.CheckIn{DayFeel: d.Feel}
			}
		}
		qs := Build(in)
		var ids []string
		for _, q := range qs {
			ids = append(ids, q.ID)
			for _, f := range q.Facts {
				if strings.TrimSpace(f) == "" {
					t.Errorf("%s/%s: empty fact", p.ID, q.ID)
				}
			}
		}
		t.Logf("%s: %v", p.ID, ids)
		if p.ID != "mock_newbie" && len(qs) == 0 {
			t.Errorf("%s: a week of data should answer something", p.ID)
		}
	}
}
