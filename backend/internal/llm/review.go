package llm

import (
	"context"
	_ "embed"
	"fmt"
	"strings"
	"time"

	openai "github.com/sashabaranov/go-openai"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

// Built-in system prompts for on-demand texts (DB versions take precedence).
//
//go:embed prompts/day_review_system.md
var DefaultDayReviewSystem string

//go:embed prompts/weekly_system.md
var DefaultWeeklySystem string

// DayReviewInput: one day explained on request.
type DayReviewInput struct {
	Day     *repo.DailyData
	Usual   []*repo.DailyData // the days before it, for the person's norm
	CheckIn *repo.CheckIn     // for that day, may be nil
	Signals []signals.Signal
	Profile map[string]string
	Partial bool // the day is today and still going
}

// WeeklyInput: the last days summed up on request.
type WeeklyInput struct {
	Days     []*repo.DailyData           // oldest first
	CheckIns map[string]*repo.CheckIn    // by date
	Signals  map[string][]signals.Signal // by date
	Profile  map[string]string
}

func BuildDayReview(in DayReviewInput) string {
	var b strings.Builder
	work := profileContext(&b, in.Profile)
	b.WriteString(signals.PromptBlock(in.Signals))
	if in.Partial {
		b.WriteString("День ещё идёт — данные до текущего часа.\n\n")
	}
	if avgLine := usualLine(in.Usual); avgLine != "" {
		b.WriteString("Обычно у человека: " + avgLine + "\n\n")
	}
	// The days themselves, not just their average: with only the average the model had nothing
	// to read when it talked about «позавчера» or «в прошлые дни» and made those days up.
	b.WriteString(previousDays(in.Usual, work))
	b.WriteString("Этот день (" + in.Day.Date.Format("02.01") + "): " + dayLine(in.Day, work) + "\n")
	if p := hourlyPicture(in.Day.HourlyUnlocks); p != "" {
		b.WriteString("Разблокировки по времени суток: " + p + "\n")
	}
	if in.CheckIn != nil {
		b.WriteString("Вечерняя отметка: " + checkInLine(in.CheckIn) + "\n")
	}
	b.WriteString("\nОбъясни этот день.")
	return b.String()
}

// maxPreviousDays caps the per-day list in a day review: enough for «а два дня назад?», short
// enough not to swell the prompt.
const maxPreviousDays = 7

// previousDays lists the most recent days before the reviewed one, oldest first, each with its
// date and numbers. Days without any data are left out — «данных нет» lines would only invite
// the model to guess. Empty when there is nothing to show.
func previousDays(days []*repo.DailyData, work map[string]bool) string {
	var lines []string
	for i := len(days) - 1; i >= 0 && len(lines) < maxPreviousDays; i-- {
		line := dayLine(days[i], work)
		if line == "данных нет" {
			continue
		}
		lines = append(lines, fmt.Sprintf("— %s: %s\n", weekday(days[i].Date)+" "+days[i].Date.Format("02.01"), line))
	}
	if len(lines) == 0 {
		return ""
	}
	for i, j := 0, len(lines)-1; i < j; i, j = i+1, j-1 {
		lines[i], lines[j] = lines[j], lines[i]
	}
	return "Предыдущие дни:\n" + strings.Join(lines, "") + "\n"
}

func BuildWeekly(in WeeklyInput) string {
	var b strings.Builder
	work := profileContext(&b, in.Profile)
	if avgLine := usualLine(in.Days); avgLine != "" {
		b.WriteString("В среднем за эти дни: " + avgLine + "\n\n")
	}
	b.WriteString("По дням:\n")
	for _, d := range in.Days {
		key := d.Date.Format("2006-01-02")
		fmt.Fprintf(&b, "— %s: %s", weekday(d.Date)+" "+d.Date.Format("02.01"), dayLine(d, work))
		if c := in.CheckIns[key]; c != nil {
			b.WriteString("; отметка: " + checkInLine(c))
		}
		if s := in.Signals[key]; len(s) > 0 {
			titles := make([]string, len(s))
			for i, x := range s {
				titles[i] = strings.ToLower(x.Title)
			}
			b.WriteString("; заметно: " + strings.Join(titles, ", "))
		}
		b.WriteString("\n")
	}
	b.WriteString("\nПодведи итоги недели.")
	return b.String()
}

// Complete runs a single system+user completion (shared by reviews).
func (c *Client) Complete(ctx context.Context, system, user string, maxTokens, maxChars int) (*GenerateResult, error) {
	start := time.Now()
	resp, used, err := c.complete(ctx, openai.ChatCompletionRequest{
		Messages: []openai.ChatCompletionMessage{
			{Role: openai.ChatMessageRoleSystem, Content: system},
			{Role: openai.ChatMessageRoleUser, Content: user},
		},
		MaxTokens:   maxTokens,
		Temperature: 0.7,
	})
	latency := int(time.Since(start).Milliseconds())
	if err != nil {
		return nil, err
	}
	if len(resp.Choices) == 0 {
		return nil, fmt.Errorf("empty completion")
	}
	return &GenerateResult{
		Model:            used,
		Message:          capMessage(strings.TrimSpace(resp.Choices[0].Message.Content), maxChars),
		PromptTokens:     resp.Usage.PromptTokens,
		CompletionTokens: resp.Usage.CompletionTokens,
		LatencyMs:        latency,
	}, nil
}

func dayLine(d *repo.DailyData, work map[string]bool) string {
	var parts []string
	if d.ScreenMin != nil {
		parts = append(parts, "экран "+hm(*d.ScreenMin))
	}
	if d.Unlocks != nil {
		parts = append(parts, fmt.Sprintf("%d разблокировок", *d.Unlocks))
	}
	if d.SleepMin != nil {
		parts = append(parts, "сон "+hm(*d.SleepMin))
	}
	if d.LastUnlock != nil {
		parts = append(parts, "последняя разблокировка "+*d.LastUnlock)
	}
	for i, a := range d.TopApps {
		if i == 3 {
			break
		}
		parts = append(parts, fmt.Sprintf("%s%s %s", resolveAppName(a.Package), workMark(work, a.Package), minutes(a.Minutes)))
	}
	if len(parts) == 0 {
		return "данных нет"
	}
	return strings.Join(parts, ", ")
}

func usualLine(days []*repo.DailyData) string {
	var screen, unlocks, sleep, ns, nu, nsl int
	for _, d := range days {
		if d.ScreenMin != nil {
			screen += *d.ScreenMin
			ns++
		}
		if d.Unlocks != nil {
			unlocks += *d.Unlocks
			nu++
		}
		if d.SleepMin != nil {
			sleep += *d.SleepMin
			nsl++
		}
	}
	var parts []string
	if ns > 0 {
		parts = append(parts, "экран "+hm(screen/ns))
	}
	if nu > 0 {
		parts = append(parts, fmt.Sprintf("%d разблокировок", unlocks/nu))
	}
	if nsl > 0 {
		parts = append(parts, "сон "+hm(sleep/nsl))
	}
	return strings.Join(parts, ", ")
}

// hourlyPicture compresses 24 hourly values into parts of the day.
func hourlyPicture(h []int) string {
	if len(h) != 24 {
		return ""
	}
	blocks := []struct {
		name     string
		from, to int
	}{{"ночь 0–7", 0, 7}, {"утро 7–12", 7, 12}, {"день 12–18", 12, 18}, {"вечер 18–24", 18, 24}}
	var parts []string
	for _, bl := range blocks {
		s := 0
		for i := bl.from; i < bl.to; i++ {
			s += h[i]
		}
		parts = append(parts, fmt.Sprintf("%s — %d", bl.name, s))
	}
	return strings.Join(parts, ", ")
}

func checkInLine(c *repo.CheckIn) string {
	feel := map[string]string{"ok": "хороший день", "meh": "средний день", "hard": "тяжёлый день"}[c.DayFeel]
	if feel == "" {
		feel = c.DayFeel
	}
	if len(c.Tags) > 0 {
		feel += " (" + strings.Join(c.Tags, ", ") + ")"
	}
	return feel
}

// plural picks the Russian form for n: one / few / many.
func plural(n int, one, few, many string) string {
	if n < 0 {
		n = -n
	}
	if r := n % 100; r >= 11 && r <= 14 {
		return many
	}
	switch n % 10 {
	case 1:
		return one
	case 2, 3, 4:
		return few
	}
	return many
}

// minutes spells a count of minutes out in words, never abbreviated.
func minutes(m int) string {
	return fmt.Sprintf("%d %s", m, plural(m, "минута", "минуты", "минут"))
}

// hm spells a duration out in words. The compact forms it replaced — «5м» for five
// minutes against «5ч00м» for five hours — read to the model as the same thing: 46% of
// day reviews turned five minutes into five hours. Spelled out, the two cannot collide,
// and the few extra tokens are cheaper than a wrong number in the text a person reads.
func hm(m int) string {
	if m < 60 {
		return minutes(m)
	}
	h, r := m/60, m%60
	out := fmt.Sprintf("%d %s", h, plural(h, "час", "часа", "часов"))
	if r > 0 {
		out += " " + minutes(r)
	}
	return out
}

func weekday(t time.Time) string {
	return [...]string{"вс", "пн", "вт", "ср", "чт", "пт", "сб"}[t.Weekday()]
}
