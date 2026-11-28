package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func (h *Handler) Chat(w http.ResponseWriter, r *http.Request) {
	var req forecast.ChatInput
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&req); err != nil || !req.Valid() || dec.Decode(&struct{}{}) != io.EOF {
		writeError(w, http.StatusBadRequest, "invalid chat message")
		return
	}
	uid := userIDFrom(r)
	reply, err := h.generator.Chat(r.Context(), uid, req)
	if errors.Is(err, repo.ErrChatConflict) || errors.Is(err, repo.ErrChatBusy) {
		if errors.Is(err, repo.ErrChatBusy) {
			w.Header().Set("Retry-After", "2")
		}
		writeError(w, http.StatusConflict, err.Error())
		return
	}
	if h.reviewError(w, uid, err) {
		return
	}
	h.touch(r, uid)
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, reply)
}
