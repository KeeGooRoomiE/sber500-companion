package forecast

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

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

// ProfileLimits: «Твой профиль» sits between question cards, not under its own heading —
// tighter than the generic insight line on purpose.
var ProfileLimits = Limits{Tokens: 120, Chars: 200}

// OrbTapLimits: a reaction bubble over the orb for about a second — the tightest budget here.
var OrbTapLimits = Limits{Tokens: 30, Chars: 60}

// InsightDailyLimit caps new insights per person per day.
//
// Generous because these are tied to gestures the person already makes — opening the app after
// lunch, tapping a tile — rather than to a prompt to engage. The cap exists so a loop in the
// app cannot spend the budget, not to ration the feature. The LLM budget is nowhere near
// a constraint — 22 ₽ of 50 000 after a week — so the cap is a runaway guard, nothing more.
const InsightDailyLimit = 50

// Insight kinds.
const (
	InsightMidday         = "midday"          // how today is going so far
	InsightStat           = "stat"            // one tile: screen | sleep | unlocks
	InsightRetro          = "retro"           // did a past forecast hold up
	InsightTag            = "tag"             // what days with this tag look like
	InsightQuestion       = "question"        // a question for the person, generated from their data
	InsightProfile        = "profile"         // «Твой профиль» — how the model reads this person
	InsightProfileClarify = "profile_clarify" // a question to correct/extend «Твой профиль»
	InsightOrbTap         = "orbtap"          // a one-way reaction to tapping the companion orb
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

	limits := InsightLimits
	switch kind {
	case InsightProfile:
		limits = ProfileLimits
	case InsightOrbTap:
		limits = OrbTapLimits
	}
	user := llm.BuildInsight(task, facts, profile)
	return g.complete(ctx, userID, prompts.InsightSystem, analytics.ComponentLLMInsight,
		analytics.TriggerUserAction, cacheKind, today, user, limits)
}

