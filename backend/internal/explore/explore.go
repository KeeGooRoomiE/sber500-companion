// Package explore answers «Хочу ещё»: follow-up questions about the person's own data.
//
// Everything here is deterministic. It decides which questions the data can actually answer
// and computes the facts for each (with numbers and dates). The model only puts the facts into
// words, and the app shows the same facts under the answer — like signals for the forecast.
// The centrepiece is «похожие дни»: past days that stood out the same way as yesterday, and
// how the person lived them and the day after.
package explore

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

// Question is one follow-up the app can offer.
type Question struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

// Answerable is a question with the facts that answer it.
type Answerable struct {
	Question
	Facts []string
}

// Input: days oldest first, ending yesterday (up to a month); check-ins by "2006-01-02".
type Input struct {
	Days     []*repo.DailyData
	CheckIns map[string]*repo.CheckIn
	WorkApps map[string]bool
	LabelOf  func(string) string
}

// Build returns every question the data can answer, most interesting first.
func Build(in Input) []Answerable {
	if len(in.Days) == 0 {
		return nil
	}
	var out []Answerable
	for _, f := range []func(Input) *Answerable{similarDays, whyYesterday, bestDays, phoneAndSleep, movement, workDays} {
		if a := f(in); a != nil && len(a.Facts) > 0 {
			out = append(out, *a)
		}
	}
	return out
}

// Find returns the question with this id if the data can answer it.
func Find(in Input, id string) *Answerable {
	for _, a := range Build(in) {
		if a.ID == id {
			return &a
		}
	}
	return nil
}

// ─── «Как у меня обычно проходят такие дни?» ─────────────────────────────────

func similarDays(in Input) *Answerable {
	days := in.Days
	if len(days) < 5 {
		return nil
	}
	sigOf := func(i int) []signals.Signal {
		from := i - 7
		if from < 0 {
			from = 0
		}
		return signals.ForLastDay(days[from:i+1], in.WorkApps, in.LabelOf)
	}
	last := len(days) - 1
	target := sigOf(last)
	if len(target) == 0 {
		return nil
	}
	want := map[string]string{}
	for _, s := range target {
		want[s.Key] = s.Title
	}

	type match struct {
		i      int
		shared []string
	}
	var found []match
	for i := last - 1; i >= 3 && len(found) < 5; i-- {
		var shared []string
		for _, s := range sigOf(i) {
			if t, ok := want[s.Key]; ok {
				shared = append(shared, t)
			}
		}
		// Same leading signal, or at least half of yesterday's signals
		if len(shared) > 0 && (contains(shared, target[0].Title) || len(shared)*2 >= len(target)) {
			found = append(found, match{i, shared})
		}
	}
	if len(found) == 0 {
		return nil
	}

	a := &Answerable{Question: Question{ID: "similar", Text: "Как у меня обычно проходят такие дни?"}}
	var dates []string
	sharedCount := map[string]int{}
	for _, m := range found {
		dates = append(dates, ruDate(days[m.i].Date))
		for _, s := range m.shared {
			sharedCount[s]++
		}
	}
	a.Facts = append(a.Facts, fmt.Sprintf("Вчера: %s", titles(target)))
	a.Facts = append(a.Facts, fmt.Sprintf("Похожие дни за месяц (%d): %s — общее: %s",
		len(found), strings.Join(dates, ", "), topKeys(sharedCount, 2)))

	// How the person marked those days, and the day after
	var feels, nextFeels []string
	var nextScreen, nextUnlocks, nightSleep []float64
	for _, m := range found {
		if c := in.CheckIns[key(days[m.i].Date)]; c != nil {
			feels = append(feels, c.DayFeel)
		}
		if m.i+1 <= last {
			n := days[m.i+1]
			if c := in.CheckIns[key(n.Date)]; c != nil {
				nextFeels = append(nextFeels, c.DayFeel)
			}
			if n.ScreenMin != nil {
				nextScreen = append(nextScreen, float64(*n.ScreenMin))
			}
			if n.Unlocks != nil {
				nextUnlocks = append(nextUnlocks, float64(*n.Unlocks))
			}
			if n.SleepMin != nil { // the night after the similar day
				nightSleep = append(nightSleep, float64(*n.SleepMin))
			}
		}
	}
	if len(feels) > 0 {
		a.Facts = append(a.Facts, "В такие дни ты отмечал: "+feelCounts(feels))
	}
	usualScreen, _ := mean(days, func(d *repo.DailyData) (float64, bool) { return ptr(d.ScreenMin) })
	usualUnlocks, _ := mean(days, func(d *repo.DailyData) (float64, bool) { return ptr(d.Unlocks) })
	usualSleep, ns := mean(days, func(d *repo.DailyData) (float64, bool) { return ptr(d.SleepMin) })
	if len(nightSleep) > 0 && ns >= 3 {
		a.Facts = append(a.Facts, fmt.Sprintf("Ночь после таких дней: сон в среднем %s — обычно %s",
			signals.FormatMinutes(avg(nightSleep)), signals.FormatMinutes(round(usualSleep))))
	}
	if len(nextScreen) > 0 {
		a.Facts = append(a.Facts, fmt.Sprintf("На следующий день: экран %s, разблокировок %d — обычно %s и %d",
			signals.FormatMinutes(avg(nextScreen)), avg(nextUnlocks), signals.FormatMinutes(round(usualScreen)), round(usualUnlocks)))
	}
	if len(nextFeels) > 0 {
		a.Facts = append(a.Facts, "Следующий день ты отмечал: "+feelCounts(nextFeels))
	}
	if len(found) < 2 {
		a.Facts = append(a.Facts, "Похожий день пока один — вывод осторожный")
	}
	return a
}

