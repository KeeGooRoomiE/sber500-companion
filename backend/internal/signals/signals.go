// Package signals explains a day from the phone's data — deterministically, before any LLM.
//
// The model only puts these findings into words; the app shows the same list under
// «Почему такой прогноз». So every claim in the forecast has visible evidence
// (interviews: "чёрный ящик не приму", "объясни вчера").
package signals

import (
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Signal is one notable thing about a day, with its evidence.
type Signal struct {
	Key      string  `json:"key"`
	Title    string  `json:"title"`
	Detail   string  `json:"detail"`
	Positive bool    `json:"positive"`
	strength float64 // how far from the person's norm; used for ordering
}

const maxSignals = 4

// Apps that are almost always work, in addition to the person's own «рабочие» picks.
var knownWorkApps = map[string]string{
	"com.slack":                        "Slack",
	"com.microsoft.teams":              "Teams",
	"us.zoom.videomeetings":            "Zoom",
	"com.microsoft.office.outlook":     "Outlook",
	"com.bitrix24.android":             "Битрикс24",
	"com.atlassian.android.jira.core":  "Jira",
	"com.google.android.apps.meetings": "Google Meet",
	"ru.yandex.telemost":               "Телемост",
	"ru.kontur.talk":                   "Контур.Толк",
}

// Dialer and in-call screens: minutes here are time on calls.
var callApps = map[string]bool{
	"com.google.android.dialer":    true,
	"com.android.dialer":           true,
	"com.samsung.android.dialer":   true,
	"com.samsung.android.incallui": true,
	"com.android.incallui":         true,
}

// ForLastDay explains the last day in `days` (oldest first) against the days before it.
// workApps are the person's own work apps (package names). labelOf names a package.
func ForLastDay(days []*repo.DailyData, workApps map[string]bool, labelOf func(string) string) []Signal {
	if len(days) == 0 {
		return nil
	}
	d := days[len(days)-1]
	base := days[:len(days)-1]
	var out []Signal
	add := func(s Signal) { out = append(out, s) }

	// Work load: unlocks in 9–18 + minutes in work apps
	if w, ok := sumHours(d.HourlyUnlocks, 9, 18); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			v, ok := sumHours(x.HourlyUnlocks, 9, 18)
			return float64(v), ok
		})
		workMin, names := workMinutes(d, workApps, labelOf)
		if n >= 2 && w >= 20 && float64(w) >= usual*1.4 {
			detail := fmt.Sprintf("%d разблокировок с 9 до 18 — обычно %d", w, round(usual))
			if workMin >= 30 {
				detail += fmt.Sprintf("; %s %s", strings.Join(names, ", "), minutes(workMin))
			}
			add(Signal{Key: "work_load", Title: "Насыщенный рабочий день", Detail: detail, strength: float64(w) / math.Max(usual, 1)})
		} else if workMin >= 90 {
			add(Signal{Key: "work_load", Title: "Много рабочих приложений", Detail: fmt.Sprintf("%s — %s", strings.Join(names, ", "), minutes(workMin)), strength: float64(workMin) / 90})
		}
	}

	// Morning storm: 7–12 (Арина: «с утра 15 звонков — через 2 часа настроение не очень»)
	if m, ok := sumHours(d.HourlyUnlocks, 7, 12); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			v, ok := sumHours(x.HourlyUnlocks, 7, 12)
			return float64(v), ok
		})
		if n >= 2 && m >= 15 && float64(m) >= usual*1.5 {
			add(Signal{Key: "morning_storm", Title: "Плотное утро", Detail: fmt.Sprintf("%d разблокировок до полудня — обычно %d", m, round(usual)), strength: float64(m) / math.Max(usual, 1)})
		}
	}

	// Calls: time in the dialer / in-call screen
	if c := callMinutes(d); c >= 30 {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return float64(callMinutes(x)), true })
		if n < 2 || float64(c) >= usual*1.5 {
			add(Signal{Key: "calls", Title: "День звонков", Detail: fmt.Sprintf("в звонках %s", minutes(c)), strength: float64(c) / 30})
		}
	}

	// Late phone: last unlock after 00:30
	if lu, ok := clock(d.LastUnlock); ok && lu >= 24*60+30 {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { v, ok := clock(x.LastUnlock); return float64(v), ok })
		if n < 2 || float64(lu) >= usual+45 {
			detail := "последнее разблокирование в " + *d.LastUnlock
			if n >= 2 {
				detail += ", обычно " + hhmm(round(usual))
			}
			add(Signal{Key: "late_phone", Title: "Телефон после полуночи", Detail: detail, strength: 1 + float64(lu-24*60)/60})
		}
	}

	// Sleep vs norm
	if d.SleepMin != nil {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.SleepMin) })
		if n >= 2 {
			diff := float64(*d.SleepMin) - usual
			switch {
			case diff <= -60:
				add(Signal{Key: "short_sleep", Title: "Короткая ночь", Detail: fmt.Sprintf("сон %s — на %s меньше обычного", minutes(*d.SleepMin), minutes(round(-diff))), strength: -diff / 60})
			case diff >= 60:
				add(Signal{Key: "long_sleep", Title: "Выспался", Detail: fmt.Sprintf("сон %s — на %s больше обычного", minutes(*d.SleepMin), minutes(round(diff))), Positive: true, strength: diff / 60})
			}
		}
	}

	// Movement vs the person's norm. Steps come from Health Connect — most phones count them
	// even without a watch. 0 means "no data" (no Health Connect), not "didn't move".
	stepsOf := func(x *repo.DailyData) (float64, bool) {
		if x.Steps == nil || *x.Steps <= 0 {
			return 0, false
		}
		return float64(*x.Steps), true
	}
	if st, ok := stepsOf(d); ok {
		usual, n := avg(base, stepsOf)
		switch {
		case n >= 2 && usual >= 3000 && st <= usual*0.5:
			add(Signal{Key: "low_move", Title: "Мало движения", Detail: fmt.Sprintf("%s шагов — обычно %s", thousands(int(st)), thousands(round(usual))), strength: usual / math.Max(st, 1)})
		case n >= 2 && st >= 8000 && st >= usual*1.5:
			add(Signal{Key: "active_day", Title: "Много движения", Detail: fmt.Sprintf("%s шагов — обычно %s", thousands(int(st)), thousands(round(usual))), Positive: true, strength: st / math.Max(usual, 1)})
		}
	}
	// A walk: one hour with 2000+ steps (≈15–20 minutes of walking)
	if h, v := peakHour(d.HourlySteps); v >= 2000 {
		add(Signal{Key: "walk", Title: "Была прогулка", Detail: fmt.Sprintf("%s шагов с %02d до %02d", thousands(v), h, (h+1)%24), Positive: true, strength: float64(v) / 2000})
	}
	// A still morning: almost no steps before noon when usually there are some
	if m, ok := sumHours(d.HourlySteps, 6, 12); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			v, ok := sumHours(x.HourlySteps, 6, 12)
			return float64(v), ok
		})
		if n >= 2 && usual >= 1000 && m < 300 {
			add(Signal{Key: "still_morning", Title: "Утро без движения", Detail: fmt.Sprintf("до полудня %d шагов — обычно %s", m, thousands(round(usual))), strength: usual / 1000})
		}
	}

	// Phone right after waking up (or not)
	if w, ok1 := clock(d.Wakeup); ok1 {
		if f, ok2 := clock(d.FirstUnlock); ok2 && f >= w {
			switch delta := f - w; {
			case delta <= 5:
				add(Signal{Key: "morning_grab", Title: "Телефон сразу после подъёма", Detail: fmt.Sprintf("проснулся в %s, разблокировал в %s", *d.Wakeup, *d.FirstUnlock), strength: 1})
			case delta >= 40:
				add(Signal{Key: "calm_morning", Title: "Утро без телефона", Detail: fmt.Sprintf("первые %s после подъёма без телефона", minutes(delta)), Positive: true, strength: float64(delta) / 40})
			}
		}
	}

	// Jumpy day overall / calm day by screen
	if d.Unlocks != nil {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.Unlocks) })
		if n >= 2 && *d.Unlocks >= 40 && float64(*d.Unlocks) >= usual*1.5 && !has(out, "work_load") {
			add(Signal{Key: "jumpy", Title: "Дёрганый день", Detail: fmt.Sprintf("%d разблокировок — обычно %d", *d.Unlocks, round(usual)), strength: float64(*d.Unlocks) / math.Max(usual, 1)})
		}
	}
	if d.ScreenMin != nil {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.ScreenMin) })
		if n >= 2 && usual >= 60 && float64(*d.ScreenMin) <= usual*0.7 {
			add(Signal{Key: "calm_screen", Title: "Спокойный по экрану день", Detail: fmt.Sprintf("экран %s — обычно %s", minutes(*d.ScreenMin), minutes(round(usual))), Positive: true, strength: usual / math.Max(float64(*d.ScreenMin), 1)})
		}
	}

	sort.SliceStable(out, func(i, j int) bool { return out[i].strength > out[j].strength })
	if len(out) > maxSignals {
		out = out[:maxSignals]
	}
	return out
}

