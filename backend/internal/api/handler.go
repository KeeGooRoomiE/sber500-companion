package api

import (
	"encoding/json"
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
)

type Handler struct {
	db      *pgxpool.Pool
	callLog *analytics.Logger
}

func (h *Handler) Checkin(w http.ResponseWriter, r *http.Request) {
	// TODO: implement
	w.WriteHeader(http.StatusAccepted)
}

func (h *Handler) PassiveData(w http.ResponseWriter, r *http.Request) {
	// TODO: implement
	w.WriteHeader(http.StatusAccepted)
}

func (h *Handler) MorningMessage(w http.ResponseWriter, r *http.Request) {
	// TODO: implement
	json.NewEncoder(w).Encode(map[string]string{"message": "stub"})
}

func (h *Handler) WeeklyFeedback(w http.ResponseWriter, r *http.Request) {
	// TODO: implement
	w.WriteHeader(http.StatusAccepted)
}
