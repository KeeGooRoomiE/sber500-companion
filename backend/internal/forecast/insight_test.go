package forecast

import (
	"reflect"
	"testing"
)

// The cache key is also the gate: an argument that gets a key gets to the model, so an
// unknown one must never produce a plausible-looking key.
func TestInsightCacheKind(t *testing.T) {
	ok := map[string][2]string{
		"in:midday":           {InsightMidday, ""},
		"in:question:1":       {InsightQuestion, ""},
		"in:question:2":       {InsightQuestion, "2"},
		"in:question:3":       {InsightQuestion, "3"},
		"in:stat:screen":      {InsightStat, "screen"},
		"in:stat:sleep":       {InsightStat, "sleep"},
		"in:stat:unlocks":     {InsightStat, "unlocks"},
		"in:retro:2026-10-01": {InsightRetro, "2026-10-01"},
		"in:tag:Работа":       {InsightTag, "Работа"},
		"in:profile:1":        {InsightProfile, ""},
		"in:profile:2":        {InsightProfile, "2"},
		"in:profile_clarify":  {InsightProfileClarify, ""},
		"in:orbtap:1":         {InsightOrbTap, ""},
		"in:orbtap:5":         {InsightOrbTap, "5"},
	}
	for want, in := range ok {
		got, err := insightCacheKind(in[0], in[1])
		if err != nil || got != want {
			t.Errorf("insightCacheKind(%q, %q) = %q, %v; want %q", in[0], in[1], got, err, want)
		}
	}

	bad := [][2]string{
		{"", ""},
		{"unknown", ""},
		{InsightStat, ""},
		{InsightStat, "battery"}, // not a tile we show
		{InsightQuestion, "4"},   // only 3 slots a day
		{InsightQuestion, "0"},
		{InsightProfile, "3"}, // only 2 slots a day
		{InsightOrbTap, "6"},  // only 5 slots a day
		{InsightOrbTap, "0"},
		{InsightRetro, "вчера"},      // not a date
		{InsightRetro, "2026-13-45"}, // not a real date
		{InsightTag, ""},             // empty tag
		{InsightTag, "ооооооооооооооооооооооооооооооооочень длинный тег"},
	}
	for _, in := range bad {
		if got, err := insightCacheKind(in[0], in[1]); err == nil {
			t.Errorf("insightCacheKind(%q, %q) = %q, want an error", in[0], in[1], got)
		}
	}
}

func TestHasTagIsForgiving(t *testing.T) {
	tags := []string{" Работа ", "Спорт"}
	for _, want := range []string{"работа", "Работа", "РАБОТА", "спорт"} {
		if !hasTag(tags, want) {
			t.Errorf("hasTag(%v, %q) = false", tags, want)
		}
	}
	if hasTag(tags, "Люди") {
		t.Error("hasTag matched a tag that is not there")
	}
}

func TestParseQuestionAnswer(t *testing.T) {
	cases := []struct {
		raw      string
		question string
		options  []string
	}{
		{
			"Часто ли рабочие созвоны продолжаются и после работы?\nВарианты: Да | Нет",
			"Часто ли рабочие созвоны продолжаются и после работы?",
			[]string{"Да", "Нет"},
		},
		{
			"Ты чаще отвлекаешься утром или вечером?\nВарианты: Утром | Вечером | По-разному",
			"Ты чаще отвлекаешься утром или вечером?",
			[]string{"Утром", "Вечером", "По-разному"},
		},
		{
			// model dropped the label but kept the "|" list
			"Ты работаешь из дома?\nДа | Нет",
			"Ты работаешь из дома?",
			[]string{"Да", "Нет"},
		},
		{
			// no options at all — falls back to free text
			"Что сегодня больше всего заняло твой день?",
			"Что сегодня больше всего заняло твой день?",
			nil,
		},
		{
			// only one option survives — not a real choice
			"Ты сегодня работал?\nВарианты: Да",
			"Ты сегодня работал?",
			nil,
		},
		{
			"",
			"",
			nil,
		},
		// The cases below are real model output (gigachat-3-pro): it almost never honours the
		// two-line contract and instead runs question and options together on one line.
		{
			// label after a dash, same line, no newline
			"Какая часть дня чаще выбивает тебя из ритма — Варианты: Вечерние переписки | Дневные задачи",
			"Какая часть дня чаще выбивает тебя из ритма",
			[]string{"Вечерние переписки", "Дневные задачи"},
		},
		{
			// no label at all, just a colon and a pipe list
			"Что чаще отвлекает: Рабочие чаты | Личные сообщения",
			"Что чаще отвлекает",
			[]string{"Рабочие чаты", "Личные сообщения"},
		},
		{
			// 4 options offered — capped at 3
			"Что чаще всего отвлекает тебя от работы или учёбы дома? Варианты: Телевизор | Музыка | Соседи | Телефон",
			"Что чаще всего отвлекает тебя от работы или учёбы дома?",
			[]string{"Телевизор", "Музыка", "Соседи"},
		},
		{
			// last option carries a stray "?" from the model — stripped
			"Что чаще всего сбивает с работы или учёбы дома: соцсети | уведомления | другое?",
			"Что чаще всего сбивает с работы или учёбы дома",
			[]string{"соцсети", "уведомления", "другое"},
		},
		{
			// a stray lead-in sentence before the actual question — only the last one is kept
			"Твой экран уходит на Telegram и Chrome. Какая часть дня выбивает тебя из ритма — Варианты: Вечер | День",
			"Какая часть дня выбивает тебя из ритма",
			[]string{"Вечер", "День"},
		},
		{
			// no label, no colon, no newline at all — just "...? | А | Б"
			"Твои дни часто меняют не звонки, а что-то ещё? | Да | Нет",
			"Твои дни часто меняют не звонки, а что-то ещё?",
			[]string{"Да", "Нет"},
		},
		{
			"Что чаще всего сбивает твой рабочий ритм днём? | Мессенджеры | Домашние дела | Усталость",
			"Что чаще всего сбивает твой рабочий ритм днём?",
			[]string{"Мессенджеры", "Домашние дела", "Усталость"},
		},
	}
	for _, c := range cases {
		q, opts := ParseQuestionAnswer(c.raw)
		if q != c.question || !reflect.DeepEqual(opts, c.options) {
			t.Errorf("ParseQuestionAnswer(%q) = (%q, %v); want (%q, %v)", c.raw, q, opts, c.question, c.options)
		}
	}
}

