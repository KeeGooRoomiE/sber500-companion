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
		// Coarse per-IP ceiling (100 rps). Generous on purpose: Russian mobile carriers put many
		// subscribers behind one CGNAT address, and a stress test runs from a single machine.
		// LLM spend is capped elsewhere (one message per user per day + LLM_DAILY_CAP).
		r.Use(httprate.Limit(6000, time.Minute, httprate.WithKeyFuncs(httprate.KeyByRealIP), httprate.WithLimitHandler(rateLimited)))

		// Public, aggregate-only numbers for web/metrics.html (no auth, CORS enabled).
		metricsH := NewMetrics(d.DB, d.DevUserIDs)
		r.Method(http.MethodGet, "/metrics", metricsH)
		r.Method(http.MethodOptions, "/metrics", metricsH)

		// New device identity: a phone registers once; 60/h per IP leaves room for CGNAT, throttles bots.
		r.With(httprate.Limit(60, time.Hour, httprate.WithKeyFuncs(httprate.KeyByRealIP), httprate.WithLimitHandler(rateLimited))).
			Post("/register", h.Register)

		r.Group(func(r chi.Router) {
			r.Use(AuthMiddleware(users))
			// Per-user limit: the app makes a handful of calls a day; 300/min only stops runaway clients.
			r.Use(httprate.Limit(300, time.Minute, httprate.WithKeyFuncs(func(r *http.Request) (string, error) {
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
