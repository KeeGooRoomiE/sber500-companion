package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/explore"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type ExploreAnswerDTO struct {
	ID       string   `json:"id"`
	Question string   `json:"question"`
	Text     string   `json:"text"`
	Facts    []string `json:"facts"` // shown under the answer as «На чём основано»
}

type ExploreResponse struct {
	Answered []ExploreAnswerDTO `json:"answered"` // today's answers, oldest first
	Next     []explore.Question `json:"next"`     // what can be asked now
	Left     int                `json:"left"`     // answers left today
	Answer   *ExploreAnswerDTO  `json:"answer,omitempty"`
}

type ExploreRequest struct {
	ID string `json:"id"`
}

// ExploreState: «Хочу ещё» — today's answers and the questions this person's data can answer.
func (h *Handler) ExploreState(w http.ResponseWriter, r *http.Request) {
	uid := userIDFrom(r)
	st, err := h.generator.ExploreToday(r.Context(), uid)
	if err != nil {
		slog.Error("explore state", "user", uid, "err", err)
		writeError(w, http.StatusInternalServerError, "db error")
		return
	}
	writeJSON(w, http.StatusOK, exploreResponse(st, nil))
}

// ExploreAsk answers one question (one LLM call, cached for the day).
func (h *Handler) ExploreAsk(w http.ResponseWriter, r *http.Request) {
	var req ExploreRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" || len(req.ID) > 40 {
		writeError(w, http.StatusBadRequest, "need question id")
		return
	}
	uid := userIDFrom(r)
	h.touch(r, uid)
	a, st, err := h.generator.ExploreAnswer(r.Context(), uid, req.ID)
	switch {
	case errors.Is(err, forecast.ErrUnknownQuestion):
		writeError(w, http.StatusNotFound, "question not available")
	case errors.Is(err, forecast.ErrExploreLimit), errors.Is(err, forecast.ErrBudget):
		writeError(w, http.StatusTooManyRequests, "daily limit reached")
	case err != nil:
		slog.Error("explore ask", "user", uid, "err", err)
		writeError(w, http.StatusServiceUnavailable, "answer unavailable")
	default:
		dto := answerDTO(*a)
		writeJSON(w, http.StatusOK, exploreResponse(st, &dto))
	}
}

func exploreResponse(st *forecast.ExploreState, answer *ExploreAnswerDTO) ExploreResponse {
	out := ExploreResponse{Answered: []ExploreAnswerDTO{}, Next: st.Next, Left: st.Left, Answer: answer}
	if out.Next == nil {
		out.Next = []explore.Question{}
	}
	for _, a := range st.Answered {
		out.Answered = append(out.Answered, answerDTO(a))
	}
	return out
}

func answerDTO(a repo.ExploreAnswer) ExploreAnswerDTO {
	facts := a.Facts
	if facts == nil {
		facts = []string{}
	}
	return ExploreAnswerDTO{ID: a.QuestionID, Question: a.Question, Text: a.Text, Facts: facts}
}
