package llm

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	openai "github.com/sashabaranov/go-openai"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type Client struct {
	ai    *openai.Client
	model string
}

func NewClient() *Client {
	cfg := openai.DefaultConfig(os.Getenv("LLM_API_KEY"))
	cfg.BaseURL = os.Getenv("LLM_BASE_URL")
	if cfg.BaseURL == "" {
		cfg.BaseURL = "https://api.cloud.ru/v1"
	}
	model := os.Getenv("LLM_MODEL")
	if model == "" {
		model = "GigaChat-Pro"
	}
	return &Client{ai: openai.NewClientWithConfig(cfg), model: model}
}

type GenerateResult struct {
	Message          string
	PromptTokens     int
	CompletionTokens int
	LatencyMs        int
}

const systemPrompt = `Ты персональный цифровой компаньон. Утром ты анализируешь данные за последние дни и даёшь честный, конкретный прогноз.

Правила:
1. Сравнивай вчера с ЛИЧНЫМИ средними пользователя, не с «нормой здорового человека».
2. Называй приложения по имени (Instagram, YouTube, Telegram), не «соцсети» или «приложение».
3. Давай ОДНО конкретное действие, не список. «Выйди на 15 минут» лучше, чем «больше двигайся».
4. Замечай ПОЛОЖИТЕЛЬНЫЕ отклонения и хвали их — это работает лучше, чем запреты.
5. Если сон падает 3+ дня подряд — смягчи тон, не дави. Человек и так устал.

Формат: 2–3 предложения. По-русски, без приветствий и подписей.`

func (c *Client) GenerateMorning(ctx context.Context, days []*repo.DailyData, last *repo.CheckIn) (*GenerateResult, error) {
	prompt := buildPrompt(days, last)

	start := time.Now()
	resp, err := c.ai.CreateChatCompletion(ctx, openai.ChatCompletionRequest{
		Model: c.model,
		Messages: []openai.ChatCompletionMessage{
			{Role: openai.ChatMessageRoleSystem, Content: systemPrompt},
			{Role: openai.ChatMessageRoleUser, Content: prompt},
		},
		MaxTokens:   350,
		Temperature: 0.75,
	})
	latency := int(time.Since(start).Milliseconds())
	if err != nil {
		return nil, err
	}

	return &GenerateResult{
		Message:          strings.TrimSpace(resp.Choices[0].Message.Content),
		PromptTokens:     resp.Usage.PromptTokens,
		CompletionTokens: resp.Usage.CompletionTokens,
		LatencyMs:        latency,
	}, nil
}

// appLabel resolves common package names to human-readable labels.
var appLabel = map[string]string{
	"com.instagram.android":        "Instagram",
	"com.instagram.lite":           "Instagram Lite",
	"com.google.android.youtube":   "YouTube",
	"org.telegram.messenger":       "Telegram",
	"org.telegram.messenger.web":   "Telegram",
	"com.vkontakte.android":        "VK",
	"ru.vk.superapp":               "VK",
	"com.whatsapp":                 "WhatsApp",
	"com.tiktok.android":           "TikTok",
	"com.zhiliaoapp.musically":     "TikTok",
	"com.twitter.android":          "Twitter/X",
	"com.facebook.katana":          "Facebook",
	"com.facebook.lite":            "Facebook Lite",
	"com.snapchat.android":         "Snapchat",
	"com.netflix.mediaclient":      "Netflix",
	"ru.ok.android":                "OK",
	"com.google.android.gm":        "Gmail",
	"com.google.android.apps.maps": "Google Maps",
	"ru.sberbank.android.main":     "СберБанк",
	"ru.tinkoff.banking":           "Тинькофф",
	"com.apple.android.music":      "Apple Music",
	"com.spotify.music":            "Spotify",
	"com.google.android.music":     "Google Music",
	"com.android.chrome":           "Chrome",
	"org.mozilla.firefox":          "Firefox",
	"com.microsoft.teams":          "Teams",
	"com.slack":                    "Slack",
	"com.notion.id":                "Notion",
}

func resolveAppName(pkg string) string {
	if label, ok := appLabel[pkg]; ok {
		return label
	}
	// strip com./ru./org. prefix for readability
	parts := strings.Split(pkg, ".")
	if len(parts) >= 2 {
		return parts[len(parts)-1]
	}
	return pkg
}

func parseHHMM(s string) time.Time {
	t, err := time.Parse("15:04", s)
	if err != nil {
		return time.Time{}
	}
	return t
}

