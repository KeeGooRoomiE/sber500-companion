package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/push"
)

// AppConfig is the remote config the app reads at start and applies as plain values (plan 1.1.0,
// items 7–8). Values only, never behaviour: the app uses what it already knows how to do.
//
// Every field has a default in defaultConfig, and an operator file overrides only the keys it
// names, so an empty or absent file changes nothing and a typo in one key can't blank the rest.
type AppConfig struct {
	Version int `json:"version"` // schema version, bumped only when a key changes meaning

	Midday  MiddayConfig  `json:"midday"`
	Evening EveningConfig `json:"evening"`
	Silence SilenceConfig `json:"silence"`
	Update  UpdateConfig  `json:"update"`

	// Flags switch on mechanics that already exist in the shipped build.
	Flags map[string]bool `json:"flags"`
	// Texts are fallbacks the app shows when the server text is unavailable, by key.
	Texts map[string][]string `json:"texts"`
}

type MiddayConfig struct {
	Enabled   bool `json:"enabled"`
	CenterMin int  `json:"center_min"` // minutes after local midnight, 840 = 14:00
	DeltaMin  int  `json:"delta_min"`  // ± window around the center
}

type EveningConfig struct {
	// DefaultMin is the evening check-in time (minutes after local midnight) for people who never
	// chose one in the profile; 0 keeps the app's built-in default. A chosen time always wins.
	DefaultMin int `json:"default_min"`
}

type SilenceConfig struct {
	// Days without any activity mark after which a person counts as silent (reactivation, weekly).
	Days int `json:"days"`
}

type UpdateConfig struct {
	// Latest, when set, is THE version offered to old apps: set it by hand on the day the store
	// actually publishes, so «Меня можно обновить» never leads to a build that isn't there yet.
	Latest      string `json:"latest"`
	DownloadURL string `json:"download_url"`
}

func defaultConfig() AppConfig {
	return AppConfig{
		Version: 1,
		Midday:  MiddayConfig{Enabled: true, CenterMin: 14 * 60, DeltaMin: 60},
		Silence: SilenceConfig{Days: 3},
		Flags:   map[string]bool{},
		Texts:   map[string][]string{},
	}
}

func configFile() string {
	if p := os.Getenv("APP_CONFIG_FILE"); p != "" {
		return p
	}
	return "/opt/companion/app_config.json"
}

// sanitize keeps the numbers inside what the app can schedule, whatever the file says.
func (c *AppConfig) sanitize() {
	d := defaultConfig()
	if c.Midday.CenterMin < 0 || c.Midday.CenterMin >= 24*60 {
		c.Midday.CenterMin = d.Midday.CenterMin
	}
	if c.Midday.DeltaMin < 0 || c.Midday.DeltaMin > 6*60 {
		c.Midday.DeltaMin = d.Midday.DeltaMin
	}
	if c.Evening.DefaultMin < 0 || c.Evening.DefaultMin >= 24*60 {
		c.Evening.DefaultMin = 0
	}
	if c.Silence.Days < 1 || c.Silence.Days > 60 {
		c.Silence.Days = d.Silence.Days
	}
	c.Update.Latest = strings.TrimPrefix(strings.TrimSpace(c.Update.Latest), "v")
	if c.Flags == nil {
		c.Flags = map[string]bool{}
	}
	if c.Texts == nil {
		c.Texts = map[string][]string{}
	}
}

// loadConfig overlays the operator file on the defaults. json.Unmarshal into a pre-filled struct
// touches only the keys present in the file.
func loadConfig() AppConfig {
	c := defaultConfig()
	b, err := os.ReadFile(configFile())
	if err != nil {
		if !os.IsNotExist(err) {
			slog.Warn("config: read failed", "err", err)
		}
		c.sanitize()
		return c
	}
	if err := json.Unmarshal(b, &c); err != nil {
		slog.Warn("config: bad json, using defaults", "err", err)
		c = defaultConfig()
	}
	c.Version = defaultConfig().Version
	c.sanitize()
	return c
}

type configCache struct {
	mu     sync.Mutex
	cfg    AppConfig
	loaded time.Time
}

var cachedConfig = &configCache{}

// currentConfig re-reads the file at most once a minute: an edit goes live without a restart.
func currentConfig() AppConfig {
	c := cachedConfig
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.loaded.IsZero() || time.Since(c.loaded) > time.Minute {
		c.cfg = loadConfig()
		c.loaded = time.Now()
	}
	return c.cfg
}

// overrideLatest returns the hand-set version from the config file, or "".
func overrideLatest() string { return currentConfig().Update.Latest }

// Config serves the remote config. Public like /version: no user data, nothing personal in it.
func Config(w http.ResponseWriter, r *http.Request) {
	c := currentConfig()
	if c.Update.DownloadURL == "" {
		c.Update.DownloadURL = strings.TrimSpace(os.Getenv("APP_DOWNLOAD_URL"))
	}
	if c.Update.DownloadURL == "" {
		c.Update.DownloadURL = DefaultDownloadURL
	}
	if c.Update.Latest == "" {
		c.Update.Latest = latestVersion(r.Context())
	}
	w.Header().Set("Cache-Control", "public, max-age=300")
	writeJSON(w, http.StatusOK, c)
}

// flagOr reads a switch that is on or off by default when the file does not name it.
func (c AppConfig) flagOr(name string, def bool) bool {
	if v, ok := c.Flags[name]; ok {
		return v
	}
	return def
}

// PushCampaignSettings is what the automatic notifications run on, read from the same config
// the app gets: silence.days, flags push_reactivation / push_weekly (on unless switched off),
// texts push_reactivation / push_weekly («Заголовок | текст»).
func PushCampaignSettings() push.Settings {
	c := currentConfig()
	return push.Settings{
		SilenceDays:       c.Silence.Days,
		ReactivationOn:    c.flagOr("push_reactivation", true),
		WeeklyOn:          c.flagOr("push_weekly", true),
		ReactivationTexts: c.Texts["push_reactivation"],
		WeeklyTexts:       c.Texts["push_weekly"],
	}
}