// ─── «Почему вчера было именно так?» ─────────────────────────────────────────

func whyYesterday(in Input) *Answerable {
	days := in.Days
	last := len(days) - 1
	from := last - 7
	if from < 0 {
		from = 0
	}
	sig := signals.ForLastDay(days[from:], in.WorkApps, in.LabelOf)
	if len(sig) == 0 {
		return nil
	}
	a := &Answerable{Question: Question{ID: "why", Text: "Почему вчера было именно так?"}}
	for _, s := range sig {
		a.Facts = append(a.Facts, s.Title+": "+s.Detail)
	}
	d := days[last]
	if len(d.TopApps) > 0 {
		var apps []string
		for i, x := range d.TopApps {
			if i == 3 {
				break
			}
			apps = append(apps, fmt.Sprintf("%s %s", in.LabelOf(x.Package), signals.FormatMinutes(x.Minutes)))
		}
		a.Facts = append(a.Facts, "Больше всего времени: "+strings.Join(apps, ", "))
	}
	if c := in.CheckIns[key(d.Date)]; c != nil {
		a.Facts = append(a.Facts, "Вечером ты отметил: "+feelWord(c.DayFeel))
	}
	return a
}

// ─── «Чем отличались мои хорошие дни от тяжёлых?» ────────────────────────────

func bestDays(in Input) *Answerable {
	var good, bad []int
	for i, d := range in.Days {
		if c := in.CheckIns[key(d.Date)]; c != nil {
			switch c.DayFeel {
			case "ok":
				good = append(good, i)
			case "hard", "meh":
				bad = append(bad, i)
			}
		}
	}
	if len(good) == 0 || len(bad) == 0 || len(good)+len(bad) < 3 {
		return nil
	}
	a := &Answerable{Question: Question{ID: "best", Text: "Чем мои хорошие дни отличаются от тяжёлых?"}}
	a.Facts = append(a.Facts, fmt.Sprintf("Хороших дней: %d, так себе и тяжёлых: %d", len(good), len(bad)))
	a.Facts = append(a.Facts, compare(in.Days, good, bad, "в хорошие дни", "в остальные")...)
	return a
}

// ─── «Как телефон вечером влияет на мой сон?» ────────────────────────────────

