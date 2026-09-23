package api

import (
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
)

func Mount(r chi.Router, db *pgxpool.Pool, callLog *analytics.Logger) {
	h := &Handler{db: db, callLog: callLog}

	r.Route("/api/v1", func(r chi.Router) {
		r.Post("/checkin", h.Checkin)
		r.Post("/data/passive", h.PassiveData)
		r.Get("/morning/{userID}", h.MorningMessage)
		r.Post("/feedback/weekly", h.WeeklyFeedback)
	})
}
