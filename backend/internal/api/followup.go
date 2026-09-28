package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
)

type FollowupRequest struct {
	Kind       string `json:"kind"` // "morning" | "day" | "week"
	Date       string `json:"date"` // "YYYY-MM-DD"; empty = today for morning/week, yesterday for day
	QuestionID string `json:"question_id"`
}

type FollowupResponse struct {
	Kind       string `json:"kind"`
	Date       string `json:"date"`
	QuestionID string `json:"question_id"`
	Text       string `json:"text"`
}

// Followup answers one chip under an insight the person is already looking at.
//
// Questions are addressed by id from a fixed catalogue, never as free text, so the prompt
// surface stays closed — the app cannot turn this into an open chat box.
func (h *Handler) Followup(w http.ResponseWriter, r *http.Request) {
	var req FollowupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	if req.Kind != "morning" && req.Kind != "day" && req.Kind != "week" {
		writeError(w, http.StatusBadRequest, "kind must be morning, day or week")
		return
	}

	date := clock.Today()
	if req.Kind == "day" {
		date = date.AddDate(0, 0, -1)
	}
	if req.Date != "" {
		parsed, err := parseDate(req.Date)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid date, use YYYY-MM-DD")
			return
		}
		date = parsed
	}

	uid := userIDFrom(r)
	rv, err := h.generator.Followup(r.Context(), uid, req.Kind, date, req.QuestionID)
	if errors.Is(err, forecast.ErrBadQuestion) {
		writeError(w, http.StatusNotFound, "unknown question")
		return
	}
	if h.reviewError(w, uid, err) {
		return
	}
	if rv == nil {
		writeError(w, http.StatusNotFound, "not enough data")
		return
	}

	h.touch(r, uid)
	writeJSON(w, http.StatusOK, FollowupResponse{
		Kind: req.Kind, Date: date.Format("2006-01-02"),
		QuestionID: req.QuestionID, Text: rv.Text,
	})
}
