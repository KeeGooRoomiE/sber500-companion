package push

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// Sender sends to people and keeps the books: every send is a call_log row, and a token the
// provider rejects is dropped so the next audience does not hit it again.
type Sender struct {
	client  *Client
	users   *repo.UserRepo
	callLog *analytics.Logger
}

// NewSender returns nil when push is not configured.
func NewSender(c *Client, users *repo.UserRepo, callLog *analytics.Logger) *Sender {
	if c == nil {
		return nil
	}
	return &Sender{client: c, users: users, callLog: callLog}
}

// Result is the outcome of a batch.
type Result struct {
	Sent    int `json:"sent"`
	Failed  int `json:"failed"`
	Dropped int `json:"dropped_tokens"`
}

// Send delivers [m] to every target, one by one, and logs each as [trigger] (scheduled for
// automatic sends, admin for the owner's own). The rows are tool calls, not LLM calls: they
// never enter calls-per-DAU, but they are in the log so a background send can never pass for a
// person acting.
func (s *Sender) Send(ctx context.Context, targets []repo.PushTarget, m Message, trigger analytics.Trigger) Result {
	var res Result
	for _, t := range targets {
		start := time.Now()
		err := s.client.Send(ctx, t.Token, m)
		ev := analytics.CallEvent{
			UserID:      t.UserID,
			Timestamp:   start,
			CallType:    analytics.CallTypeTool,
			Component:   analytics.ComponentPush,
			Trigger:     trigger,
			UserVisible: err == nil,
			Result:      "ok",
			LatencyMs:   time.Since(start).Milliseconds(),
		}
		if err != nil {
			ev.Result = "error"
			code := "send_failed"
			if errors.Is(err, ErrInvalidToken) {
				code = "invalid_token"
				if cerr := s.users.ClearPushToken(ctx, t.UserID, t.Token); cerr == nil {
					res.Dropped++
				}
			}
			ev.ErrorCode = &code
			res.Failed++
			slog.Warn("push: send failed", "user", t.UserID, "err", err)
		} else {
			res.Sent++
		}
		s.callLog.Log(ctx, ev)
	}
	return res
}
