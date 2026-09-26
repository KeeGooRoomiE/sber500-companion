package llm

import (
	"context"
	_ "embed"
	"fmt"
	"os"
	"sort"
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
		cfg.BaseURL = "https://shared1.multitool.works:4000/v1"
	}
	model := os.Getenv("LLM_MODEL")
	if model == "" {
		model = "gigachat-3-pro"
	}
	return &Client{ai: openai.NewClientWithConfig(cfg), model: model}
}

type GenerateResult struct {
	Message          string
	PromptTokens     int
	CompletionTokens int
	LatencyMs        int
}

// DefaultMorningSystem is the built-in system prompt (prompts/morning_system.md).
// The active version normally comes from the prompts table; this is the fallback.
//
//go:embed prompts/morning_system.md
var DefaultMorningSystem string

// Model is the configured model name (stored with each message for cost accounting).
func (c *Client) Model() string { return c.model }

// MorningInput is everything the morning prompt is built from.
type MorningInput struct {
	Days    []*repo.DailyData // oldest first, up to yesterday
	Last    *repo.CheckIn     // latest check-in, may be nil
	First   bool              // very first message: a short retrospective of the person's week
	Profile map[string]string // answers from «Расскажи о себе» (no name)
}

// BuildMorningPrompt renders the user message.
func BuildMorningPrompt(in MorningInput) string { return buildPrompt(in) }

// GenerateMorning calls the model with the given system prompt and the user's data.
func (c *Client) GenerateMorning(ctx context.Context, system string, in MorningInput) (*GenerateResult, error) {
	prompt := buildPrompt(in)

	start := time.Now()
	resp, err := c.ai.CreateChatCompletion(ctx, openai.ChatCompletionRequest{
		Model: c.model,
		Messages: []openai.ChatCompletionMessage{
			{Role: openai.ChatMessageRoleSystem, Content: system},
			{Role: openai.ChatMessageRoleUser, Content: prompt},
		},
		MaxTokens:   100, // ~200 chars in Russian at ~2 chars/token
		Temperature: 0.75,
	})
	latency := int(time.Since(start).Milliseconds())
	if err != nil {
		return nil, err
	}

	return &GenerateResult{
		Message:          capMessage(strings.TrimSpace(resp.Choices[0].Message.Content), 220),
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

// capMessage trims the message to the last complete sentence within maxChars.
func capMessage(s string, maxChars int) string {
	runes := []rune(s)
	if len(runes) <= maxChars {
		return s
	}
	cut := string(runes[:maxChars])
	// find last sentence-ending punctuation
	for i := len(cut) - 1; i >= 0; i-- {
		if cut[i] == '.' || cut[i] == '!' || cut[i] == '?' {
			return strings.TrimSpace(cut[:i+1])
		}
	}
	return strings.TrimSpace(cut)
}

func parseHHMM(s string) time.Time {
	t, err := time.Parse("15:04", s)
	if err != nil {
		return time.Time{}
	}
	return t
}

func buildPrompt(in MorningInput) string {
	days, last, first := in.Days, in.Last, in.First
	var b strings.Builder
	workApps := profileContext(&b, in.Profile)

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
				parts = append(parts, fmt.Sprintf("экран %dч%02dм", h, m))
			} else {
				parts = append(parts, fmt.Sprintf("экран %dм", m))
			}
		}
		if d.SleepMin != nil {
			h, m := *d.SleepMin/60, *d.SleepMin%60
			parts = append(parts, fmt.Sprintf("сон %dч%02dм", h, m))
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

		apps := make([]repo.AppUsage, 0, len(d.TopApps))
		for _, a := range d.TopApps {
			if a.Package != "" { // rows saved before the key fix have empty packages
				apps = append(apps, a)
			}
		}
		if len(apps) > 0 {
			top := apps[0]
			name := resolveAppName(top.Package) + workMark(workApps, top.Package)
			parts = append(parts, fmt.Sprintf("топ: %s %dм", name, top.Minutes))
			if len(apps) > 1 {
				top2 := apps[1]
				name2 := resolveAppName(top2.Package) + workMark(workApps, top2.Package)
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

	if first && len(days) > 1 {
		// Day 0: the app just backfilled history — the value is "here is your usual week".
		b.WriteString("\nЭто первое сообщение человеку, он только что установил приложение. " +
			"Опиши его обычную неделю по этим дням: одна самая заметная закономерность и что из неё следует для сегодня. " +
			"Формат и ограничения те же.")
	} else {
		b.WriteString("\nСоставь утренний прогноз на сегодня.")
	}
	return b.String()
}

// profileLines maps profile answers to plain context lines for the model.
var profileLines = []struct{ key, label string }{
	{"work_place", "Работает/учится"},
	{"bedtime", "Обычно ложится"},
	{"wake", "Обычно встаёт"},
	{"wearable", "Часы или браслет"},
	{"triggers", "Что чаще выбивает из колеи"},
	{"goal", "Что хочет изменить"},
	{"tone", "Как с ним говорить"},
}

// profileContext writes the «Контекст о человеке» block and returns the set of work apps.
func profileContext(b *strings.Builder, p map[string]string) map[string]bool {
	work := map[string]bool{}
	for _, pkg := range strings.Split(p["work_apps"], ",") {
		if pkg = strings.TrimSpace(pkg); pkg != "" {
			work[pkg] = true
		}
	}
	var lines []string
	for _, l := range profileLines {
		if v := strings.TrimSpace(p[l.key]); v != "" {
			lines = append(lines, fmt.Sprintf("  %s: %s", l.label, v))
		}
	}
	if len(work) > 0 {
		names := make([]string, 0, len(work))
		for pkg := range work {
			names = append(names, resolveAppName(pkg))
		}
		sort.Strings(names)
		lines = append(lines, "  Рабочие приложения: "+strings.Join(names, ", "))
	}
	if strings.HasPrefix(p["wearable"], "Нет") {
		lines = append(lines, "  Часов нет — сон может быть оценкой по паузе экрана, говори о нём осторожно")
	}
	if len(lines) > 0 {
		b.WriteString("Контекст о человеке (со слов):\n")
		b.WriteString(strings.Join(lines, "\n"))
		b.WriteString("\n\n")
	}
	return work
}

func workMark(work map[string]bool, pkg string) string {
	if work[pkg] {
		return " (рабочее)"
	}
	return ""
}
