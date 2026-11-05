package llm

import (
	"strings"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/signals"
)

func markerDay(d int, screen, unlocks int) *repo.DailyData {
	s, u := screen, unlocks
	return &repo.DailyData{
		Date:      time.Date(2026, 11, d, 0, 0, 0, 0, time.UTC),
		ScreenMin: &s,
		Unlocks:   &u,
	}
}

// Each of these was a sentence the model produced out of thin air in the persona run.
// The prompt rules forbidding them are only enforceable if the data states the fact.
func TestDayReviewStatesWhatTheRulesNeed(t *testing.T) {
	sig := []signals.Signal{{Key: "busy_work", Title: "Насыщенный рабочий день", Detail: "47 разблокировок с 9 до 18 — обычно 27"}}

	t.Run("нет отметки — сказано прямо", func(t *testing.T) {
		got := BuildDayReview(DayReviewInput{
			Day:     markerDay(4, 205, 84),
			Usual:   []*repo.DailyData{markerDay(1, 189, 51), markerDay(2, 190, 52), markerDay(3, 188, 50)},
			CheckIn: nil,
			Signals: sig,
		})
		if !strings.Contains(got, "Вечерней отметки за этот день нет") {
			t.Error("не сказано, что отметки нет — модель пишет «ты отметил»")
		}
		if strings.Contains(got, "Вечерняя отметка:") {
			t.Error("заявлена отметка, которой нет")
		}
	})

	t.Run("заголовки сигналов помечены как наши", func(t *testing.T) {
		got := BuildDayReview(DayReviewInput{Day: markerDay(4, 205, 84), Signals: sig})
		if !strings.Contains(got, "наши вычисления, а не слова человека") {
			t.Error("заголовок сигнала снова можно принять за слова человека")
		}
	})

	t.Run("короткая история названа числом и запретом", func(t *testing.T) {
		got := BuildDayReview(DayReviewInput{
			Day:   markerDay(4, 240, 66),
			Usual: []*repo.DailyData{markerDay(3, 190, 50)}, // всего два дня
		})
		if !strings.Contains(got, "История по этому человеку: 2 дн.") {
			t.Error("длина истории не названа — правило «не говори обычно» неисполнимо")
		}
		if !strings.Contains(got, "слово «обычно» не употребляй") {
			t.Error("нет запрета при короткой истории")
		}
	})

	t.Run("длинная история запрета не несёт", func(t *testing.T) {
		usual := []*repo.DailyData{}
		for d := 1; d <= 9; d++ {
			usual = append(usual, markerDay(d, 190, 50))
		}
		got := BuildDayReview(DayReviewInput{Day: markerDay(10, 205, 60), Usual: usual})
		if !strings.Contains(got, "История по этому человеку: 10 дн.") {
			t.Error("длина истории посчитана неверно")
		}
		if strings.Contains(got, "слово «обычно» не употребляй") {
			t.Error("запрет на «обычно» выставлен там, где норма уже есть")
		}
	})
}
