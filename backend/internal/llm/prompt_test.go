package llm

import (
	"strings"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func TestBuildPromptProfile(t *testing.T) {
	screen := 200
	days := []*repo.DailyData{{
		Date:      time.Date(2026, 9, 25, 0, 0, 0, 0, time.UTC),
		ScreenMin: &screen,
		TopApps:   []repo.AppUsage{{Package: "com.slack", Minutes: 90}, {Package: "org.telegram.messenger", Minutes: 40}},
	}}
	p := buildPrompt(MorningInput{Days: days, Profile: map[string]string{
		"work_place": "Из дома",
		"wearable":   "Нет",
		"work_apps":  "com.slack",
		"name":       "Саша", // must never reach the model
	}})

	for _, want := range []string{"Контекст о человеке", "Работает/учится: Из дома", "Рабочие приложения: Slack", "Slack (рабочее) 90м", "Часов нет"} {
		if !strings.Contains(p, want) {
			t.Errorf("prompt lacks %q:\n%s", want, p)
		}
	}
	if strings.Contains(p, "Саша") {
		t.Error("name leaked into the prompt")
	}
	if strings.Contains(p, "Telegram (рабочее)") {
		t.Error("non-work app marked as work")
	}

	if empty := buildPrompt(MorningInput{Days: days}); strings.Contains(empty, "Контекст") {
		t.Error("context block without a profile")
	}
}
