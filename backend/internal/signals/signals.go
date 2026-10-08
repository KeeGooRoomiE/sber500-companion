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

// maxSignals caps what reaches the person — both the «На чём основано» list and the evidence
// block in the prompt.
//
// Was 4 while there were thirteen signals. Adding the behavioural ones made the cap the thing
// that decided what gets said: checking_day alone displaced «Плотное утро» from a day that
// genuinely had both, and an existing explore test caught it. Six leaves room for a second
// true thing about a day without drowning a two-sentence forecast in evidence.
const maxSignals = 6

// minMeaningfulScreen: below this the day has no data worth describing rather than a quiet one.
const minMeaningfulScreen = 15

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

	// A chain across days, not a single day against the norm — the one thing people describe
	// about themselves that no number here was showing. Interview #20 put it exactly:
	// «Отложить телефон после двенадцати — довольно фатальное решение, которое в силах
	// определить течение последующих нескольких дней.»
	if c := lateNightRun(days); c != nil {
		add(*c)
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
		// A near-empty day is missing data, not a calm one. Someone who installed yesterday
		// evening had «экран 0 м — обычно 3 ч 34 м» as their only signal, which reads as an
		// observation about them and is an artefact of the day not having happened.
		if n >= 2 && usual >= 60 && *d.ScreenMin >= minMeaningfulScreen && float64(*d.ScreenMin) <= usual*0.7 {
			add(Signal{Key: "calm_screen", Title: "Спокойный по экрану день", Detail: fmt.Sprintf("экран %s — обычно %s", minutes(*d.ScreenMin), minutes(round(usual))), Positive: true, strength: usual / math.Max(float64(*d.ScreenMin), 1)})
		}
	}

	// ─── Форма дня, а не объём ────────────────────────────────────────────────
	// Всё ниже считается из почасовых массивов и границ дня. Они заполнены у 100% и 87%
	// пользователей соответственно, в отличие от сна (11%) — см. SIGNALS.md.

	// Average session length: screen minutes per unlock. The same unlock count means
	// opposite things at different session lengths — thirty one-minute glances are not
	// thirty deliberate sessions, and `jumpy` cannot tell them apart because it counts
	// only unlocks.
	if sess, ok := sessionLen(d); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return sessionLen(x) })
		if n >= 2 && usual > 0 {
			switch r := sess / usual; {
			case r <= 0.6 && *d.Unlocks >= 30:
				add(Signal{Key: "checking_day", Title: "День заглядываний",
					Detail: fmt.Sprintf("в среднем %s за раз — обычно %s, а разблокировок %d",
						minutesF(sess), minutesF(usual), *d.Unlocks),
					strength: usual / math.Max(sess, 0.1)})
			case r >= 1.8 && sess >= 8:
				add(Signal{Key: "immersed_day", Title: "Долгие посадки",
					Detail:   fmt.Sprintf("в среднем %s за раз — обычно %s", minutesF(sess), minutesF(usual)),
					strength: r})
			}
		}
	}

	// Woke at the usual hour, looked at the phone briefly, went back to sleep: a short burst,
	// then at least one completely empty hour, then the day proper. The empty hour is what
	// makes it a false start rather than an ordinary morning — hour buckets are coarse, so
	// without it a glance at 06:55 and a real start at 07:05 are indistinguishable.
	if h, ok := falseStart(d.HourlyScreen); ok {
		add(Signal{Key: "false_start", Title: "Проснулся и уснул снова",
			Detail:   fmt.Sprintf("в %02d:00 несколько минут в телефоне, потом час тишины — день начался позже", h),
			strength: 2.2})
	}

	// Unlocks between 01:00 and 06:00 — «просыпался ночью и проверял телефон». Deliberately
	// not tied to the sleep window: that needs Health Connect, which 11% have, while this
	// works for everyone.
	if n := nightWakings(d.HourlyUnlocks); n >= 2 {
		usual, m := avg(base, func(x *repo.DailyData) (float64, bool) {
			if len(x.HourlyUnlocks) != 24 {
				return 0, false
			}
			return float64(nightWakings(x.HourlyUnlocks)), true
		})
		if m >= 2 && float64(n) >= math.Max(usual*2, 2) {
			detail := fmt.Sprintf("%d %s между часом и шестью утра", n, plural(n, "разблокировка", "разблокировки", "разблокировок"))
			if usual < 0.5 {
				detail += " — обычно ночью телефон не трогаешь"
			}
			add(Signal{Key: "night_checks", Title: "Ночные проверки телефона", Detail: detail,
				strength: 1.8 + float64(n)/3})
		}
	}

	// How long the person was up, by the phone's evidence. A proxy for the waking window at
	// 87% coverage, where real wakeup/bedtime reach 11%. Not the same thing — the phone is
	// also untouched while awake — but it compares like for like against the person's norm.
	if span, ok := daySpan(d); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return daySpan(x) })
		if n >= 2 {
			switch diff := span - usual; {
			case diff >= 90:
				add(Signal{Key: "long_day", Title: "Длинный день",
					Detail: fmt.Sprintf("между первым и последним разблокированием %s — на %s больше обычного",
						minutes(round(span)), minutes(round(diff))),
					strength: diff / 90})
			case diff <= -90:
				add(Signal{Key: "short_day", Title: "Короткий день", Positive: true,
					Detail: fmt.Sprintf("между первым и последним разблокированием %s — на %s меньше обычного",
						minutes(round(span)), minutes(round(-diff))),
					strength: -diff / 90})
			}
		}
	}

	// A quiet phone day means opposite things depending on the legs: out and about, or at a
	// desk all day. Screen alone cannot tell them apart; steps can. Only fires when both the
	// screen is below the person's norm and steps are clearly on one side of theirs.
	if d.ScreenMin != nil && d.Steps != nil && *d.Steps > 0 {
		usualScreen, nS := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.ScreenMin) })
		usualSteps, nW := avg(base, func(x *repo.DailyData) (float64, bool) {
			if x.Steps == nil || *x.Steps <= 0 {
				return 0, false
			}
			return float64(*x.Steps), true
		})
		quiet := nS >= 2 && usualScreen >= 60 && float64(*d.ScreenMin) <= usualScreen*0.7 && *d.ScreenMin >= minMeaningfulScreen
		if quiet && nW >= 2 && usualSteps >= 2000 {
			switch st := float64(*d.Steps); {
			case st >= usualSteps*1.5:
				add(Signal{Key: "away_day", Title: "День на ногах", Positive: true,
					Detail: fmt.Sprintf("экран %s вместо обычных %s, зато %s шагов",
						minutes(*d.ScreenMin), minutes(round(usualScreen)), thousands(*d.Steps)),
					strength: 1.6 + st/math.Max(usualSteps, 1)})
			case st <= usualSteps*0.6:
				add(Signal{Key: "desk_day", Title: "День на месте",
					Detail: fmt.Sprintf("и экран ниже обычного (%s), и шагов мало — %s",
						minutes(*d.ScreenMin), thousands(*d.Steps)),
					strength: 1.6 + usualSteps/math.Max(st, 1)})
			}
		}
	}

	// Longest unbroken stretch of screen hours, and the longest waking hour without any.
	// Two sides of the same array: what the day was spent in, and whether it had a pause.
	if run, start := longestRun(d.HourlyScreen, 20); run >= 3 {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			r, _ := longestRun(x.HourlyScreen, 20)
			return float64(r), len(x.HourlyScreen) == 24
		})
		if n >= 2 && float64(run) >= usual*1.5 {
			add(Signal{Key: "long_stretch", Title: "Долгий отрезок без пауз",
				Detail:   fmt.Sprintf("с %02d:00 примерно %d %s подряд в телефоне", start, run, plural(run, "час", "часа", "часов")),
				strength: 1.4 + float64(run)/3})
		}
	}
	// A real pause during the day. Positive: the person put the phone down.
	if gap, start, ok := longestGap(d.HourlyScreen, 9, 22); ok && gap >= 4 {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			g, _, ok := longestGap(x.HourlyScreen, 9, 22)
			return float64(g), ok
		})
		if n >= 2 && float64(gap) >= math.Max(usual*1.6, 4) {
			add(Signal{Key: "quiet_block", Title: "Долгая пауза без телефона", Positive: true,
				Detail:   fmt.Sprintf("с %02d:00 примерно %d %s не брал телефон", start, gap, plural(gap, "час", "часа", "часов")),
				strength: 1.3 + float64(gap)/4})
		}
	}
	// Not one hour of the working day without the phone.
	if busy, ok := hoursWithScreen(d.HourlyScreen, 9, 19); ok && busy == 10 {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) {
			v, ok := hoursWithScreen(x.HourlyScreen, 9, 19)
			return float64(v), ok
		})
		// «Обычно хотя бы одна пауза есть, а вчера не было» — это и есть утверждение.
		// Порог 8.5 требовал полутора пустых часов в среднем и поэтому не срабатывал никогда.
		if n >= 2 && usual <= 9.3 {
			add(Signal{Key: "no_break", Title: "Рабочий день без пауз",
				Detail: "с девяти до семи не было ни одного часа без телефона", strength: 1.5})
		}
	}
	// An evening truly away from the phone. Positive, and deliberately separate from
	// quiet_block: the evening is when people say they want the phone down.
	if gap, start, ok := longestGap(d.HourlyScreen, 18, 24); ok && gap >= 3 {
		add(Signal{Key: "evening_gap", Title: "Вечер без телефона", Positive: true,
			Detail:   fmt.Sprintf("с %02d:00 примерно %d %s без экрана", start, gap, plural(gap, "час", "часа", "часов")),
			strength: 1.5 + float64(gap)/3})
	}

	// The day's centre of mass moved. Same totals, different day.
	if cm, ok := centreOfMass(d); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return centreOfMass(x) })
		if n >= 2 {
			if diff := cm - usual; math.Abs(diff) >= 2 {
				word, title := "позже", "День сместился на вечер"
				if diff < 0 {
					word, title = "раньше", "День сместился на утро"
				}
				add(Signal{Key: "day_shift", Title: title,
					Detail: fmt.Sprintf("основное время в телефоне примерно на %.0f %s %s обычного",
						math.Abs(diff), plural(int(math.Abs(diff)), "час", "часа", "часов"), word),
					strength: 1.3 + math.Abs(diff)/2})
			}
		}
	}

	// The day started later than usual, by the phone rather than by Health Connect.
	if f, ok := wakeUnlock(d); ok {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return wakeUnlock(x) })
		if n >= 2 && f-usual >= 75 && !has(out, "false_start") && !has(out, "night_checks") {
			add(Signal{Key: "late_start", Title: "Позднее начало дня",
				Detail:   fmt.Sprintf("первая разблокировка в %s — обычно около %s", *d.FirstUnlock, hhmm(round(usual))),
				strength: (float64(f) - usual) / 75})
		}
	}

	// How scattered the week's wake-ups are. Both directions: a steady rhythm is one of the
	// few good things we can say to someone without Health Connect.
	if sd, n := spreadOfFirstUnlock(days); n >= 5 {
		switch {
		case sd >= 75:
			add(Signal{Key: "start_jitter", Title: "Режим гуляет",
				Detail:   fmt.Sprintf("за неделю подъём разъезжается примерно на %s", minutes(round(sd))),
				strength: 1.2 + sd/90})
		case sd <= 12:
			// Low weight on purpose. A steady week is worth saying, but it is a fact about
			// the week, not about yesterday, and it must never outrank something that
			// actually happened. The personas make this visible: most of them hold the wake
			// time constant, so a looser threshold fired for almost everyone — and a signal
			// that fires for everyone says nothing.
			add(Signal{Key: "steady_rhythm", Title: "Ровный ритм недели", Positive: true,
				Detail:   fmt.Sprintf("всю неделю встаёшь почти в одно время, разброс около %s", minutes(round(sd))),
				strength: 1.05})
		}
	}

	// Bedtime creeping later night after night, without ever crossing midnight — which is
	// exactly the case late_night_run cannot see, because it only counts nights past 00:30.
	if n, drift := bedtimeDrift(days); n >= 4 && !has(out, "late_night_run") {
		add(Signal{Key: "bedtime_drift", Title: "Отбой сползает",
			Detail: fmt.Sprintf("%d %s подряд ложишься позже предыдущего — всего на %s",
				n, plural(n, "вечер", "вечера", "вечеров"), minutes(round(drift))),
			strength: 1.6 + float64(n)/4})
	}

	// An app in the top that is usually not there at all, and a day eaten by a single app.
	// Not for work or call apps: those already have their own signals, and saying the same
	// app twice in one forecast reads as the model repeating itself.
	if pkg, min := newcomerApp(d, base); min >= 60 && labelOf(pkg) != "" && isTopTwo(d, pkg) &&
		!workApps[pkg] && knownWorkApps[pkg] == "" && !callApps[pkg] {
		add(Signal{Key: "new_app", Title: "Новое в топе",
			Detail:   fmt.Sprintf("%s — %s, обычно этого приложения в топе нет", labelOf(pkg), minutes(min)),
			strength: 1.3 + float64(min)/90})
	}
	if d.ScreenMin != nil && *d.ScreenMin >= 90 && len(d.TopApps) > 0 {
		top := d.TopApps[0]
		if share := float64(top.Minutes) / float64(*d.ScreenMin); share >= 0.55 {
			add(Signal{Key: "one_app_day", Title: "День одного приложения",
				Detail:   fmt.Sprintf("%s — %s, больше половины всего экрана", labelOf(top.Package), minutes(top.Minutes)),
				strength: 1.2 + share})
		}
	}

	// Screen and steps both high: the phone was in motion, not on the sofa. Stops «много
	// экрана» from automatically reading as «залипал».
	if d.ScreenMin != nil && d.Steps != nil && *d.Steps > 0 {
		usualScreen, nS := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.ScreenMin) })
		usualSteps, nW := avg(base, func(x *repo.DailyData) (float64, bool) {
			if x.Steps == nil || *x.Steps <= 0 {
				return 0, false
			}
			return float64(*x.Steps), true
		})
		if nS >= 2 && nW >= 2 && usualSteps >= 2000 &&
			float64(*d.ScreenMin) >= usualScreen*1.3 && float64(*d.Steps) >= usualSteps*1.6 {
			add(Signal{Key: "commute_screen", Title: "Телефон в движении",
				Detail: fmt.Sprintf("экран выше обычного (%s), но и %s шагов — это не диван",
					minutes(*d.ScreenMin), thousands(*d.Steps)),
				strength: 1.5})
		}
	}
	// The longest run of waking hours with almost no steps.
	if run, start := sedentaryRun(d.HourlySteps, 9, 22, 100); run >= 4 {
		add(Signal{Key: "sedentary_streak", Title: "Долго на одном месте",
			Detail:   fmt.Sprintf("с %02d:00 примерно %d %s почти без шагов", start, run, plural(run, "час", "часа", "часов")),
			strength: 1.3 + float64(run)/5})
	}

	// Morning battery far below the usual — the phone did not go on charge, which usually
	// means the evening did not go as usual either.
	if d.BatteryMorning != nil {
		usual, n := avg(base, func(x *repo.DailyData) (float64, bool) { return ptrf(x.BatteryMorning) })
		if n >= 2 && usual >= 60 && float64(*d.BatteryMorning) <= usual*0.4 {
			add(Signal{Key: "not_charged", Title: "Телефон не ставил на зарядку",
				Detail:   fmt.Sprintf("утром %d%% — обычно около %d%%", *d.BatteryMorning, round(usual)),
				strength: 1.4})
		}
	}

	sort.SliceStable(out, func(i, j int) bool { return out[i].strength > out[j].strength })
	if len(out) > maxSignals {
		out = out[:maxSignals]
	}
	return out
}

