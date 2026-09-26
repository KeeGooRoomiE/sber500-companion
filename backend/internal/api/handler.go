package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

type Handler struct {
	users     *repo.UserRepo
	daily     *repo.DailyRepo
	checkin   *repo.CheckInRepo
	morning   *repo.MorningRepo
	activity  *repo.ActivityRepo
	feedback  *repo.FeedbackRepo
	generator *forecast.Generator
	callLog   *analytics.Logger
}

// touch marks the user active today (DAU / retention). Failures only get logged.
func (h *Handler) touch(r *http.Request, uid string) {
	if err := h.activity.Touch(r.Context(), uid, clock.Today()); err != nil {
		slog.Warn("activity touch failed", "err", err)
	}
}

// Ping is sent by the app when it comes to the foreground — the "user opened the app" signal.
func (h *Handler) Ping(w http.ResponseWriter, r *http.Request) {
	h.touch(r, userIDFrom(r))
	w.WriteHeader(http.StatusNoContent)
}

// PassiveData accepts daily snapshot from Android.
func (h *Handler) PassiveData(w http.ResponseWriter, r *http.Request) {
	var req PassiveDataRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}

	date, err := parseDate(req.Date)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid date, use YYYY-MM-DD")
		return
	}

	uid := userIDFrom(r)

	// Update FCM token if provided.
	if req.FCMToken != nil {
		if err := h.users.Upsert(r.Context(), uid, req.FCMToken); err != nil {
			slog.Error("upsert fcm token", "err", err)
		}
	}

	apps := make([]repo.AppUsage, 0, len(req.TopApps))
	for _, a := range req.TopApps {
		if pkg := a.PackageID(); pkg != "" {
			apps = append(apps, repo.AppUsage{Package: pkg, Minutes: a.Minutes})
		}
	}

	if err := h.daily.Upsert(r.Context(), &repo.DailyData{
		UserID:         uid,
		Date:           date,
		SleepMin:       req.SleepMin,
		Bedtime:        req.Bedtime,
		Wakeup:         req.Wakeup,
		Steps:          req.Steps,
		ScreenMin:      req.ScreenMin,
		Unlocks:        req.Unlocks,
		FirstUnlock:    req.FirstUnlock,
		LastUnlock:     req.LastUnlock,
		TopApps:        apps,
		BatteryMorning: req.BatteryMorning,
		HourlyUnlocks:  hours24(req.HourlyUnlocks),
		HourlyScreen:   hours24(req.HourlyScreen),
	}); err != nil {
		slog.Error("daily upsert", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}

	h.callLog.Log(r.Context(), analytics.CallEvent{
		UserID:      uid,
		Timestamp:   time.Now(),
		CallType:    analytics.CallTypeTool,
		Component:   analytics.ComponentUsageStats,
		Trigger:     analytics.TriggerScheduled,
		UserVisible: false,
		Result:      "ok",
	})

	w.WriteHeader(http.StatusNoContent)
}

// Checkin saves evening mood check-in.
func (h *Handler) Checkin(w http.ResponseWriter, r *http.Request) {
	var req CheckInRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}

	date, err := parseDate(req.Date)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid date")
		return
	}

	valid := map[string]bool{"ok": true, "meh": true, "hard": true}
	if !valid[req.DayFeel] {
		writeError(w, http.StatusBadRequest, "day_feel must be ok|meh|hard")
		return
	}

	uid := userIDFrom(r)
	h.touch(r, uid)

	if err := h.checkin.Upsert(r.Context(), &repo.CheckIn{
		UserID:   uid,
		Date:     date,
		DayFeel:  req.DayFeel,
		Tags:     req.Tags,
		NoteText: req.NoteText,
	}); err != nil {
		slog.Error("checkin upsert", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}

	h.callLog.Log(r.Context(), analytics.CallEvent{
		UserID:      uid,
		Timestamp:   time.Now(),
		CallType:    analytics.CallTypeTool,
		Component:   analytics.ComponentCheckin,
		Trigger:     analytics.TriggerUserAction,
		UserVisible: true,
		Result:      "ok",
	})

	// Scenario = morning forecast received + evening check-in submitted on the same day.
	// Log once per day; if morning was never fetched, SentAt is nil and we skip.
	if msg, err := h.morning.ForDate(r.Context(), uid, date); err == nil && msg != nil && msg.SentAt != nil {
		h.callLog.Log(r.Context(), analytics.CallEvent{
			UserID:      uid,
			Timestamp:   time.Now(),
			CallType:    analytics.CallTypeTool,
			Component:   analytics.ComponentScenarioCompleted,
			Trigger:     analytics.TriggerUserAction,
			UserVisible: false,
			Result:      "ok",
		})
	}

	w.WriteHeader(http.StatusNoContent)
}

