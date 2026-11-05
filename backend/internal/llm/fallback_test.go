package llm

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"

	openai "github.com/sashabaranov/go-openai"
)

// The chain exists for a flaky shared proxy, not for bad requests: walking it on a 400 would
// turn one rejected call into one per model and spend budget doing it.
func TestWorthFallback(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"handshake dropped", io.ErrUnexpectedEOF, true},
		{"connection reset", &net.OpError{Op: "read", Err: errors.New("connection reset by peer")}, true},
		{"rate limited", &openai.APIError{HTTPStatusCode: 429}, true},
		{"upstream down", &openai.APIError{HTTPStatusCode: 503}, true},
		{"bad request", &openai.APIError{HTTPStatusCode: 400}, false},
		{"bad key", &openai.APIError{HTTPStatusCode: 401}, false},
		{"model not allowed", &openai.APIError{HTTPStatusCode: 403}, false},
		{"caller gave up", context.Canceled, false},
		{"caller timed out", context.DeadlineExceeded, false},
	}
	for _, c := range cases {
		if got := worthFallback(c.err); got != c.want {
			t.Errorf("%s: worthFallback = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestChainSkipsPrimaryWhenEmpty(t *testing.T) {
	t.Setenv("LLM_MODEL", "primary-model")
	t.Setenv("LLM_FALLBACK_MODELS", " second , ,primary-model, third ")
	c := NewClient()
	want := []string{"primary-model", "second", "third"}
	if len(c.models) != len(want) {
		t.Fatalf("models = %v, want %v", c.models, want)
	}
	for i := range want {
		if c.models[i] != want[i] {
			t.Fatalf("models = %v, want %v", c.models, want)
		}
	}
	if c.Model() != "primary-model" {
		t.Errorf("Model() = %q, want the primary", c.Model())
	}
}
