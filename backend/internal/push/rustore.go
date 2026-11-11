// Package push sends server-initiated notifications through RuStore Push.
//
// Every message goes out as a «notification» message, never data-only: the RuStore client on
// the phone draws it itself. A data-only message needs our own process to be started and, on
// the phones tried so far, it never arrived (11.11 test: four data pushes lost, four
// notification pushes delivered).
package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

const defaultBase = "https://vkpns.rustore.ru"

// ErrInvalidToken means the provider no longer knows this device token: drop it.
var ErrInvalidToken = errors.New("push: device token rejected")

// Message is what the person sees.
type Message struct {
	Title string
	Body  string
}

type Client struct {
	projectID string
	token     string
	base      string
	http      *http.Client
}

// NewFromEnv reads RUSTORE_PUSH_PROJECT_ID and RUSTORE_PUSH_TOKEN (the service token from the
// RuStore console). Without both it returns nil: sending is simply off, and callers check.
func NewFromEnv() *Client {
	project := strings.TrimSpace(os.Getenv("RUSTORE_PUSH_PROJECT_ID"))
	token := strings.TrimSpace(os.Getenv("RUSTORE_PUSH_TOKEN"))
	if project == "" || token == "" {
		return nil
	}
	return &Client{projectID: project, token: token, base: defaultBase, http: &http.Client{Timeout: 15 * time.Second}}
}

// Send delivers one notification to one device. Success only means the provider accepted it:
// delivery can lag by minutes on a phone with a bad connection.
func (c *Client) Send(ctx context.Context, deviceToken string, m Message) error {
	payload, err := json.Marshal(map[string]any{
		"message": map[string]any{
			"token":        deviceToken,
			"notification": map[string]string{"title": m.Title, "body": m.Body},
		},
	})
	if err != nil {
		return err
	}
	url := fmt.Sprintf("%s/v1/projects/%s/messages:send", c.base, c.projectID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<10))
	switch {
	case resp.StatusCode/100 == 2:
		return nil
	case resp.StatusCode == http.StatusNotFound:
		// The project id and service token are ours and known good, so a 404 on a send is the
		// device token the provider does not recognise.
		return fmt.Errorf("%w: %s", ErrInvalidToken, strings.TrimSpace(string(body)))
	default:
		return fmt.Errorf("push: provider answered %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
}