// insightCacheKind validates the kind and builds its cache key in one place, so an unknown
// argument can never reach the model under a key that looks valid.
func insightCacheKind(kind, arg string) (string, error) {
	switch kind {
	case InsightMidday:
		return "in:" + kind, nil
	case InsightQuestion:
		// Up to 3 fresh questions a day — tied to the person actually answering one (the app
		// asks for the next slot only after that), not a standing invitation to call 3 times
		// regardless. "" is slot 1, for callers written before slots existed.
		switch arg {
		case "":
			arg = "1"
		case "1", "2", "3":
		default:
			return "", ErrBadInsight
		}
		return "in:question:" + arg, nil
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
	case InsightProfile:
		// Slot "1" is the one the screen loads on open, cached for the day like everything
		// else. Slot "2" exists only for the moment right after «Уточнить» is answered — a
		// second, separate cache slot is what lets that one call produce a different text the
		// same day instead of just replaying slot 1's cached result back.
		switch arg {
		case "":
			arg = "1"
		case "1", "2":
		default:
			return "", ErrBadInsight
		}
		return "in:profile:" + arg, nil
	case InsightProfileClarify:
		return "in:profile_clarify", nil
	case InsightOrbTap:
		// Five slots, each tied to a specific fact (see insightPrompt) — not five independent
		// chances to re-roll the same reaction. "" is slot 1.
		switch arg {
		case "":
			arg = "1"
		case "1", "2", "3", "4", "5":
		default:
			return "", ErrBadInsight
		}
		return "in:orbtap:" + arg, nil
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
		// Already has as many generated answers as the profile keeps (api.PutProfile drops the
		// rest on arrival) — asking again would spend a call on a question that can only be
		// thrown away, for someone who, by now, has run out of new ground for this kind anyway.
		if llm.CountGenAnswers(profile) >= llm.MaxGenAnswers {
			return nil, "", ErrNoData
		}
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
		task := "Задай человеку один короткий вопрос о нём самом с вариантами ответа, ответ на который помог бы точнее объяснять его дни. Отталкивайся от того, что видно в данных. Вопрос — открытый, не называй варианты внутри него (не пиши «...А или Б?»)."
		if known := llm.KnownProfileFacts(profile); len(known) > 0 {
			facts = append(facts, "Уже известно про человека: "+strings.Join(known, "; "))
			task += " Не повторяй и не переформулируй то, что уже известно про человека — спроси о другой стороне его дней."
		}
		return facts, task, nil

	case InsightProfile:
		facts := statFacts(days, "screen")
		facts = append(facts, statFacts(days, "sleep")...)
		facts = append(facts, statFacts(days, "unlocks")...)
		if sig := signals.ForLastDay(days, WorkApps(profile), llm.AppLabel); len(sig) > 0 {
			for _, s := range sig {
				facts = append(facts, s.Title+": "+s.Detail)
			}
		}
		if known := llm.KnownProfileFacts(profile); len(known) > 0 {
			facts = append(facts, "Уже известно про человека: "+strings.Join(known, "; "))
		}
		return facts, "Опиши этого человека одним-двумя предложениями, глядя на данные и на то, что уже известно о нём — как ты его видишь. Будь конкретен, не используй общие фразы вроде «активный пользователь».", nil

	case InsightProfileClarify:
		// Clarifies slot 1 specifically — the one the person actually saw, not whatever slot 2
		// happens to hold from an earlier clarification today.
		base, err := g.reviews.Get(ctx, userID, "in:profile:1", today)
		if err != nil {
			return nil, "", err
		}
		if base == nil || strings.TrimSpace(base.Text) == "" {
			return nil, "", ErrNoData
		}
		facts := []string{"Текущее описание этого человека: «" + base.Text + "»"}
		return facts, "Задай человеку один короткий вопрос с вариантами ответа, который помог бы поправить или дополнить это описание. Не повторяй его буквально — спроси о конкретной стороне его дней, способной его изменить. Вопрос — открытый, не называй варианты внутри него.", nil

	case InsightOrbTap:
		// One fact per slot, not the same question asked five times — see the comment on
		// insightCacheKind. A slot with nothing to say (statFacts empty, no signal, no tag)
		// falls through to ErrNoData in Insight(), same as every other kind.
		var facts []string
		switch arg {
		case "1":
			facts = head(statFacts(days, "screen"), 2)
		case "2":
			facts = head(statFacts(days, "sleep"), 2)
		case "3":
			facts = head(statFacts(days, "unlocks"), 2)
		case "4":
			if sig := signals.ForLastDay(days, WorkApps(profile), llm.AppLabel); len(sig) > 0 {
				facts = []string{sig[0].Title + ": " + sig[0].Detail}
			}
		case "5":
			if checkins, err := g.checkin.Range(ctx, userID, today, today); err == nil {
				if c := checkins[today.Format("2006-01-02")]; c != nil && len(c.Tags) > 0 {
					facts = []string{"Сегодня человек отметил: " + c.Tags[0]}
				}
			}
			if len(facts) == 0 && len(last.TopApps) > 0 {
				a := last.TopApps[0]
				facts = []string{"Больше всего времени сегодня: " + llm.AppLabel(a.Package) + " " + signals.FormatMinutes(a.Minutes)}
			}
		}
		task := "Человек только что несколько раз быстро тронул иконку-компаньона на экране — " +
			"это жест, не вопрос к нему, отвечать вопросом не нужно. Напиши одну очень короткую " +
			"фразу-реакцию — уложись в 36 символов, это очень краткая сводка, не разворачивай мысль. " +
			"Обязательно закончи её точкой, восклицательным " +
			"или вопросительным знаком — не обрывай фразу без знака в конце. Если в данных есть что-то " +
			"заметное — тепло и коротко прокомментируй именно это; если заметного нет — короткую " +
			"нейтральную фразу без выдуманного наблюдения. Если день выглядит тяжёлым — экран или " +
			"разблокировки заметно выше обычного, короткий сон — не бодрись и не подкалывай: ровная, " +
			"мягкая фраза без восторга, орб не радуется плохому дню. Без вопроса с вариантами ответа, " +
			"без приветствий и подписей."
		return facts, task, nil
	}
	return nil, "", ErrBadInsight
}

