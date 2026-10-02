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
		{InsightProfile, "3"},        // only 2 slots a day
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
