package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type Handler struct {
	users   *repo.UserRepo
	daily   *repo.DailyRepo
	checkin *repo.CheckInRepo
	morning *repo.MorningRepo
	callLog *analytics.Logger
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
		Component:   analytics.ComponentUsageStats,
		Trigger:     analytics.TriggerUserAction,
		UserVisible: true,
		Result:      "ok",
	})

	w.WriteHeader(http.StatusNoContent)
}

// MorningMessage returns today's pre-generated message. Returns 404 if not ready yet.
func (h *Handler) MorningMessage(w http.ResponseWriter, r *http.Request) {
	uid := userIDFrom(r)
	today := time.Now().Truncate(24 * time.Hour)

	msg, err := h.morning.ForDate(r.Context(), uid, today)
	if err != nil {
		slog.Error("morning fetch", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	if msg == nil {
		writeError(w, http.StatusNotFound, "not ready yet")
		return
	}

	if err := h.morning.MarkSent(r.Context(), uid, today); err != nil {
		slog.Warn("mark sent failed", "err", err)
	}

	writeJSON(w, http.StatusOK, MorningMessageResponse{
		Date:    msg.Date.Format("2006-01-02"),
		Message: msg.Message,
	})
}

// WeeklyFeedback stores user reaction to the weekly summary.
func (h *Handler) WeeklyFeedback(w http.ResponseWriter, r *http.Request) {
	var req WeeklyFeedbackRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}

	// TODO: persist to weekly_feedback table
	_ = userIDFrom(r)
	w.WriteHeader(http.StatusNoContent)
}

// --- helpers ---

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, ErrorResponse{Error: msg})
}
