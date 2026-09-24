package forecast

import "testing"

func TestSafeOutput(t *testing.T) {
	ok := []string{
		"Сон 6ч22м — на 48 минут меньше обычного. Начни с простой задачи.",
		"Вчера 7240 шагов и 8 часов сна. Удержи ритм и сегодня.",
		"Лёг в 00:15, поспал 6ч. Сегодня не ставь больше одной большой задачи.",
		"Instagram 1ч 12м, YouTube 57м — больше обычного. Попробуй выйти на 15 минут.",
		"Три дня недосыпа, т.е. тело устало. Сегодня лёгкий день.",
	}
	bad := []string{
		"Подробнее на https://example.com",
		"Заходи на www.example.org",
		"Пиши в t.me/somebot",
		"Звони +7 (999) 123-45-67",
		"Подпишись на @promo_channel",
		"Смотри example.ru",
	}
	for _, s := range ok {
		if !SafeOutput(s) {
			t.Errorf("rejected a normal forecast: %q", s)
		}
	}
	for _, s := range bad {
		if SafeOutput(s) {
			t.Errorf("let through: %q", s)
		}
	}
}
