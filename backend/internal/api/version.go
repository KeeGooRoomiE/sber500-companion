package api

import (
	"net/http"
	"os"
	"strings"
)

// LatestAppVersion is the newest released APK. Bump it together with a release tag —
// a push to backend/ deploys it. APP_LATEST_VERSION in .env overrides it without a deploy.
const LatestAppVersion = "0.6.0"

// DefaultDownloadURL is the landing's download section (it links the latest release APK).
const DefaultDownloadURL = "https://keegooroomie.github.io/sber500-companion/#download"

type VersionResponse struct {
	Latest      string `json:"latest"`       // e.g. "0.6.0"
	DownloadURL string `json:"download_url"` // where «Обновить» leads
}

// Version tells the app whether a newer build exists. Public: no user data, no auth.
func Version(w http.ResponseWriter, r *http.Request) {
	latest := strings.TrimSpace(os.Getenv("APP_LATEST_VERSION"))
	if latest == "" {
		latest = LatestAppVersion
	}
	url := strings.TrimSpace(os.Getenv("APP_DOWNLOAD_URL"))
	if url == "" {
		url = DefaultDownloadURL
	}
	w.Header().Set("Cache-Control", "public, max-age=300")
	writeJSON(w, http.StatusOK, VersionResponse{Latest: latest, DownloadURL: url})
}
