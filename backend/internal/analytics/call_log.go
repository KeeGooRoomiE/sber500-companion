package analytics

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type CallType string
type Component string
type Trigger string

const (
	CallTypeLLM        CallType = "llm"
	CallTypeTool       CallType = "tool"
	CallTypeBackground CallType = "background"

	ComponentLLMMorning Component = "llm_morning"
	ComponentLLMCheckin Component = "llm_checkin"
	ComponentLLMWeekly  Component = "llm_weekly"
	ComponentUsageStats Component = "usagestats"
	ComponentHealthConn Component = "health_connect"
	ComponentCalendar   Component = "calendar"
	ComponentCheckin    Component = "checkin"
	ComponentMorningAPI Component = "morning_api"

	TriggerUserAction Trigger = "user_action"
	TriggerScheduled  Trigger = "scheduled"
	TriggerFollowup   Trigger = "followup"
)

type CallEvent struct {
	UserID      string    `json:"user_id"`
	Timestamp   time.Time `json:"timestamp"`
	SessionID   string    `json:"session_id"`
	CallType    CallType  `json:"call_type"`
	Component   Component `json:"component"`
	Trigger     Trigger   `json:"trigger"`
	UserVisible bool      `json:"user_visible"`
	Result      string    `json:"result"`
	ErrorCode   *string   `json:"error_code"`
	LatencyMs   int64     `json:"latency_ms"`
}

type Logger struct {
	db         *pgxpool.Pool
	devUserIDs map[string]bool
}

func NewLogger(db *pgxpool.Pool, devUserIDs []string) *Logger {
	m := make(map[string]bool, len(devUserIDs))
	for _, id := range devUserIDs {
		m[id] = true
	}
	return &Logger{db: db, devUserIDs: m}
}

// Log writes the event asynchronously. The request context is cancelled as soon as the
// handler returns, so the write runs detached from it (WithoutCancel) with its own timeout —
// otherwise most inserts would fail with "context canceled" and the anti-fraud log would have holes.
func (l *Logger) Log(ctx context.Context, e CallEvent) {
	if l.devUserIDs[e.UserID] {
		return
	}
	go func() {
		wctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		if err := l.persist(wctx, e); err != nil {
			b, _ := json.Marshal(e)
			slog.Error("call_log persist failed", "err", err, "event", string(b))
		}
	}()
}

func (l *Logger) persist(ctx context.Context, e CallEvent) error {
	_, err := l.db.Exec(ctx, `
		INSERT INTO call_log
			(user_id, ts, session_id, call_type, component, trigger, user_visible, result, error_code, latency_ms)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
		e.UserID, e.Timestamp, e.SessionID,
		string(e.CallType), string(e.Component), string(e.Trigger),
		e.UserVisible, e.Result, e.ErrorCode, e.LatencyMs,
	)
	return err
}
