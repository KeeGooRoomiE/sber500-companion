package push

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Automatic notifications (plan 1.1.0, items 4 and 9). Both go only to people who stopped
// opening the app; whoever is using it gets nothing from here.
const (
	CampaignReactivation = "reactivation"
	CampaignWeekly       = "weekly_silent"

	// Evening of the person's own clock: the day is mostly over, a look at it makes sense.
	reactivationHour = 18
	// A person silent for longer than this has left; writing to them is noise.
	reactivationMaxDays = 14

	// Sunday midday, the week is nearly whole.
	weeklyHour    = 12
	weeklyWeekday = 0
	weeklyMaxDays = 28
	weeklyGapDays = 6
)

// Settings are read from the remote config on every tick, so a change needs no restart.
type Settings struct {
	SilenceDays       int
	ReactivationOn    bool
	WeeklyOn          bool
	ReactivationTexts []string // «Заголовок | текст»; empty means the built-in ones
	WeeklyTexts       []string
}

var defaultReactivation = []Message{
	{Title: "Давно не виделись", Body: "За эти дни накопилось, что рассказать о твоём ритме. Загляни на минуту."},
	{Title: "Прогноз тебя ждёт", Body: "Пока тебя не было, данных стало больше. Покажу, что изменилось."},
}

var defaultWeekly = []Message{
	{Title: "Твоя неделя на одном экране", Body: "Собрал, как прошла неделя: сон, экран и что на них влияло. Посмотри сводку."},
}

// ParseMessage reads «Заголовок | текст». Anything else is not a message.
func ParseMessage(s string) (Message, bool) {
	title, body, ok := strings.Cut(s, "|")
	title, body = strings.TrimSpace(title), strings.TrimSpace(body)
	if !ok || title == "" || body == "" {
		return Message{}, false
	}
	return Message{Title: title, Body: body}, true
}

// pick takes the configured texts when at least one parses, the built-in ones otherwise, and
// rotates by day so the same person does not read the same line twice in a row.
func pick(configured []string, builtin []Message, day time.Time) Message {
	var pool []Message
	for _, s := range configured {
		if m, ok := ParseMessage(s); ok {
			pool = append(pool, m)
		}
	}
	if len(pool) == 0 {
		pool = builtin
	}
	return pool[day.YearDay()%len(pool)]
}

// Campaigns runs the automatic notifications once an hour.
type Campaigns struct {
	sender   *Sender
	users    *repo.UserRepo
	settings func() Settings
}

// NewCampaigns returns nil when push is off: there is nothing to run.
func NewCampaigns(sender *Sender, users *repo.UserRepo, settings func() Settings) *Campaigns {
	if sender == nil {
		return nil
	}
	return &Campaigns{sender: sender, users: users, settings: settings}
}

// nextTick is two minutes past the next hour: audiences are picked by the local hour, and the
// margin keeps a slightly early wake-up from landing in the previous one.
func nextTick(now time.Time) time.Time {
	return now.Truncate(time.Hour).Add(time.Hour + 2*time.Minute)
}

func (c *Campaigns) Start(ctx context.Context) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Until(nextTick(time.Now()))):
			}
			c.Tick(ctx, time.Now())
		}
	}()
}

// Tick runs every enabled campaign for the people whose local hour has come.
func (c *Campaigns) Tick(ctx context.Context, now time.Time) {
	s := c.settings()
	if s.SilenceDays < 1 {
		return
	}
	if s.ReactivationOn {
		c.run(ctx, repo.CampaignQuery{
			Campaign:      CampaignReactivation,
			MinSilentDays: s.SilenceDays,
			MaxSilentDays: max(reactivationMaxDays, s.SilenceDays),
			LocalHour:     reactivationHour,
			Weekday:       -1,
			OncePerStreak: true,
		}, pick(s.ReactivationTexts, defaultReactivation, now))
	}
	if s.WeeklyOn {
		c.run(ctx, repo.CampaignQuery{
			Campaign:      CampaignWeekly,
			MinSilentDays: s.SilenceDays,
			MaxSilentDays: max(weeklyMaxDays, s.SilenceDays),
			LocalHour:     weeklyHour,
			Weekday:       weeklyWeekday,
			MinGapDays:    weeklyGapDays,
		}, pick(s.WeeklyTexts, defaultWeekly, now))
	}
}

func (c *Campaigns) run(ctx context.Context, q repo.CampaignQuery, m Message) {
	targets, err := c.users.PushTargetsCampaign(ctx, q)
	if err != nil {
		slog.Error("push campaign: audience", "campaign", q.Campaign, "err", err)
		return
	}
	if len(targets) == 0 {
		return
	}
	ids := make([]string, len(targets))
	for i, t := range targets {
		ids[i] = t.UserID
	}
	if err := c.users.MarkCampaignSent(ctx, q.Campaign, ids); err != nil {
		slog.Error("push campaign: mark", "campaign", q.Campaign, "err", err)
		return // not recorded means it could repeat next hour: better not to send at all
	}
	res := c.sender.Send(ctx, targets, m, analytics.TriggerScheduled)
	slog.Info("push campaign", "campaign", q.Campaign, "audience", len(targets), "sent", res.Sent, "failed", res.Failed)
}
