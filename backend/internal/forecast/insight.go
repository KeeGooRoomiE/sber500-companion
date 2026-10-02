package forecast

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

// ErrBadInsight: unknown kind, or an argument that does not belong to it.
var ErrBadInsight = errors.New("unknown insight")

// InsightLimits: one line, not a review. These sit inside screens that already have text.
var InsightLimits = Limits{Tokens: 160, Chars: 300}

// InsightDailyLimit caps new insights per person per day.
//
// Generous because these are tied to gestures the person already makes — opening the app after
// lunch, tapping a tile — rather than to a prompt to engage. The cap exists so a loop in the
// app cannot spend the budget, not to ration the feature.
const InsightDailyLimit = 25

// Insight kinds.
const (
	InsightMidday   = "midday"   // how today is going so far
	InsightStat     = "stat"     // one tile: screen | sleep | unlocks
	InsightRetro    = "retro"    // did a past forecast hold up
	InsightTag      = "tag"      // what days with this tag look like
	InsightQuestion = "question" // a question for the person, generated from their data
)

// Insight answers one slice of the person's data.
//
// Every kind shares the cache, the budget and the logging; only the facts and the task differ.
// Cached per (user, kind, argument, day), so re-opening a screen costs nothing and only a new
// question costs a call.
func (g *Generator) Insight(ctx context.Context, userID, kind, arg string) (*repo.Review, error) {
	today := clock.Today()
	cacheKind, err := insightCacheKind(kind, arg)
	if err != nil {
		return nil, err
	}

	unlock := g.lockUser(userID)
	defer unlock()

	if cached, err := g.reviews.Get(ctx, userID, cacheKind, today); err != nil || cached != nil {
		return cached, err
	}

	used, err := g.reviews.CountKindPrefixToday(ctx, userID, "in:")
	if err != nil {
		return nil, err
	}
	if used >= InsightDailyLimit {
		return nil, ErrBudget
	}

	profile, err := g.users.Profile(ctx, userID)
	if err != nil {
		return nil, err
	}
	// A month: enough for «обычно» on any of these without being a different query per kind.
	days, err := g.daily.LastN(ctx, userID, 31, today)
	if err != nil {
		return nil, err
	}
	if len(days) == 0 {
		return nil, ErrNoData
	}

	facts, task, err := g.insightPrompt(ctx, userID, kind, arg, days, profile)
	if err != nil {
		return nil, err
	}
	if len(facts) == 0 {
		return nil, ErrNoData
	}

	user := llm.BuildInsight(task, facts, profile)
	return g.complete(ctx, userID, prompts.InsightSystem, analytics.ComponentLLMInsight,
		analytics.TriggerUserAction, cacheKind, today, user, InsightLimits)
}

// insightCacheKind validates the kind and builds its cache key in one place, so an unknown
// argument can never reach the model under a key that looks valid.
func insightCacheKind(kind, arg string) (string, error) {
	switch kind {
	case InsightMidday, InsightQuestion:
		return "in:" + kind, nil
	case InsightStat:
		if arg != "screen" && arg != "sleep" && arg != "unlocks" {
			return "", ErrBadInsight
		}
		return "in:stat:" + arg, nil
	case InsightTag:
		if strings.TrimSpace(arg) == "" || len([]rune(arg)) > 32 {
			return "", ErrBadInsight
		}
		return "in:tag:" + arg, nil
	case InsightRetro:
		if _, err := time.Parse("2006-01-02", arg); err != nil {
			return "", ErrBadInsight
		}
		return "in:retro:" + arg, nil
	}
	return "", ErrBadInsight
}