// A model that spells the choice out twice — once in the sentence, once as the list — must not
// end up showing it three times (sentence + two chips repeating the same words).
func TestParseQuestionAnswerDropsDuplicatedOptions(t *testing.T) {
	cases := []struct{ raw, question string }{
		{
			"Ты работаешь из дома или в офисе? Варианты: Дома | В офисе",
			"Ты работаешь из дома или в офисе?",
		},
		{
			// the colon-ending shape from the live report: question restates the options,
			// then ends with ":" instead of "?"
			"Что чаще отвлекает — рабочие чаты или звонки: Рабочие чаты | Звонки",
			"Что чаще отвлекает — рабочие чаты или звонки",
		},
		{
			// case-insensitive match still counts as a repeat
			"ты дома или в офисе? Варианты: Дома | В офисе",
			"ты дома или в офисе?",
		},
	}
	for _, c := range cases {
		q, opts := ParseQuestionAnswer(c.raw)
		if q != c.question {
			t.Errorf("ParseQuestionAnswer(%q) question = %q; want %q", c.raw, q, c.question)
		}
		if opts != nil {
			t.Errorf("ParseQuestionAnswer(%q) options = %v; want nil (duplicated in the question)", c.raw, opts)
		}
	}

	// A genuine, non-duplicated question keeps its options — the guard must not be trigger-happy.
	q, opts := ParseQuestionAnswer("Где тебе лучше работается? Варианты: Дома | В офисе")
	if q != "Где тебе лучше работается?" || len(opts) != 2 {
		t.Errorf("a non-duplicated question lost its options: q=%q opts=%v", q, opts)
	}
}

func TestSafeOrbReaction(t *testing.T) {
	cases := []struct {
		raw  string
		text string
		ok   bool
	}{
		{"Экран сегодня больше обычного.", "Экран сегодня больше обычного.", true},
		{"Тебе скучно?", "Тебе скучно?", true},
		{"Пальцы бегут быстрее обычного!", "Пальцы бегут быстрее обычного!", true},
		{"Сегодня экран был включён дольше обычного — это твой максиму", "", false}, // cut mid-word
		{"  ", "", false},
		{"", "", false},
	}
	for _, c := range cases {
		text, ok := SafeOrbReaction(c.raw)
		if text != c.text || ok != c.ok {
			t.Errorf("SafeOrbReaction(%q) = (%q, %v); want (%q, %v)", c.raw, text, ok, c.text, c.ok)
		}
	}
}

// A one-word option like «Да» is a byte-for-byte substring of «задача» — a raw strings.Contains
// check would have stripped perfectly good chips from a question that never mentioned them.
func TestQuestionRepeatsOptionsIgnoresSubstringCollisions(t *testing.T) {
	q, opts := ParseQuestionAnswer("Какие задачи сегодня важнее? Варианты: Да | Нет")
	if q != "Какие задачи сегодня важнее?" || len(opts) != 2 {
		t.Errorf("a coincidental substring («да» inside «задачи») dropped real options: q=%q opts=%v", q, opts)
	}

	// A genuine whole-word duplicate must still be caught.
	if !questionRepeatsOptions("Ты дома или в офисе?", []string{"Дома", "В офисе"}) {
		t.Error("a genuine whole-word duplicate was not caught")
	}
}

// Exactly the text from a live bug report: midday grew a question-with-options tail it was
// never asked for, and midday has no parsing of its own — it would have shown this verbatim.
func TestStripLeakedQuestion(t *testing.T) {
	cases := []struct{ raw, want string }{
		{
			"Сегодня экран и разблокировки близки к средним значениям, но заметно выше обычного — " +
				"особенно экран (почти 3 часа против 41 минуты).\n\n" +
				"В чём ты сегодня больше всего отвлекался: в рабочих чатах или на случайные приложения?\n" +
				"Варианты: Рабочие чаты | Случайные приложения",
			"Сегодня экран и разблокировки близки к средним значениям, но заметно выше обычного — " +
				"особенно экран (почти 3 часа против 41 минуты).",
		},
		{
			// the leaked question with no "Варианты:" label at all
			"Сон сегодня короче обычного.\n\nЧто мешало лечь пораньше?",
			"Сон сегодня короче обычного.",
		},
		{
			// a clean answer with no leak must pass through untouched
			"Экран сегодня заметно выше обычного — почти вдвое.",
			"Экран сегодня заметно выше обычного — почти вдвое.",
		},
		{
			"",
			"",
		},
	}
	for _, c := range cases {
		got := StripLeakedQuestion(c.raw)
		if got != c.want {
			t.Errorf("StripLeakedQuestion(%q) = %q; want %q", c.raw, got, c.want)
		}
	}
}
