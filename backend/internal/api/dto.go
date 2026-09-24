package api

import "time"

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

type WeeklyFeedbackRequest struct {
	WeekStart string `json:"week_start"` // "2006-01-02"
	Feedback  string `json:"feedback"`   // "yes" | "no" | "partially"
}

// --- responses ---

type MorningMessageResponse struct {
	Date    string `json:"date"`
	Message string `json:"message"`
}

type ErrorResponse struct {
	Error string `json:"error"`
}

// --- helpers ---

func parseDate(s string) (time.Time, error) {
	return time.Parse("2006-01-02", s)
}
