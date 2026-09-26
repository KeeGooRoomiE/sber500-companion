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

func TestResolveAppName(t *testing.T) {
	cases := map[string]string{
		"org.telegram.messenger": "Telegram",
		"com.bitrix24.android":   "Битрикс24",
		"com.example.coolapp":    "Coolapp",
		"ru.somebank.mobile.app": "Somebank",
		"com.unknown.android":    "Unknown",
		"single":                 "Single",
	}
	for pkg, want := range cases {
		if got := resolveAppName(pkg); got != want {
			t.Errorf("resolveAppName(%q) = %q, want %q", pkg, got, want)
		}
	}
}

func TestSleepTrendIgnoresNoise(t *testing.T) {
	mk := func(sleep ...int) []*repo.DailyData {
		var out []*repo.DailyData
		for i, s := range sleep {
			v := s
			out = append(out, &repo.DailyData{Date: time.Date(2026, 9, 10+i, 0, 0, 0, 0, time.UTC), SleepMin: &v})
		}
		return out
	}
	// 7:21 → 7:20 → 7:05 → 6:55: small steps, 26 min in total — not a trend
	if p := BuildMorningPrompt(MorningInput{Days: mk(441, 440, 425, 415)}); strings.Contains(p, "Сон снижается") {
		t.Error("tiny drops must not be called a falling-sleep trend")
	}
	// 7:30 → 6:50 → 6:10 → 5:20: a real slide
	if p := BuildMorningPrompt(MorningInput{Days: mk(450, 410, 370, 320)}); !strings.Contains(p, "Сон снижается 4 дня подряд") {
		t.Error("a real 2h slide must be flagged")
	}
}