// lateNightRun reports a run of consecutive nights that ended past midnight, and what the
// sleep after them did.
//
// Every other signal compares one day with the person's norm. This one is about the sequence:
// a single late night says little, three in a row is the thing people recognise in themselves.
// Only fires when the run reaches the last day — a streak that ended last week is history, not
// something to say this morning.
func lateNightRun(days []*repo.DailyData) *Signal {
	const lateAfter = 24*60 + 30 // 00:30, same threshold as the single-day signal

	isLate := func(x *repo.DailyData) bool {
		lu, ok := clock(x.LastUnlock)
		return ok && lu >= lateAfter
	}

	run := 0
	for i := len(days) - 1; i >= 0 && isLate(days[i]); i-- {
		run++
	}
	if run < 2 {
		return nil
	}

	// What the nights of the run did to sleep, against the days before it.
	inRun := days[len(days)-run:]
	before := days[:len(days)-run]
	runSleep, nRun := avg(inRun, func(x *repo.DailyData) (float64, bool) { return ptrf(x.SleepMin) })
	baseSleep, nBase := avg(before, func(x *repo.DailyData) (float64, bool) { return ptrf(x.SleepMin) })

	detail := fmt.Sprintf("%d %s подряд экран гас после полуночи", run, plural(run, "вечер", "вечера", "вечеров"))
	if nRun >= 1 && nBase >= 2 && baseSleep-runSleep >= 20 {
		detail += fmt.Sprintf(" — сон в эти ночи короче обычного на %s", minutes(round(baseSleep-runSleep)))
	}

	return &Signal{
		Key:   "late_night_run",
		Title: fmt.Sprintf("%d-й вечер подряд за полночь", run),
		// Ranked above one-off deviations on purpose: a chain is the more interesting fact,
		// and with only four slots it has to out-rank them to be said at all.
		Detail:   detail,
		strength: 2 + float64(run),
	}
}