// head returns at most the first n elements — statFacts' first lines (today, then the average)
// are enough context for a one-line reaction; its weekly list would only pad the prompt.
func head(s []string, n int) []string {
	if len(s) > n {
		return s[:n]
	}
	return s
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

// maxQuestionOptions: the UI shows chips, not a menu — more than three stops being "a question"
// and starts being a form.
const maxQuestionOptions = 3

// StripLeakedQuestion drops a stray question-with-options tail from a kind that never asked for
// one — midday, a stat tile, retro, a tag, the profile summary. Seen live: a midday card whose
// real answer was a complete sentence, followed by a blank line and "В чём ты сегодня больше
// всего отвлекался? Варианты: Рабочие чаты | Случайные приложения" tacked on, shown verbatim
// because these kinds are served as-is with no parsing at all. The system prompt now says not
// to do this; this is the backstop for the calls where it does anyway — applied at serve time,
// not generation time, so a card already cached with the leak heals on the next read instead of
// sitting broken until the day's cache resets.
func StripLeakedQuestion(raw string) string {
	s := raw
	if idx := strings.Index(strings.ToLower(s), "варианты"); idx >= 0 {
		s = s[:idx]
	}
	// No label, but the text still ends in its own bare question (paragraphs split by a blank
	// line) — the same leak, just without the options line.
	if paras := strings.Split(strings.TrimRight(s, "\n"), "\n\n"); len(paras) > 1 {
		last := strings.TrimSpace(paras[len(paras)-1])
		if strings.HasSuffix(last, "?") && len([]rune(last)) < 140 {
			s = strings.Join(paras[:len(paras)-1], "\n\n")
		}
	}
	return strings.TrimSpace(s)
}

// ParseQuestionAnswer splits a generated question's raw text into the question itself and its
// answer options.
//
// The system prompt asks for two lines — question, then "Варианты: А | Б" — but in practice
// the model almost always runs them together on one line, with the label sitting after a dash
// or a question mark rather than after a newline. So this looks for the label anywhere in the
// text; failing that, for a colon followed by a "|"-separated list, which is what the model
// falls back to when it drops the label. If neither shows up, options comes back empty and the
// caller shows a free-text question instead — the text is still shown either way.
func ParseQuestionAnswer(raw string) (question string, options []string) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", nil
	}
	// Every successful path returns through here, so the one check that matters — did the
	// question already name its own options — only has to be written once.
	finish := func(head string, opts []string) (string, []string) {
		if questionRepeatsOptions(head, opts) {
			// The model spelled the choice out twice: once as «А или Б» in the sentence, once
			// as chips. Showing both reads as the chips repeating the question, so this drops
			// to the sentence alone — still a complete, answerable question on its own.
			return head, nil
		}
		return head, opts
	}
	lower := strings.ToLower(raw)
	if idx := strings.Index(lower, "варианты"); idx >= 0 {
		head := lastSentence(strings.TrimRight(strings.TrimSpace(raw[:idx]), " -–—:·"))
		if _, list, ok := strings.Cut(raw[idx:], ":"); ok {
			if opts := splitQuestionOptions(list); len(opts) >= 2 {
				return finish(head, opts)
			}
		}
		// The label is there but nothing usable follows it — drop it rather than show it.
		raw = strings.TrimSpace(raw[:idx])
	}
	// No label: a colon followed somewhere by a "|" list is the shape the model uses instead.
	if colon := strings.LastIndex(raw, ":"); colon >= 0 && strings.Contains(raw[colon:], "|") {
		head := lastSentence(strings.TrimSpace(raw[:colon]))
		if opts := splitQuestionOptions(raw[colon+1:]); len(opts) >= 2 {
			return finish(head, opts)
		}
	}
	// Neither label nor colon: the plain two-line shape the prompt actually asks for —
	// question, newline, bare "А | Б" list.
	if nl := strings.Index(raw, "\n"); nl >= 0 {
		if rest := strings.TrimSpace(raw[nl+1:]); strings.Contains(rest, "|") {
			if opts := splitQuestionOptions(rest); len(opts) >= 2 {
				return finish(lastSentence(strings.TrimSpace(raw[:nl])), opts)
			}
		}
	}
	// Last resort: no label, no colon, no newline — just "...вопрос? | А | Б" on one line. Cut
	// at the nearest sentence end before the first "|", which is what is left once the model
	// has dropped every other separator it was asked for.
	if pipe := strings.Index(raw, "|"); pipe >= 0 {
		cut := -1
		for _, ch := range []byte{'?', '!', '.'} {
			if i := strings.LastIndexByte(raw[:pipe], ch); i > cut {
				cut = i
			}
		}
		if cut >= 0 {
			if opts := splitQuestionOptions(raw[cut+1:]); len(opts) >= 2 {
				return finish(lastSentence(strings.TrimSpace(raw[:cut+1])), opts)
			}
		}
	}
	return lastSentence(raw), nil
}

