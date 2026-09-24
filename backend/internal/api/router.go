package api

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/httprate"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

// maxBody caps request bodies: a daily snapshot is ~2 KB, 64 KB is plenty.
const maxBody = 64 << 10

type Deps struct {
	DB         *pgxpool.Pool
	CallLog    *analytics.Logger
	Generator  *forecast.Generator
	DevUserIDs []string
}

func Mount(r chi.Router, d Deps) {
	users := repo.NewUserRepo(d.DB)
	h := &Handler{
		users:     users,
		daily:     repo.NewDailyRepo(d.DB),
		checkin:   repo.NewCheckInRepo(d.DB),
		morning:   repo.NewMorningRepo(d.DB),
		activity:  repo.NewActivityRepo(d.DB),
		generator: d.Generator,
		callLog:   d.CallLog,
	}

	r.Route("/api/v1", func(r chi.Router) {
		r.Use(limitBody)
		// Coarse per-IP ceiling for everything (behind Caddy RealIP is the client address).
		r.Use(httprate.Limit(600, time.Minute, httprate.WithKeyFuncs(httprate.KeyByRealIP), httprate.WithLimitHandler(rateLimited)))

		// Public, aggregate-only numbers for web/metrics.html (no auth, CORS enabled).
		r.Method(http.MethodGet, "/metrics", NewMetrics(d.DB, d.DevUserIDs))

		// New device identity. Tight per-IP limit: one phone registers once, bots get throttled.
		r.With(httprate.Limit(10, time.Hour, httprate.WithKeyFuncs(httprate.KeyByRealIP), httprate.WithLimitHandler(rateLimited))).
			Post("/register", h.Register)

		r.Group(func(r chi.Router) {
			r.Use(AuthMiddleware(users))
			// Per-user limit: the app makes a handful of calls a day.
			r.Use(httprate.Limit(60, time.Minute, httprate.WithKeyFuncs(func(r *http.Request) (string, error) {
				return userIDFrom(r), nil
			}), httprate.WithLimitHandler(rateLimited)))
			r.Post("/data/passive", h.PassiveData)
			r.Post("/checkin", h.Checkin)
			r.Get("/morning", h.MorningMessage)
			r.Post("/ping", h.Ping)
			r.Post("/feedback/weekly", h.WeeklyFeedback)
		})
	})
}

func limitBody(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, maxBody)
		next.ServeHTTP(w, r)
	})
}
