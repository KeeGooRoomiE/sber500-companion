package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
)

// maxPushToken bounds what we store: RuStore tokens are a few dozen characters.
const maxPushToken = 512

// PutPushToken records the phone's push token. The app calls it whenever its push client gives
// out a token (first start, rotation) — idempotent, so a repeat is harmless. A phone that never
// calls it simply cannot be reached by a server push.
func (h *Handler) PutPushToken(w http.ResponseWriter, r *http.Request) {
	var req PushTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	token := strings.TrimSpace(req.Token)
	if len(token) < 8 || len(token) > maxPushToken || strings.ContainsAny(token, " \t\r\n") {
		writeError(w, http.StatusBadRequest, "invalid token")
		return
	}
	provider := strings.TrimSpace(req.Provider)
	if provider == "" {
		provider = "rustore"
	}
	if provider != "rustore" {
		writeError(w, http.StatusBadRequest, "unknown provider")
		return
	}
	uid := userIDFrom(r)
	if err := h.users.SetPushToken(r.Context(), uid, provider, token); err != nil {
		slog.Error("set push token", "user", uid, "err", err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