// PromptBlock renders signals for the model.
func PromptBlock(s []Signal) string {
	if len(s) == 0 {
		return "Что было заметно вчера: ничего необычного, день ровный по твоим же меркам.\n\n"
	}
	var b strings.Builder
	b.WriteString("Что было заметно вчера (посчитано по данным — опирайся на это, ничего не выдумывай):\n")
	for _, x := range s {
		fmt.Fprintf(&b, "  - %s: %s\n", x.Title, x.Detail)
	}
	b.WriteString("\n")
	return b.String()
}

func has(s []Signal, key string) bool {
	for _, x := range s {
		if x.Key == key {
			return true
		}
	}
	return false
}

func workMinutes(d *repo.DailyData, workApps map[string]bool, labelOf func(string) string) (int, []string) {
	total := 0
	var names []string
	for _, a := range d.TopApps {
		if workApps[a.Package] || knownWorkApps[a.Package] != "" {
			total += a.Minutes
			names = append(names, labelOf(a.Package))
		}
	}
	return total, names
}

func callMinutes(d *repo.DailyData) int {
	total := 0
	for _, a := range d.TopApps {
		if callApps[a.Package] {
			total += a.Minutes
		}
	}
	return total
}

// sumHours sums h[from..to) if the series is present.
func sumHours(h []int, from, to int) (int, bool) {
	if len(h) != 24 {
		return 0, false
	}
	s := 0
	for i := from; i < to; i++ {
		s += h[i]
	}
	return s, true
}

