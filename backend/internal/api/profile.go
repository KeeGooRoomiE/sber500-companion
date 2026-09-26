package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
)

// profileLimits lists the answers the server accepts and their max length.
// The name is deliberately absent: it stays on the phone and never reaches the LLM.
var profileLimits = map[string]int{
	"work_place":   100,
	"bedtime":      100,
	"wake":         100,
	"wearable":     100,
	"tone":         100,
	"goal":         300,
	"triggers":     300,
	"work_apps":    1500, // comma-separated package names
	"morning_time": 10,
	"evening_time": 10,
}

type ProfileRequest struct {
	Answers map[string]string `json:"answers"`
}

// GetProfile returns the stored answers — a reinstalled app restores «Расскажи о себе» from here.
func (h *Handler) GetProfile(w http.ResponseWriter, r *http.Request) {
	p, err := h.users.Profile(r.Context(), userIDFrom(r))
	if err != nil {
		slog.Error("profile read", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	if p == nil {
		p = map[string]string{}
	}
	writeJSON(w, http.StatusOK, ProfileRequest{Answers: p})
}

// PutProfile stores the answers used as context for the forecast. Unknown keys are dropped.
func (h *Handler) PutProfile(w http.ResponseWriter, r *http.Request) {
	var req ProfileRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	clean := map[string]string{}
	for k, v := range req.Answers {
		limit, ok := profileLimits[k]
		v = strings.TrimSpace(v)
		if !ok || v == "" {
			continue
		}
		if r := []rune(v); len(r) > limit {
			v = string(r[:limit])
		}
		clean[k] = v
	}
	if err := h.users.SetProfile(r.Context(), userIDFrom(r), clean); err != nil {
		slog.Error("profile save", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
