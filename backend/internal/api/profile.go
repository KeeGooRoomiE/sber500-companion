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
	// Added with the depth question and missed here, so the answer was silently dropped —
	// the list is an allowlist and «Unknown keys are dropped».
	"depth": 100,
}

// genAnswerPrefix marks answers to questions the model generated for this person. They cannot
// be in the allowlist above because the questions do not exist until they are asked.
const genAnswerPrefix = "gen_"

// maxGenAnswers caps how many generated answers are kept, so the profile cannot grow without
// bound and quietly inflate every prompt built from it.
const maxGenAnswers = 20

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
	gen := 0
	for k, v := range req.Answers {
		v = strings.TrimSpace(v)
		if v == "" {
			continue
		}
		limit, ok := profileLimits[k]
		if !ok {
			// Answers to questions the model generated for this person. They cannot be in the
			// allowlist because the questions do not exist until they are asked, so the key
			// shape is checked instead — and capped, or the profile would grow without bound
			// and quietly inflate every prompt built from it.
			if !strings.HasPrefix(k, genAnswerPrefix) || len(k) > 64 || gen >= maxGenAnswers {
				continue
			}
			gen++
			limit = 200
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
