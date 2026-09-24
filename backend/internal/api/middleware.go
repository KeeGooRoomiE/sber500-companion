package api

import (
	"context"
	"net/http"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type contextKey string

const ctxUserID contextKey = "userID"

// UserMiddleware extracts X-User-ID header and upserts the user into DB.
// Returns 401 if header is missing.
func UserMiddleware(users *repo.UserRepo) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			uid := r.Header.Get("X-User-ID")
			if uid == "" {
				writeError(w, http.StatusUnauthorized, "X-User-ID required")
				return
			}
			if err := users.Upsert(r.Context(), uid, nil); err != nil {
				writeError(w, http.StatusInternalServerError, "db error")
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