func phoneAndSleep(in Input) *Answerable {
	type night struct{ last, sleep int }
	var nights []night
	for i := 0; i+1 < len(in.Days); i++ {
		lu, ok := signals.ClockMinutes(in.Days[i].LastUnlock)
		if ok && in.Days[i+1].SleepMin != nil {
			nights = append(nights, night{lu, *in.Days[i+1].SleepMin})
		}
	}
	if len(nights) < 5 {
		return nil
	}
	lasts := make([]int, len(nights))
	for i, n := range nights {
		lasts[i] = n.last
	}
	sort.Ints(lasts)
	cut := lasts[len(lasts)/2]
	var late, early []float64
	for _, n := range nights {
		if n.last > cut {
			late = append(late, float64(n.sleep))
		} else {
			early = append(early, float64(n.sleep))
		}
	}
	if len(late) < 2 || len(early) < 2 {
		return nil
	}
	a := &Answerable{Question: Question{ID: "phone_sleep", Text: "Как телефон вечером влияет на мой сон?"}}
	a.Facts = append(a.Facts,
		fmt.Sprintf("Вечера, когда телефон откладывался после %s (%d): сон в среднем %s", signals.FormatClock(cut), len(late), signals.FormatMinutes(avg(late))),
		fmt.Sprintf("Вечера, когда до %s (%d): сон в среднем %s", signals.FormatClock(cut), len(early), signals.FormatMinutes(avg(early))),
	)
	if math.Abs(float64(avg(late)-avg(early))) < 20 {
		a.Facts = append(a.Facts, "Разница меньше 20 минут — заметной связи пока нет")
	}
	return a
}

// ─── «Что мне даёт движение?» ────────────────────────────────────────────────

func movement(in Input) *Answerable {
	var idx []int
	var steps []int
	for i, d := range in.Days {
		if d.Steps != nil && *d.Steps > 0 {
			idx = append(idx, i)
			steps = append(steps, *d.Steps)
		}
	}
	if len(idx) < 5 {
		return nil
	}
	s := append([]int(nil), steps...)
	sort.Ints(s)
	cut := s[len(s)/2]
	var more, less []int
	for k, i := range idx {
		if steps[k] > cut {
			more = append(more, i)
		} else {
			less = append(less, i)
		}
	}
	if len(more) < 2 || len(less) < 2 {
		return nil
	}
	a := &Answerable{Question: Question{ID: "movement", Text: "Что мне даёт движение?"}}
	a.Facts = append(a.Facts, fmt.Sprintf("Дни больше %s шагов: %d, меньше: %d", signals.FormatCount(cut), len(more), len(less)))
	a.Facts = append(a.Facts, compare(in.Days, more, less, "в подвижные дни", "в остальные")...)
	return a
}

// ─── «Как работа влияет на мои дни?» ─────────────────────────────────────────

func workDays(in Input) *Answerable {
	var heavy, light []int
	for i, d := range in.Days {
		w := 0
		for _, x := range d.TopApps {
			if in.WorkApps[x.Package] || signals.IsWorkApp(x.Package) {
				w += x.Minutes
			}
		}
		if len(d.TopApps) == 0 {
			continue
		}
		if w >= 90 {
			heavy = append(heavy, i)
		} else {
			light = append(light, i)
		}
	}
	if len(heavy) < 2 || len(light) < 2 {
		return nil
	}
	a := &Answerable{Question: Question{ID: "work", Text: "Как работа в телефоне влияет на мои дни?"}}
	a.Facts = append(a.Facts, fmt.Sprintf("Дней с рабочими приложениями от 1,5 ч: %d, остальных: %d", len(heavy), len(light)))
	a.Facts = append(a.Facts, compare(in.Days, heavy, light, "в рабочие дни", "в остальные")...)
	return a
}

// ─── helpers ─────────────────────────────────────────────────────────────────

