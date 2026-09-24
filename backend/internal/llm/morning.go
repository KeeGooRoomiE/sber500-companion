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

func (c *Client) GenerateMorning(ctx context.Context, days []*repo.DailyData, last *repo.CheckIn) (*GenerateResult, error) {
	prompt := buildPrompt(days, last)

	start := time.Now()
	resp, err := c.ai.CreateChatCompletion(ctx, openai.ChatCompletionRequest{
		Model: c.model,
		Messages: []openai.ChatCompletionMessage{
			{
				Role: openai.ChatMessageRoleSystem,
				Content: "Ты персональный цифровой компаньон. " +
					"Анализируй данные и давай короткий, честный, ободряющий прогноз дня. " +
					"Не более 3 предложений. Пиши по-русски, без лишних формальностей.",
			},
			{Role: openai.ChatMessageRoleUser, Content: prompt},
		},
		MaxTokens:   300,
		Temperature: 0.7,
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

func buildPrompt(days []*repo.DailyData, last *repo.CheckIn) string {
	var b strings.Builder
	b.WriteString("Данные за последние дни:\n\n")

	for _, d := range days {
		b.WriteString(fmt.Sprintf("— %s: ", d.Date.Format("02 Jan")))
		parts := []string{}
		if d.ScreenMin != nil {
			parts = append(parts, fmt.Sprintf("экран %d мин", *d.ScreenMin))
		}
		if d.SleepMin != nil {
			h := *d.SleepMin / 60
			m := *d.SleepMin % 60
			parts = append(parts, fmt.Sprintf("сон %dч%02dм", h, m))
		}
		if d.Unlocks != nil {
			parts = append(parts, fmt.Sprintf("%d разблокировок", *d.Unlocks))
		}
		if d.Steps != nil {
			parts = append(parts, fmt.Sprintf("%d шагов", *d.Steps))
		}
		if len(d.TopApps) > 0 {
			parts = append(parts, fmt.Sprintf("топ-приложение: %s (%d мин)", d.TopApps[0].Package, d.TopApps[0].Minutes))
		}
		b.WriteString(strings.Join(parts, ", "))
		b.WriteString("\n")
	}

	if last != nil {
		b.WriteString(fmt.Sprintf("\nПоследний чек-ин (%s): %s",
			last.Date.Format("02 Jan"), last.DayFeel))
		if len(last.Tags) > 0 {
			b.WriteString(", теги: " + strings.Join(last.Tags, ", "))
		}
		b.WriteString("\n")
	}

	b.WriteString("\nСоставь утренний прогноз на сегодня.")
	return b.String()
}