// plural picks the Russian form for a count: 1 вечер, 2 вечера, 5 вечеров.
func plural(n int, one, few, many string) string {
	m10, m100 := n%10, n%100
	switch {
	case m100 >= 11 && m100 <= 14:
		return many
	case m10 == 1:
		return one
	case m10 >= 2 && m10 <= 4:
		return few
	default:
		return many
	}
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

// sessionLen is the average minutes of screen per unlock — the length of a typical sitting.
// Research calls the short end «checking habits»; it separates people better than either
// number on its own (see RESEARCH_USAGE.md).
func sessionLen(d *repo.DailyData) (float64, bool) {
	if d.ScreenMin == nil || d.Unlocks == nil || *d.Unlocks <= 0 || *d.ScreenMin <= 0 {
		return 0, false
	}
	return float64(*d.ScreenMin) / float64(*d.Unlocks), true
}

// daySpan is the minutes between the first and the last unlock.
func daySpan(d *repo.DailyData) (float64, bool) {
	f, ok1 := clock(d.FirstUnlock)
	l, ok2 := clock(d.LastUnlock)
	if !ok1 || !ok2 || l <= f {
		return 0, false
	}
	return float64(l - f), true
}

// falseStart finds a brief early-morning burst followed by a fully empty hour and then a day
// that actually happened. Returns the hour of the burst.
//
// The empty hour is not optional: hourly buckets are an hour wide, so a glance at 06:55 and a
// real start at 07:05 land in neighbouring buckets and look exactly like waking up normally.
func falseStart(h []int) (int, bool) {
	if len(h) != 24 {
		return 0, false
	}
	for hour := 4; hour <= 9; hour++ {
		if h[hour] < 1 || h[hour] > 10 || h[hour+1] != 0 {
			continue
		}
		// The burst has to be the start of the day, not a waking in the middle of the night:
		// without this a 02:00 check followed by a quiet 03:00 reads as a false start.
		quietBefore := true
		for i := hour - 3; i < hour; i++ {
			if i >= 0 && h[i] != 0 {
				quietBefore = false
			}
		}
		if !quietBefore {
			continue
		}
		rest := 0
		for i := hour + 2; i < 24; i++ {
			rest += h[i]
		}
		if rest >= 60 {
			return hour, true
		}
	}
	return 0, false
}

// minutesF renders a fractional number of minutes: 1.5 as «1.5 м», 18.4 as «18 м».
func minutesF(v float64) string {
	if v < 10 {
		return strings.TrimSuffix(fmt.Sprintf("%.1f", v), ".0") + " м"
	}
	return minutes(round(v))
}

// nightWakings counts unlocks between 01:00 and 05:00 that follow a quiet hour.
//
// The quiet hour is the whole point: it separates waking up and reaching for the phone from
// simply not having gone to bed yet. A night owl whose screen is still on at 01:30 is not
// having a restless night, and counting them the same way makes the signal say the opposite
// of what happened.
func nightWakings(h []int) int {
	if len(h) != 24 {
		return 0
	}
	n := 0
	for hour := 1; hour <= 4; hour++ {
		if h[hour] > 0 && h[hour-1] == 0 {
			n += h[hour]
		}
	}
	return n
}

// longestRun returns the longest run of consecutive hours holding at least `min` minutes of
// screen, and the hour it starts. The day is treated as wrapping past midnight: an evening
// that runs to 01:00 is one sitting, not two.
func longestRun(h []int, min int) (int, int) {
	if len(h) != 24 {
		return 0, 0
	}
	best, bestStart, run, start := 0, 0, 0, 0
	for i := 0; i < 48; i++ {
		if h[i%24] >= min {
			if run == 0 {
				start = i % 24
			}
			run++
			if run > best && run <= 24 {
				best, bestStart = run, start
			}
		} else {
			run = 0
		}
	}
	return best, bestStart
}

// longestGap returns the longest run of hours in [from, to) with no screen at all.
func longestGap(h []int, from, to int) (int, int, bool) {
	if len(h) != 24 || from < 0 || to > 24 || from >= to {
		return 0, 0, false
	}
	best, bestStart, run, start := 0, 0, 0, 0
	for i := from; i < to; i++ {
		if h[i] == 0 {
			if run == 0 {
				start = i
			}
			run++
			if run > best {
				best, bestStart = run, start
			}
		} else {
			run = 0
		}
	}
	return best, bestStart, true
}

// hoursWithScreen counts hours in [from, to) that hold any screen time.
func hoursWithScreen(h []int, from, to int) (int, bool) {
	if len(h) != 24 {
		return 0, false
	}
	n := 0
	for i := from; i < to && i < 24; i++ {
		if h[i] > 0 {
			n++
		}
	}
	return n, true
}

// centreOfMass is the minute-weighted average hour of the day's screen time: one number for
// «когда именно» the day happened. Hours before 05:00 count as the previous evening, so a day
// ending at 01:00 does not drag the centre back to the morning.
func centreOfMass(d *repo.DailyData) (float64, bool) {
	h := d.HourlyScreen
	if len(h) != 24 {
		return 0, false
	}
	sum, weight := 0.0, 0.0
	for i, v := range h {
		if v == 0 {
			continue
		}
		hour := float64(i)
		if i < 5 {
			hour += 24
		}
		sum += hour * float64(v)
		weight += float64(v)
	}
	if weight < 30 {
		return 0, false
	}
	return sum / weight, true
}

// spreadOfFirstUnlock is the mean absolute deviation of the week's wake-up times, in minutes.
func spreadOfFirstUnlock(days []*repo.DailyData) (float64, int) {
	var v []float64
	for _, d := range days {
		if f, ok := wakeUnlock(d); ok {
			v = append(v, f)
		}
	}
	if len(v) < 3 {
		return 0, len(v)
	}
	mean := 0.0
	for _, x := range v {
		mean += x
	}
	mean /= float64(len(v))
	dev := 0.0
	for _, x := range v {
		dev += math.Abs(x - mean)
	}
	return dev / float64(len(v)), len(v)
}

// bedtimeDrift counts the run of consecutive nights, ending with the last day, each of which
// finished later than the one before, and by how much in total.
//
// late_night_run only sees nights past 00:30, so a week that creeps from 22:10 to 23:58 is
// invisible to it — and that is precisely the week worth mentioning, before it crosses over.
func bedtimeDrift(days []*repo.DailyData) (int, float64) {
	last := len(days) - 1
	if last < 1 {
		return 0, 0
	}
	run := 0
	for i := last; i > 0; i-- {
		cur, ok1 := clock(days[i].LastUnlock)
		prev, ok2 := clock(days[i-1].LastUnlock)
		if !ok1 || !ok2 || cur-prev < 5 {
			break
		}
		run++
	}
	if run == 0 {
		return 0, 0
	}
	first, ok1 := clock(days[last-run].LastUnlock)
	end, ok2 := clock(days[last].LastUnlock)
	if !ok1 || !ok2 {
		return 0, 0
	}
	return run, float64(end - first)
}

// newcomerApp finds the top app of the day that does not appear in the days before it.
func newcomerApp(d *repo.DailyData, base []*repo.DailyData) (string, int) {
	if len(base) < 3 {
		return "", 0
	}
	seen := map[string]bool{}
	for _, x := range base {
		for _, a := range x.TopApps {
			seen[a.Package] = true
		}
	}
	bestPkg, bestMin := "", 0
	for _, a := range d.TopApps {
		if !seen[a.Package] && a.Minutes > bestMin {
			bestPkg, bestMin = a.Package, a.Minutes
		}
	}
	return bestPkg, bestMin
}

// sedentaryRun is the longest run of hours in [from, to) with fewer than `max` steps.
func sedentaryRun(h []int, from, to, max int) (int, int) {
	if len(h) != 24 {
		return 0, 0
	}
	best, bestStart, run, start := 0, 0, 0, 0
	for i := from; i < to && i < 24; i++ {
		if h[i] < max {
			if run == 0 {
				start = i
			}
			run++
			if run > best {
				best, bestStart = run, start
			}
		} else {
			run = 0
		}
	}
	return best, bestStart
}

// wakeUnlock is the first unlock that plausibly starts the day, in minutes.
//
// clock() counts anything before 05:00 as the previous night (24:xx), which is right for a
// last unlock and wrong for a first one: a 02:10 night check would otherwise read as waking
// up at 26:10, i.e. nineteen hours late.
func wakeUnlock(d *repo.DailyData) (float64, bool) {
	f, ok := clock(d.FirstUnlock)
	if !ok || f >= 24*60 {
		return 0, false
	}
	return float64(f), true
}

// isTopTwo reports whether the package is among the two most used apps of the day.
func isTopTwo(d *repo.DailyData, pkg string) bool {
	for i, a := range d.TopApps {
		if i == 2 {
			return false
		}
		if a.Package == pkg {
			return true
		}
	}
	return false
}
