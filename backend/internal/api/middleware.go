package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type contextKey string

const ctxUserID contextKey = "userID"

// AuthMiddleware resolves "Authorization: Bearer <token>" (issued by POST /register)
// to a user id. 401 tells the app to register again.
func AuthMiddleware(users *repo.UserRepo) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			if !ok || token == "" {
				writeError(w, http.StatusUnauthorized, "token required")
				return
			}
			uid, found, err := users.ByToken(r.Context(), token)
			if err != nil {
				slog.Error("auth lookup", "err", err)
				writeError(w, http.StatusInternalServerError, "db error")
				return
			}
			if !found {
				writeError(w, http.StatusUnauthorized, "unknown token")
				return
			}
			ctx := context.WithValue(r.Context(), ctxUserID, uid)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func userIDFrom(r *http.Request) string {
	v, _ := r.Context().Value(ctxUserID).(string)
	return v
}

type RegisterRequest struct {
	// Hash of the app's per-device id (survives reinstall). Optional: without it the
	// phone always gets a new identity, like before.
	DeviceKey string `json:"device_key"`
}

type RegisterResponse struct {
	UserID    string `json:"user_id"`
	Token     string `json:"token"`
	Returning bool   `json:"returning"` // we know this phone: old data, forecasts and answers are back
}

// Register issues a new device identity. Rate-limited per IP in the router.
func (h *Handler) Register(w http.ResponseWriter, r *http.Request) {
	var req RegisterRequest
	if r.ContentLength != 0 {
		_ = json.NewDecoder(r.Body).Decode(&req) // old apps send an empty body
	}
	key := strings.TrimSpace(req.DeviceKey)
	if len(key) < 32 || len(key) > 128 {
		key = "" // not a hash — ignore rather than trust
	}
	uid, token, returning, err := h.users.Register(r.Context(), key)
	if err != nil {
		slog.Error("register", "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	if returning {
		slog.Info("register: returning device", "user", uid)
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusCreated, RegisterResponse{UserID: uid, Token: token, Returning: returning})
}

// rateLimited answers in the API's JSON error shape.
func rateLimited(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusTooManyRequests)
	_ = json.NewEncoder(w).Encode(ErrorResponse{Error: "too many requests"})
}