// MorningMessage returns today's message: pre-generated at night, or generated now if the
// night run missed this user. 404 when there's no data yet or the daily LLM cap is reached.
func (h *Handler) MorningMessage(w http.ResponseWriter, r *http.Request) {
	uid := userIDFrom(r)
	today := clock.Today()
	h.touch(r, uid)

	start := time.Now()
	msg, err := h.generator.Ensure(r.Context(), uid, today, analytics.TriggerUserAction)
	notReady := errors.Is(err, forecast.ErrNoData) || errors.Is(err, forecast.ErrBudget) || (err == nil && (msg == nil || msg.Message == ""))

	result := "ok"
	switch {
	case notReady:
		result = "empty"
	case err != nil:
		result = "error"
	}
	h.callLog.Log(r.Context(), analytics.CallEvent{
		UserID:      uid,
		Timestamp:   start,
		CallType:    analytics.CallTypeTool,
		Component:   analytics.ComponentMorningAPI,
		Trigger:     analytics.TriggerUserAction,
		UserVisible: true,
		Result:      result,
		LatencyMs:   time.Since(start).Milliseconds(),
	})

	if notReady {
		writeError(w, http.StatusNotFound, "not ready yet")
		return
	}
	if err != nil {
		slog.Error("morning ensure", "user", uid, "err", err)
		writeError(w, http.StatusServiceUnavailable, "forecast unavailable")
		return
	}

	if err := h.morning.MarkSent(r.Context(), uid, today); err != nil {
		slog.Warn("mark sent failed", "err", err)
	}

	sig, err := h.generator.Explain(r.Context(), uid, today)
	if err != nil {
		slog.Warn("morning explain", "err", err)
	}
	if sig == nil {
		sig = []signals.Signal{}
	}
	verdict, err := h.feedback.Get(r.Context(), uid, "morning", today)
	if err != nil {
		slog.Warn("morning feedback", "err", err)
	}
	writeJSON(w, http.StatusOK, MorningMessageResponse{
		Date:     msg.Date.Format("2006-01-02"),
		Message:  msg.Message,
		Signals:  sig,
		Feedback: verdict,
	})
}

// Feedback stores «Совпало» / «Не совсем» for the morning forecast or a day / week review.
// Only recent texts can be rated (forecasts: today and the two days before; reviews: 30 days).
func (h *Handler) Feedback(w http.ResponseWriter, r *http.Request) {
	var req FeedbackRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	date, err := parseDate(req.Date)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid date")
		return
	}
	today := clock.Today()
	back := 30
	if req.Kind == "morning" {
		back = 2
	}
	kindOK := req.Kind == "morning" || req.Kind == "day" || req.Kind == "week"
	if !kindOK || (req.Verdict != "hit" && req.Verdict != "miss") ||
		date.After(today) || date.Before(today.AddDate(0, 0, -back)) {
		writeError(w, http.StatusBadRequest, "bad kind, verdict or date")
		return
	}
	uid := userIDFrom(r)
	h.touch(r, uid)
	err = h.feedback.Set(r.Context(), uid, req.Kind, date, req.Verdict)
	switch {
	case errors.Is(err, repo.ErrNothingToRate):
		writeError(w, http.StatusNotFound, "nothing to rate")
	case err != nil:
		slog.Error("feedback", "user", uid, "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// --- helpers ---

// hours24 keeps an hourly series only if it is exactly 24 non-negative values.
func hours24(v []int) []int {
	if len(v) != 24 {
		return nil
	}
	for _, x := range v {
		if x < 0 || x > 100000 {
			return nil
		}
	}
	return v
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, ErrorResponse{Error: msg})
}
