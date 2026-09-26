package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

type HistoryItem struct {
	Date    string `json:"date"`
	Message string `json:"message"`
}

type HistoryResponse struct {
	Items []HistoryItem `json:"items"`
}

// History returns the last two weeks of morning forecasts for the panels at the bottom of Home.
func (h *Handler) History(w http.ResponseWriter, r *http.Request) {
	msgs, err := h.morning.History(r.Context(), userIDFrom(r), 14)
	if err != nil {
		slog.Error("history", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	out := HistoryResponse{Items: make([]HistoryItem, 0, len(msgs))}
	for _, m := range msgs {
		out.Items = append(out.Items, HistoryItem{Date: m.Date.Format("2006-01-02"), Message: m.Message})
	}
	writeJSON(w, http.StatusOK, out)
}

type DayReviewRequest struct {
	Date string `json:"date"` // "YYYY-MM-DD"; empty = yesterday
}

type ReviewResponse struct {
	Kind    string           `json:"kind"` // "day" | "week"
	Date    string           `json:"date"`
	Text    string           `json:"text"`
	Signals []signals.Signal `json:"signals"`
}

// DayReview explains one day on request («Разбор дня»).
func (h *Handler) DayReview(w http.ResponseWriter, r *http.Request) {
	var req DayReviewRequest
	if r.ContentLength != 0 {
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
	}
	date := clock.Today().AddDate(0, 0, -1)
	if req.Date != "" {
		d, err := time.Parse("2006-01-02", req.Date)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid date")
			return
		}
		date = d
	}
	uid := userIDFrom(r)
	h.touch(r, uid)
	rv, sig, err := h.generator.DayReview(r.Context(), uid, date)
	if h.reviewError(w, uid, err) {
		return
	}
	if sig == nil {
		sig = []signals.Signal{}
	}
	writeJSON(w, http.StatusOK, ReviewResponse{Kind: "day", Date: date.Format("2006-01-02"), Text: rv.Text, Signals: sig})
}

// WeekReview sums up the last 7 days on request («Итоги недели»).
func (h *Handler) WeekReview(w http.ResponseWriter, r *http.Request) {
	uid := userIDFrom(r)
	h.touch(r, uid)
	rv, err := h.generator.WeeklyReview(r.Context(), uid)
	if h.reviewError(w, uid, err) {
		return
	}
	writeJSON(w, http.StatusOK, ReviewResponse{Kind: "week", Date: rv.Date.Format("2006-01-02"), Text: rv.Text, Signals: []signals.Signal{}})
}

// reviewError maps generator errors to responses; true if a response was written.
func (h *Handler) reviewError(w http.ResponseWriter, uid string, err error) bool {
	switch {
	case err == nil:
		return false
	case errors.Is(err, forecast.ErrBadDate):
		writeError(w, http.StatusBadRequest, "date out of range")
	case errors.Is(err, forecast.ErrNoData):
		writeError(w, http.StatusNotFound, "not enough data")
	case errors.Is(err, forecast.ErrBudget):
		writeError(w, http.StatusTooManyRequests, "daily limit reached")
	default:
		slog.Error("review", "user", uid, "err", err)
		writeError(w, http.StatusServiceUnavailable, "review unavailable")
	}
	return true
}