// insightPrompt assembles the facts and the task for one kind.
func (g *Generator) insightPrompt(
	ctx context.Context, userID, kind, arg string,
	days []*repo.DailyData, profile map[string]string,
) ([]string, string, error) {
	today := clock.Today()
	last := days[len(days)-1]

	switch kind {
	case InsightMidday:
		// Only today, and only as far as it has gone. The point is «как идёт», not a verdict.
		if !last.Date.Equal(today) {
			return nil, "", ErrNoData
		}
		facts := statFacts(days, "screen")
		facts = append(facts, statFacts(days, "unlocks")...)
		if sig := signals.ForLastDay(days, WorkApps(profile), llm.AppLabel); len(sig) > 0 {
			for _, s := range sig {
				facts = append(facts, s.Title+": "+s.Detail)
			}
		}
		return facts, "Скажи, как идёт сегодняшний день по сравнению с обычным для этого человека. День ещё не кончился — говори о том, что видно к этому часу.", nil

	case InsightStat:
		facts := statFacts(days, arg)
		name := map[string]string{"screen": "экранное время", "sleep": "сон", "unlocks": "разблокировки"}[arg]
		return facts, fmt.Sprintf("Скажи одну мысль про %s этого человека: что в этом показателе заметно за последнее время и с чем это у него обычно связано.", name), nil

	case InsightRetro:
		date, _ := time.Parse("2006-01-02", arg)
		msg, err := g.morning.ForDate(ctx, userID, date)
		if err != nil {
			return nil, "", err
		}
		if msg == nil || msg.Message == "" {
			return nil, "", ErrNoData
		}
		// The day the forecast was about, and the days before it for a baseline.
		var upto []*repo.DailyData
		for _, d := range days {
			if !d.Date.After(date) {
				upto = append(upto, d)
			}
		}
		if len(upto) == 0 || !upto[len(upto)-1].Date.Equal(date) {
			return nil, "", ErrNoData
		}
		facts := []string{"Прогноз на " + date.Format("02.01") + ": «" + msg.Message + "»"}
		for _, s := range signals.ForLastDay(upto, WorkApps(profile), llm.AppLabel) {
			facts = append(facts, "По факту — "+s.Title+": "+s.Detail)
		}
		if c, err := g.checkin.Range(ctx, userID, date, date); err == nil {
			if v := c[date.Format("2006-01-02")]; v != nil {
				facts = append(facts, "Человек отметил этот день: "+feelWord(v.DayFeel))
			}
		}
		return facts, "Скажи, сошёлся ли тот прогноз с тем, что было на самом деле. Если не сошёлся — так и скажи, без оправданий.", nil

	case InsightTag:
		from := today.AddDate(0, 0, -30)
		checkins, err := g.checkin.Range(ctx, userID, from, today)
		if err != nil {
			return nil, "", err
		}
		var withTag, without []*repo.DailyData
		for _, d := range days {
			c := checkins[d.Date.Format("2006-01-02")]
			if c != nil && hasTag(c.Tags, arg) {
				withTag = append(withTag, d)
			} else {
				without = append(without, d)
			}
		}
		if len(withTag) < 2 || len(without) < 2 {
			return nil, "", ErrNoData
		}
		facts := []string{
			fmt.Sprintf("Дней с отметкой «%s»: %d, остальных: %d", arg, len(withTag), len(without)),
			compareLine("Экран", withTag, without, func(d *repo.DailyData) *int { return d.ScreenMin }, true),
			compareLine("Сон", withTag, without, func(d *repo.DailyData) *int { return d.SleepMin }, true),
			compareLine("Разблокировки", withTag, without, func(d *repo.DailyData) *int { return d.Unlocks }, false),
		}
		return facts, fmt.Sprintf("Скажи, чем у этого человека отличаются дни с отметкой «%s» от остальных.", arg), nil

	case InsightQuestion:
		facts := statFacts(days, "screen")
		if len(last.TopApps) > 0 {
			var apps []string
			for i, a := range last.TopApps {
				if i == 4 {
					break
				}
				apps = append(apps, llm.AppLabel(a.Package)+" "+signals.FormatMinutes(a.Minutes))
			}
			facts = append(facts, "Больше всего времени за последний день: "+strings.Join(apps, ", "))
		}
		if len(profile) > 0 {
			var known []string
			for k := range profile {
				known = append(known, k)
			}
			facts = append(facts, "Уже известно про человека (ключи): "+strings.Join(known, ", "))
		}
		return facts, "Задай человеку один короткий вопрос о нём самом, ответ на который помог бы точнее объяснять его дни. Отталкивайся от того, что видно в данных. Только сам вопрос.", nil
	}
	return nil, "", ErrBadInsight
}

// statFacts turns one tile into «сегодня столько, обычно столько, за неделю так».
func statFacts(days []*repo.DailyData, stat string) []string {
	get := map[string]func(*repo.DailyData) *int{
		"screen":  func(d *repo.DailyData) *int { return d.ScreenMin },
		"sleep":   func(d *repo.DailyData) *int { return d.SleepMin },
		"unlocks": func(d *repo.DailyData) *int { return d.Unlocks },
	}[stat]
	if get == nil {
		return nil
	}
	isTime := stat != "unlocks"
	label := map[string]string{"screen": "Экран", "sleep": "Сон", "unlocks": "Разблокировки"}[stat]

	var vals []int
	for _, d := range days {
		if v := get(d); v != nil {
			vals = append(vals, *v)
		}
	}
	if len(vals) == 0 {
		return nil
	}
	fmtv := func(v int) string {
		if isTime {
			return signals.FormatMinutes(v)
		}
		return fmt.Sprintf("%d", v)
	}
	out := []string{llm.FactLine(label+" за последний день", fmtv(vals[len(vals)-1]), "")}
	if len(vals) >= 3 {
		sum := 0
		for _, v := range vals {
			sum += v
		}
		out = append(out, llm.FactLine(label+" в среднем", fmtv(sum/len(vals)), ""))
		week := vals
		if len(week) > 7 {
			week = week[len(week)-7:]
		}
		var parts []string
		for _, v := range week {
			parts = append(parts, fmtv(v))
		}
		out = append(out, label+" по дням (от старого к новому): "+strings.Join(parts, ", "))
	}
	return out
}

// compareLine is «что: внутри группы столько, снаружи столько».
func compareLine(label string, a, b []*repo.DailyData, get func(*repo.DailyData) *int, isTime bool) string {
	avg := func(xs []*repo.DailyData) (int, bool) {
		sum, n := 0, 0
		for _, d := range xs {
			if v := get(d); v != nil {
				sum += *v
				n++
			}
		}
		if n == 0 {
			return 0, false
		}
		return sum / n, true
	}
	av, ok1 := avg(a)
	bv, ok2 := avg(b)
	if !ok1 || !ok2 {
		return ""
	}
	f := func(v int) string {
		if isTime {
			return signals.FormatMinutes(v)
		}
		return fmt.Sprintf("%d", v)
	}
	return fmt.Sprintf("%s: в такие дни %s, в остальные %s", label, f(av), f(bv))
}

func hasTag(tags []string, want string) bool {
	for _, t := range tags {
		if strings.EqualFold(strings.TrimSpace(t), strings.TrimSpace(want)) {
			return true
		}
	}
	return false
}

func feelWord(feel string) string {
	switch feel {
	case "ok":
		return "отлично"
	case "meh":
		return "нормально"
	case "hard":
		return "тяжело"
	}
	return feel
}
