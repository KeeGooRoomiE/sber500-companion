package api

import (
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func Mount(r chi.Router, db *pgxpool.Pool, callLog *analytics.Logger) {
	users := repo.NewUserRepo(db)
	h := &Handler{
		users:   users,
		daily:   repo.NewDailyRepo(db),
		checkin: repo.NewCheckInRepo(db),
		morning: repo.NewMorningRepo(db),
		callLog: callLog,
	}

	r.Route("/api/v1", func(r chi.Router) {
		r.Use(UserMiddleware(users))
		r.Post("/data/passive", h.PassiveData)
		r.Post("/checkin", h.Checkin)
		r.Get("/morning", h.MorningMessage)
		r.Post("/feedback/weekly", h.WeeklyFeedback)
	})
}