func buildPrompt(days []*repo.DailyData, last *repo.CheckIn) string {
	var b strings.Builder

	// compute personal weekly averages
	var sumScreen, sumSleep, sumSteps, sumUnlocks int
	var cntScreen, cntSleep, cntSteps, cntUnlocks int
	for _, d := range days {
		if d.ScreenMin != nil {
			sumScreen += *d.ScreenMin
			cntScreen++
		}
		if d.SleepMin != nil {
			sumSleep += *d.SleepMin
			cntSleep++
		}
		if d.Steps != nil {
			sumSteps += *d.Steps
			cntSteps++
		}
		if d.Unlocks != nil {
			sumUnlocks += *d.Unlocks
			cntUnlocks++
		}
	}

	if cntScreen > 0 || cntSleep > 0 {
		b.WriteString("Личные средние за период:\n")
		if cntScreen > 0 {
			b.WriteString(fmt.Sprintf("  экран: %d мин/день\n", sumScreen/cntScreen))
		}
		if cntSleep > 0 {
			avg := sumSleep / cntSleep
			b.WriteString(fmt.Sprintf("  сон: %dч%02dм\n", avg/60, avg%60))
		}
		if cntSteps > 0 {
			b.WriteString(fmt.Sprintf("  шаги: %d/день\n", sumSteps/cntSteps))
		}
		if cntUnlocks > 0 {
			b.WriteString(fmt.Sprintf("  разблокировок: %d/день\n", sumUnlocks/cntUnlocks))
		}
		b.WriteString("\n")
	}

	// detect declining sleep trend (days are chronological, oldest first)
	decliningDays := 0
	for i := 1; i < len(days); i++ {
		if days[i].SleepMin != nil && days[i-1].SleepMin != nil &&
			*days[i].SleepMin < *days[i-1].SleepMin {
			decliningDays++
		} else {
			decliningDays = 0
		}
	}
	if decliningDays >= 2 {
		b.WriteString(fmt.Sprintf("⚠️ Сон снижается %d дня подряд — тон должен быть мягким.\n\n", decliningDays+1))
	}

	b.WriteString("Данные за последние дни:\n\n")

	for _, d := range days {
		b.WriteString(fmt.Sprintf("— %s: ", d.Date.Format("02 Jan")))
		parts := []string{}

		if d.ScreenMin != nil {
			h, m := *d.ScreenMin/60, *d.ScreenMin%60
			if h > 0 {
				parts = append(parts, fmt.Sprintf("экран %dч%02дм", h, m))
			} else {
				parts = append(parts, fmt.Sprintf("экран %dм", m))
			}
		}
		if d.SleepMin != nil {
			h, m := *d.SleepMin/60, *d.SleepMin%60
			parts = append(parts, fmt.Sprintf("сон %dч%02дм", h, m))
		}
		if d.Unlocks != nil {
			parts = append(parts, fmt.Sprintf("%d разблокировок", *d.Unlocks))
		}
		if d.LastUnlock != nil {
			parts = append(parts, fmt.Sprintf("последняя в %s", *d.LastUnlock))
		}
		if d.Steps != nil {
			parts = append(parts, fmt.Sprintf("%d шагов", *d.Steps))
		}
		if d.BatteryMorning != nil && *d.BatteryMorning < 75 {
			parts = append(parts, fmt.Sprintf("заряд утром %d%%", *d.BatteryMorning))
		}

		if d.Wakeup != nil && d.FirstUnlock != nil {
			wakeupT := parseHHMM(*d.Wakeup)
			unlockT := parseHHMM(*d.FirstUnlock)
			if !wakeupT.IsZero() && !unlockT.IsZero() {
				deltaMin := int(unlockT.Sub(wakeupT).Minutes())
				if deltaMin > 30 {
					parts = append(parts, fmt.Sprintf("✓ проснулся в %s, телефон взял через %dм",
						*d.Wakeup, deltaMin))
				}
			}
		}

		if len(d.TopApps) > 0 {
			top := d.TopApps[0]
			name := resolveAppName(top.Package)
			parts = append(parts, fmt.Sprintf("топ: %s %dм", name, top.Minutes))
			if len(d.TopApps) > 1 {
				top2 := d.TopApps[1]
				name2 := resolveAppName(top2.Package)
				parts = append(parts, fmt.Sprintf("%s %dм", name2, top2.Minutes))
			}
		}

		b.WriteString(strings.Join(parts, ", "))
		b.WriteString("\n")
	}

	if last != nil {
		feel := last.DayFeel
		switch feel {
		case "ok":
			feel = "хороший день"
		case "meh":
			feel = "средний день"
		case "hard":
			feel = "тяжёлый день"
		}
		b.WriteString(fmt.Sprintf("\nПоследний чек-ин (%s): %s",
			last.Date.Format("02 Jan"), feel))
		if len(last.Tags) > 0 {
			b.WriteString(", теги: " + strings.Join(last.Tags, ", "))
		}
		b.WriteString("\n")
	}

	b.WriteString("\nСоставь утренний прогноз на сегодня.")
	return b.String()
}
