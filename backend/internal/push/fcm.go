// Package push sends notifications to phones through Firebase Cloud Messaging (HTTP v1).
//
// Why it exists: Android kills background work. App Standby buckets and OEM battery
// managers (Xiaomi, Honor, Samsung…) mean a local WorkManager job may never run — measured
// on a real device: zero background executions in 16 hours. The morning forecast is the
// product, so its delivery cannot depend on the phone alone. The local worker still runs and
// still wins when it can (it fires at the first unlock, which the server cannot see); this is
// the backstop that guarantees the message arrives at all.
//
// Credentials: a Google service-account JSON, path in FCM_CREDENTIALS_FILE. Without it the
// sender is disabled and every Send is a no-op that reports ErrDisabled — the server runs
// exactly as before, so a missing key never takes the API down.
//
// The service-account OAuth flow is implemented here on the standard library instead of
// golang.org/x/oauth2: that module now requires Go 1.26, and CI reads the toolchain version
// from go.mod, so depending on it would silently upgrade the whole backend. The flow is a
// signed JWT exchanged for an access token — small and stable enough to own.
package push

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

const scope = "https://www.googleapis.com/auth/firebase.messaging"

// ErrDisabled is returned when no credentials are configured.
var ErrDisabled = errors.New("push disabled: FCM_CREDENTIALS_FILE not set")

// ErrUnregistered means the token is dead — the app was uninstalled or refreshed it.
// The caller drops such tokens from the database.
var ErrUnregistered = errors.New("token unregistered")

// serviceAccount is the part of the Google credentials file this package uses.
type serviceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

type Sender struct {
	sa     serviceAccount
	key    *rsa.PrivateKey
	client *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

// Message is one notification. Type rides along as data so the app can tell what it is.
type Message struct {
	Title string
	Body  string
	// Type reaches the app as data.type: morning | day_review | update | custom.
	Type string
}

// New reads FCM_CREDENTIALS_FILE. A nil *Sender is valid and disabled, so callers never
// need a nil check — Enabled reports false and Send returns ErrDisabled.
func New(_ context.Context) *Sender {
	path := strings.TrimSpace(os.Getenv("FCM_CREDENTIALS_FILE"))
	if path == "" {
		slog.Warn("push: disabled (FCM_CREDENTIALS_FILE not set)")
		return nil
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		slog.Error("push: cannot read credentials", "path", path, "err", err)
		return nil
	}
	var sa serviceAccount
	if err := json.Unmarshal(raw, &sa); err != nil {
		slog.Error("push: credentials are not valid json", "path", path, "err", err)
		return nil
	}
	if sa.ProjectID == "" || sa.ClientEmail == "" || sa.PrivateKey == "" {
		slog.Error("push: credentials missing project_id, client_email or private_key", "path", path)
		return nil
	}
	if sa.TokenURI == "" {
		sa.TokenURI = "https://oauth2.googleapis.com/token"
	}
	key, err := parseKey(sa.PrivateKey)
	if err != nil {
		slog.Error("push: bad private key", "err", err)
		return nil
	}
	slog.Info("push: enabled", "project", sa.ProjectID)
	return &Sender{sa: sa, key: key, client: &http.Client{Timeout: 15 * time.Second}}
}

func (s *Sender) Enabled() bool { return s != nil }

func parseKey(pemStr string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, errors.New("no PEM block")
	}
	// Google issues PKCS#8; accept PKCS#1 too so a hand-made key also works.
	if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		rsaKey, ok := k.(*rsa.PrivateKey)
		if !ok {
			return nil, fmt.Errorf("key is %T, want RSA", k)
		}
		return rsaKey, nil
	}
	return x509.ParsePKCS1PrivateKey(block.Bytes)
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// accessToken returns a cached OAuth token, minting a new one when it is close to expiry.
func (s *Sender) accessToken(ctx context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// One minute of slack so a token never expires mid-flight.
	if s.token != "" && time.Now().Before(s.expires.Add(-time.Minute)) {
		return s.token, nil
	}

	now := time.Now()
	header := b64([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, err := json.Marshal(map[string]any{
		"iss":   s.sa.ClientEmail,
		"scope": scope,
		"aud":   s.sa.TokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	if err != nil {
		return "", err
	}
	signingInput := header + "." + b64(claims)
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, s.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("sign jwt: %w", err)
	}
	assertion := signingInput + "." + b64(sig)

	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.sa.TokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := s.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<10))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("oauth %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.AccessToken == "" {
		return "", fmt.Errorf("oauth response has no access_token: %w", err)
	}
	s.token = out.AccessToken
	s.expires = now.Add(time.Duration(out.ExpiresIn) * time.Second)
	return s.token, nil
}

// Send delivers one message to one device token.
//
// It sends a notification block and a data block on purpose: with a notification block
// Android shows the message from the system tray even when the app process is dead, which
// is the case this whole package exists for. The data block is what the app reads when it
// happens to be running.
func (s *Sender) Send(ctx context.Context, deviceToken string, m Message) error {
	if !s.Enabled() {
		return ErrDisabled
	}
	body, err := json.Marshal(map[string]any{
		"message": map[string]any{
			"token": deviceToken,
			"notification": map[string]any{
				"title": m.Title,
				"body":  m.Body,
			},
			"data": map[string]string{
				"type":  m.Type,
				"title": m.Title,
				"body":  m.Body,
			},
			"android": map[string]any{
				// high priority wakes the device out of Doze; this is a once-a-day
				// message, not a chat, so it is not abusive.
				"priority":     "high",
				"notification": map[string]any{"channel_id": "ch_morning"},
			},
		},
	})
	if err != nil {
		return err
	}

	tok, err := s.accessToken(ctx)
	if err != nil {
		return fmt.Errorf("oauth token: %w", err)
	}

	endpoint := "https://fcm.googleapis.com/v1/projects/" + s.sa.ProjectID + "/messages:send"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")

	resp, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return nil
	}

	slurp, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
	// 404 UNREGISTERED, or 400 naming the token field, means this token is gone for good.
	if resp.StatusCode == http.StatusNotFound ||
		(resp.StatusCode == http.StatusBadRequest && bytes.Contains(slurp, []byte("token"))) {
		return ErrUnregistered
	}
	return fmt.Errorf("fcm %d: %s", resp.StatusCode, strings.TrimSpace(string(slurp)))
}
