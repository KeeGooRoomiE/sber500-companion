package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
)

type InsightRequest struct {
	// midday | stat | retro | tag | question
	Kind string `json:"kind"`
	// What the kind is about: the tile name, the date, the tag. Empty for midday and question.
	Arg string `json:"arg"`
}

type InsightResponse struct {
	Kind string `json:"kind"`
	Arg  string `json:"arg"`
	Text string `json:"text"`
}

// Insight answers one slice of the person's own data — the midday read, a tile, a past
// forecast, a tag, or a question generated for them.
//
// One endpoint for all of them on purpose: they differ only in which facts go in, and five
// endpoints would have meant five places for the budget, the cache and the logging to drift.
func (h *Handler) Insight(w http.ResponseWriter, r *http.Request) {
	var req InsightRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}

	uid := userIDFrom(r)
	rv, err := h.generator.Insight(r.Context(), uid, req.Kind, req.Arg)
	if errors.Is(err, forecast.ErrBadInsight) {
		writeError(w, http.StatusNotFound, "unknown insight")
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
	writeJSON(w, http.StatusOK, InsightResponse{Kind: req.Kind, Arg: req.Arg, Text: rv.Text})
}
