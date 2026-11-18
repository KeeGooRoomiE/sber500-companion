package push

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func testClient(t *testing.T, status int, seen *map[string]any, auth *string) *Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if seen != nil {
			_ = json.NewDecoder(r.Body).Decode(seen)
		}
		if auth != nil {
			*auth = r.Header.Get("Authorization")
		}
		if r.URL.Path != "/v1/projects/proj/messages:send" {
			t.Errorf("path = %s", r.URL.Path)
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)
	return &Client{projectID: "proj", token: "svc", base: srv.URL, http: srv.Client()}
}

func TestSendIsAlwaysANotificationMessage(t *testing.T) {
	var body map[string]any
	var auth string
	c := testClient(t, 200, &body, &auth)
	if err := c.Send(context.Background(), "dev-token", Message{Title: "t", Body: "b"}); err != nil {
		t.Fatal(err)
	}
	if auth != "Bearer svc" {
		t.Errorf("auth = %q", auth)
	}
	msg := body["message"].(map[string]any)
	if msg["token"] != "dev-token" {
		t.Errorf("token = %v", msg["token"])
	}
	if _, ok := msg["notification"]; !ok {
		t.Error("data-only message: must carry a notification block")
	}
	if _, ok := msg["data"]; ok {
		t.Error("must not be a data message")
	}
}

func TestSendUnknownTokenIsReportedAsInvalid(t *testing.T) {
	c := testClient(t, 404, nil, nil)
	err := c.Send(context.Background(), "gone", Message{Title: "t", Body: "b"})
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("err = %v, want ErrInvalidToken", err)
	}
}

func TestSendProviderErrorIsNotAnInvalidToken(t *testing.T) {
	c := testClient(t, 500, nil, nil)
	err := c.Send(context.Background(), "x", Message{Title: "t", Body: "b"})
	if err == nil || errors.Is(err, ErrInvalidToken) {
		t.Fatalf("err = %v, want a plain failure", err)
	}
}