// questionRepeatsOptions: true when every option already appears, word for word, inside the
// question itself — most often because the model phrased it as «А или Б?» instead of asking
// something open. Chips under a sentence that already names them would look like the chips
// are duplicating the text, not answering it.
func questionRepeatsOptions(question string, options []string) bool {
	if len(options) == 0 {
		return false
	}
	qWords := wordsOf(question)
	for _, opt := range options {
		if !containsWholeWords(qWords, wordsOf(opt)) {
			return false
		}
	}
	return true
}

// wordsOf splits on anything that isn't a letter or digit and lowercases the rest. Plain
// strings.Contains was a real bug here: a one-word option like «Да» is a byte-for-byte
// substring of «задача», so a question about tasks was losing its chips over a coincidence
// that had nothing to do with it.
func wordsOf(s string) []string {
	var words []string
	var cur []rune
	flush := func() {
		if len(cur) > 0 {
			words = append(words, string(cur))
			cur = cur[:0]
		}
	}
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			cur = append(cur, r)
		} else {
			flush()
		}
	}
	flush()
	return words
}

// containsWholeWords: true if needle's words appear, in the same order, as a contiguous run
// inside haystack's words — a whole-word match, not a substring one.
func containsWholeWords(haystack, needle []string) bool {
	if len(needle) == 0 || len(needle) > len(haystack) {
		return false
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j, w := range needle {
			if haystack[i+j] != w {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}

// SafeOrbReaction returns the text as-is if it ends cleanly, or ok=false if the 60-char cap
// had to cut it off with nothing to stop at — capMessage's own fallback when a sentence runs
// long with no earlier punctuation. For a line shown inside a paragraph that is a non-event;
// for a bubble over the orb for about a second, a word sheared in half reads as an obvious
// glitch, so the caller shows a canned phrase instead rather than this.
func SafeOrbReaction(raw string) (string, bool) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", false
	}
	switch r := []rune(s); r[len(r)-1] {
	case '.', '!', '?', '…':
		return s, true
	default:
		return "", false
	}
}

// lastSentence drops a stray lead-in before the actual question — the system prompt says
// "only the question" but the model sometimes adds one anyway. A genuine single-sentence
// question (the common case) passes through unchanged.
func lastSentence(s string) string {
	parts := strings.Split(strings.TrimSpace(s), ". ")
	return strings.TrimSpace(parts[len(parts)-1])
}

func splitQuestionOptions(s string) []string {
	var out []string
	for _, opt := range strings.Split(s, "|") {
		opt = strings.TrimSpace(strings.TrimRight(strings.TrimSpace(opt), "?!.,;:"))
		if opt == "" {
			continue
		}
		out = append(out, opt)
		if len(out) == maxQuestionOptions {
			break
		}
	}
	return out
}
