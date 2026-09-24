package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
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

		// Public, aggregate-only numbers for web/metrics.html (no user header, CORS enabled).
		r.Method(http.MethodGet, "/metrics", NewMetrics(d.DB, d.DevUserIDs))

		r.Group(func(r chi.Router) {
			r.Use(UserMiddleware(users))
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