// compare describes two groups of days: evening, the night after, the mark.
func compare(days []*repo.DailyData, a, b []int, an, bn string) []string {
	var out []string
	line := func(label string, f func(*repo.DailyData) (float64, bool), next bool, format func(int) string) {
		va, na := groupMean(days, a, f, next)
		vb, nb := groupMean(days, b, f, next)
		if na > 0 && nb > 0 {
			out = append(out, fmt.Sprintf("%s: %s %s, %s %s", label, an, format(round(va)), bn, format(round(vb))))
		}
	}
	line("Экран", func(d *repo.DailyData) (float64, bool) { return ptr(d.ScreenMin) }, false, signals.FormatMinutes)
	line("Разблокировок", func(d *repo.DailyData) (float64, bool) { return ptr(d.Unlocks) }, false, func(v int) string { return fmt.Sprint(v) })
	line("Последний раз телефон", func(d *repo.DailyData) (float64, bool) {
		v, ok := signals.ClockMinutes(d.LastUnlock)
		return float64(v), ok
	}, false, signals.FormatClock)
	line("Сон ночью после", func(d *repo.DailyData) (float64, bool) { return ptr(d.SleepMin) }, true, signals.FormatMinutes)
	line("Шаги", func(d *repo.DailyData) (float64, bool) {
		if d.Steps == nil || *d.Steps <= 0 {
			return 0, false
		}
		return float64(*d.Steps), true
	}, false, signals.FormatCount)
	return out
}

// groupMean averages f over the days in idx (or over the day after each, for next = true).
func groupMean(days []*repo.DailyData, idx []int, f func(*repo.DailyData) (float64, bool), next bool) (float64, int) {
	sum, n := 0.0, 0
	for _, i := range idx {
		j := i
		if next {
			j = i + 1
		}
		if j >= len(days) {
			continue
		}
		if v, ok := f(days[j]); ok {
			sum += v
			n++
		}
	}
	if n == 0 {
		return 0, 0
	}
	return sum / float64(n), n
}

func mean(days []*repo.DailyData, f func(*repo.DailyData) (float64, bool)) (float64, int) {
	idx := make([]int, len(days))
	for i := range days {
		idx[i] = i
	}
	return groupMean(days, idx, f, false)
}

func ptr(p *int) (float64, bool) {
	if p == nil {
		return 0, false
	}
	return float64(*p), true
}

func avg(v []float64) int {
	s := 0.0
	for _, x := range v {
		s += x
	}
	return round(s / float64(len(v)))
}

func round(v float64) int { return int(math.Round(v)) }

func key(t time.Time) string { return t.Format("2006-01-02") }

var months = []string{"янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"}

func ruDate(t time.Time) string { return fmt.Sprintf("%d %s", t.Day(), months[t.Month()-1]) }

func feelWord(f string) string {
	switch f {
	case "ok":
		return "хорошо"
	case "meh":
		return "так себе"
	case "hard":
		return "тяжело"
	}
	return f
}

func feelCounts(feels []string) string {
	count := map[string]int{}
	for _, f := range feels {
		count[feelWord(f)]++
	}
	var parts []string
	for _, w := range []string{"хорошо", "так себе", "тяжело"} {
		if n := count[w]; n > 0 {
			parts = append(parts, fmt.Sprintf("%s — %d", w, n))
		}
	}
	return strings.Join(parts, ", ")
}

func titles(s []signals.Signal) string {
	var t []string
	for _, x := range s {
		r := []rune(x.Title)
		t = append(t, strings.ToLower(string(r[:1]))+string(r[1:]))
	}
	return strings.Join(t, ", ")
}

func topKeys(count map[string]int, n int) string {
	type kv struct {
		k string
		v int
	}
	var list []kv
	for k, v := range count {
		list = append(list, kv{k, v})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].v > list[j].v || (list[i].v == list[j].v && list[i].k < list[j].k) })
	var out []string
	for i, x := range list {
		if i == n {
			break
		}
		out = append(out, "«"+x.k+"»")
	}
	return strings.Join(out, " и ")
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}
