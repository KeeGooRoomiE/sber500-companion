package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// FallbackAppVersion is used only while GitHub can't be reached (and APP_LATEST_VERSION is unset).
const FallbackAppVersion = "0.6.1"

// DefaultDownloadURL is the landing's download section (it links the latest release APK).
const DefaultDownloadURL = "https://keegooroomie.github.io/sber500-companion/#download"

const releasesAPI = "https://api.github.com/repos/KeeGooRoomiE/sber500-companion/releases/latest"

type VersionResponse struct {
	Latest      string `json:"latest"`       // e.g. "0.6.1"
	DownloadURL string `json:"download_url"` // where «Обновить» leads
}

// latestRelease reads the newest published GitHub release — the APK the landing links to.
// So the version the app is told about is always one that can actually be downloaded:
// tag a release and, within 10 minutes, older apps offer the update. No deploy needed.
type latestRelease struct {
	mu      sync.Mutex
	version string
	checked time.Time
	client  *http.Client
}

var releases = &latestRelease{client: &http.Client{Timeout: 5 * time.Second}}

func (l *latestRelease) get(ctx context.Context) string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if time.Since(l.checked) < 10*time.Minute {
		return l.version
	}
	l.checked = time.Now() // also on failure: don't hammer GitHub (60 req/h unauthenticated)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, releasesAPI, nil)
	if err != nil {
		return l.version
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := l.client.Do(req)
	if err != nil {
		slog.Warn("version: github unreachable", "err", err)
		return l.version
	}
	defer resp.Body.Close()
	var body struct {
		TagName string `json:"tag_name"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&body) != nil {
		slog.Warn("version: github answered", "status", resp.StatusCode)
		return l.version
	}
	if v := strings.TrimPrefix(strings.TrimSpace(body.TagName), "v"); v != "" {
		l.version = v
	}
	return l.version
}

// Version tells the app whether a newer build exists. Public: no user data, no auth.
// Order: APP_LATEST_VERSION (manual override) → latest GitHub release → FallbackAppVersion.
func Version(w http.ResponseWriter, r *http.Request) {
	latest := strings.TrimSpace(os.Getenv("APP_LATEST_VERSION"))
	if latest == "" {
		latest = releases.get(r.Context())
	}
	if latest == "" {
		latest = FallbackAppVersion
	}
	url := strings.TrimSpace(os.Getenv("APP_DOWNLOAD_URL"))
	if url == "" {
		url = DefaultDownloadURL
	}
	w.Header().Set("Cache-Control", "public, max-age=300")
	writeJSON(w, http.StatusOK, VersionResponse{Latest: latest, DownloadURL: url})
}
