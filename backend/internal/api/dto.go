package api

import (
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

// --- requests ---

type PassiveDataRequest struct {
	Date           string        `json:"date"` // "2006-01-02"
	SleepMin       *int          `json:"sleep_min"`
	Bedtime        *string       `json:"bedtime"` // "23:30"
	Wakeup         *string       `json:"wakeup"`  // "07:00"
	Steps          *int          `json:"steps"`
	ScreenMin      *int          `json:"screen_min"`
	Unlocks        *int          `json:"unlocks"`
	FirstUnlock    *string       `json:"first_unlock"`
	LastUnlock     *string       `json:"last_unlock"`
	TopApps        []AppUsageDTO `json:"top_apps"`
	BatteryMorning *int          `json:"battery_morning"`
	FCMToken       *string       `json:"fcm_token"`
	HourlyUnlocks  []int         `json:"hourly_unlocks"` // 24 values, local hours
	HourlyScreen   []int         `json:"hourly_screen"`  // minutes per local hour
	HourlySteps    []int         `json:"hourly_steps"`   // Health Connect steps per local hour
}

type AppUsageDTO struct {
	Package     string `json:"package"`
	PackageName string `json:"package_name"` // sent by app builds before 0.4.3
	Minutes     int    `json:"minutes"`
}

// PackageID returns the package name from either the current or the legacy key.
func (a AppUsageDTO) PackageID() string {
	if a.Package != "" {
		return a.Package
	}
	return a.PackageName
}

type CheckInRequest struct {
	Date     string   `json:"date"`     // "2006-01-02"
	DayFeel  string   `json:"day_feel"` // "ok" | "meh" | "hard"
	Tags     []string `json:"tags"`
	NoteText *string  `json:"note_text"`
}

type FeedbackRequest struct {
	Kind    string `json:"kind"`    // "morning" | "day" | "week"
	Date    string `json:"date"`    // "2006-01-02" — the date of the rated text
	Verdict string `json:"verdict"` // "hit" (Совпало) | "miss" (Не совсем)
}

// --- responses ---

type MorningMessageResponse struct {
	Date     string           `json:"date"`
	Message  string           `json:"message"`
	Signals  []signals.Signal `json:"signals"`  // evidence for «Почему такой прогноз»
	Feedback string           `json:"feedback"` // "" | "hit" | "miss"
}

type ErrorResponse struct {
	Error string `json:"error"`
}

// --- helpers ---

func parseDate(s string) (time.Time, error) {
	return time.Parse("2006-01-02", s)
}
