package forecast

import (
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"strings"
	"testing"
	"unicode/utf8"
)

func TestChatIntent(t *testing.T) {
	for text, want := range map[string]string{
		"Я хочу, чтоб ты запомнил обо мне: я работаю ночью":  "remember",
		"Хочу, чтобы ты запомнил обо мне: занимаюсь музыкой": "remember",
		"Что ты обо мне помнишь?":                            "memory_list", "Что ты обо мне знаешь?": "memory_list",
		"Что я просил запомнить?": "memory_list", "Показать ещё": "more",
		"Забудь мой график работы": "forget", "Расскажи подробнее про сегодняшний день": "answer",
		"Помоги выбрать велосипед": "redirect", "Сколько я был на велосипеде?": "answer",
		"Расскажи мне анекдот": "redirect", "Почему в будни у меня меньше шагов?": "answer",
	} {
		if got := chatMode(text); got != want {
			t.Errorf("%s: %s, want %s", text, got, want)
		}
	}
}

func TestChatContextBoundsAndReplyLength(t *testing.T) {
	req := ChatInput{ID: "starter:2026-11-28", Text: "Привет", Source: "starter"}
	if !req.Valid() {
		t.Fatal("valid starter rejected")
	}
	req.History = []ChatLine{{Role: "system", Text: "настройки"}}
	if req.Valid() {
		t.Fatal("unexpected role accepted")
	}
	req.History = []ChatLine{{Role: "user", Text: strings.Repeat("ш", 2000)}, {Role: "assistant", Text: strings.Repeat("э", 2000)}, {Role: "user", Text: strings.Repeat("🌱", 2000)}, {Role: "assistant", Text: "да"}}
	if got := recentLines(req.History); len(got) != 3 || got[0].Role != "assistant" {
		t.Fatalf("context window %d", len(got))
	}
	out, err := resolveChat("answer", "Как мой день?", nil, llm.ChatDecision{Kind: "answer", Text: strings.Repeat("🌱", 1030)}, false)
	if err != nil || utf8.RuneCountInString(out.reply.Text) != 1000 || len(out.reply.Pills) != 2 {
		t.Fatal("invalid output limits", err)
	}
}

func TestMemoryRequiresExplicitRequestAndExactFact(t *testing.T) {
	text := "Запомни: теперь я работаю ночью"
	old := []repo.ChatFact{{ID: 3, Key: "work_schedule", Text: "я работаю днём"}}
	d := llm.ChatDecision{Kind: "remember", Key: "work_schedule", Fact: "теперь я работаю ночью", ReplaceID: 3}
	out, err := resolveChat("remember", text, old, d, false)
	if err != nil || out.change == nil || out.change.ID != 3 {
		t.Fatal("explicit correction lost", err)
	}
	if out, err = resolveChat("answer", text, old, d, false); err == nil || out.change != nil {
		t.Fatal("unauthorized memory change")
	}
	d.Fact = "я сплю до полудня"
	if out, _ = resolveChat("remember", text, old, d, false); out.reply.Kind != "clarify" || out.change != nil {
		t.Fatal("unsupported fact accepted")
	}
	d.Fact = "теперь я работаю ночью"
	d.ReplaceID = 93
	if out, _ = resolveChat("remember", text, old, d, false); out.change != nil {
		t.Fatal("foreign/unknown fact selected")
	}
}

func TestMemoryListPreservesFactsAndPaginates(t *testing.T) {
	facts := make([]repo.ChatFact, 8)
	for i := range facts {
		facts[i] = repo.ChatFact{ID: int64(i + 1), Text: strings.Repeat(string(rune('а'+i)), 220)}
	}
	page, cursor := selectMemoryPage(facts)
	if len(page) != 4 || cursor != 4 {
		t.Fatalf("page len %d cursor %d", len(page), cursor)
	}
	out, err := resolveChat("memory_list", "Что помнишь?", page, llm.ChatDecision{OrderedIDs: []int64{2, 55, 2, 1}, Text: "лишний факт"}, true)
	if err != nil || utf8.RuneCountInString(out.reply.Text) > 1000 || strings.Contains(out.reply.Text, "лишний") || out.reply.Pills[0] != "Показать ещё" {
		t.Fatal("invalid memory page", err)
	}
	for _, f := range page {
		if strings.Count(out.reply.Text, f.Text) != 1 {
			t.Fatal("lost or duplicate fact")
		}
	}
}
