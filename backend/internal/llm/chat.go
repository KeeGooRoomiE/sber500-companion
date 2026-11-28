package llm

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"strings"
	"time"

	openai "github.com/sashabaranov/go-openai"
)

var ErrChatResponse = errors.New("invalid structured chat response")

//go:embed prompts/chat_system.md
var DefaultChatSystem string

type ChatAttempt func(model string, started time.Time, usage openai.Usage, err error)

type ChatDecision struct {
	Kind       string   `json:"kind"`
	Text       string   `json:"text"`
	Pills      []string `json:"pills"`
	Key        string   `json:"key"`
	Fact       string   `json:"fact"`
	ReplaceID  int64    `json:"replace_id"`
	OrderedIDs []int64  `json:"ordered_ids"`
}

// The same primary and fallback chain serves chat and forecasts. Each real provider attempt
// feeds the audit log; formatting and suggestions are produced in the same completion.
func (c *Client) Chat(ctx context.Context, system string, input []byte, observe ChatAttempt) (ChatDecision, error) {
	req := openai.ChatCompletionRequest{
		Messages:  []openai.ChatCompletionMessage{{Role: openai.ChatMessageRoleSystem, Content: system}, {Role: openai.ChatMessageRoleUser, Content: string(input)}},
		MaxTokens: 850, Temperature: 0.3,
	}
	resp, _, err := c.completeObserved(ctx, req, observe)
	if err != nil {
		return ChatDecision{}, err
	}
	if len(resp.Choices) == 0 {
		return ChatDecision{}, ErrChatResponse
	}
	raw := strings.TrimSpace(resp.Choices[0].Message.Content)
	if strings.HasPrefix(raw, "```") && strings.HasSuffix(raw, "```") {
		_, raw, _ = strings.Cut(raw, "\n")
		raw = strings.TrimSpace(strings.TrimSuffix(raw, "```"))
	}
	var result ChatDecision
	if err = json.Unmarshal([]byte(raw), &result); err != nil {
		return result, ErrChatResponse
	}
	return result, nil
}