func avg(days []*repo.DailyData, f func(*repo.DailyData) (float64, bool)) (float64, int) {
	sum, n := 0.0, 0
	for _, d := range days {
		if v, ok := f(d); ok {
			sum += v
			n++
		}
	}
	if n == 0 {
		return 0, 0
	}
	return sum / float64(n), n
}

func ptrf(p *int) (float64, bool) {
	if p == nil {
		return 0, false
	}
	return float64(*p), true
}

// clock parses "HH:MM" into minutes; times before 05:00 count as after midnight (24:xx).
func clock(s *string) (int, bool) {
	if s == nil {
		return 0, false
	}
	h, m, ok := strings.Cut(*s, ":")
	if !ok {
		return 0, false
	}
	hh, err1 := strconv.Atoi(h)
	mm, err2 := strconv.Atoi(m)
	if err1 != nil || err2 != nil {
		return 0, false
	}
	v := hh*60 + mm
	if hh < 5 {
		v += 24 * 60
	}
	return v, true
}

func hhmm(v int) string { return fmt.Sprintf("%02d:%02d", (v/60)%24, v%60) }

// peakHour returns the hour with the most steps and its value (0 if no series).
func peakHour(h []int) (int, int) {
	if len(h) != 24 {
		return 0, 0
	}
	best := 0
	for i, v := range h {
		if v > h[best] {
			best = i
		}
	}
	return best, h[best]
}

// thousands formats 7240 as "7 240" (Russian style, thin no-break space).
func thousands(v int) string {
	s := strconv.Itoa(v)
	if len(s) <= 4 {
		return s
	}
	var b strings.Builder
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteRune('\u202f')
		}
		b.WriteRune(c)
	}
	return b.String()
}

func round(v float64) int { return int(math.Round(v)) }

func minutes(m int) string {
	switch {
	case m < 60:
		return fmt.Sprintf("%d м", m)
	case m%60 == 0:
		return fmt.Sprintf("%d ч", m/60)
	default:
		return fmt.Sprintf("%d ч %d м", m/60, m%60)
	}
}

// Formatting shared with the explore package (same wording everywhere).

// FormatMinutes renders 95 as "1 ч 35 м".
func FormatMinutes(m int) string { return minutes(m) }

// FormatCount renders 12400 as "12 400".
func FormatCount(v int) string { return thousands(v) }

// ClockMinutes parses "HH:MM" into minutes; times before 05:00 count as after midnight.
func ClockMinutes(s *string) (int, bool) { return clock(s) }

// FormatClock renders minutes (possibly past 24:00) as "HH:MM".
func FormatClock(v int) string { return hhmm(v) }

// IsWorkApp reports whether a package is a well-known work app (Slack, Zoom, Битрикс24…).
func IsWorkApp(pkg string) bool { return knownWorkApps[pkg] != "" }
