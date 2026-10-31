package llm

import (
	"strings"
	"testing"
	"time"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

func day(date string, screen, sleep int) *repo.DailyData {
	t, _ := time.Parse("2006-01-02", date)
	return &repo.DailyData{Date: t, ScreenMin: &screen, SleepMin: &sleep}
}

func TestDayReviewListsPreviousDays(t *testing.T) {
	usual := []*repo.DailyData{day("2026-10-26", 200, 420), day("2026-10-27", 240, 400), day("2026-10-28", 180, 450)}
	out := BuildDayReview(DayReviewInput{Day: day("2026-10-29", 300, 360), Usual: usual})

	i26, i27, i28, today := strings.Index(out, "26.10"), strings.Index(out, "27.10"), strings.Index(out, "28.10"), strings.Index(out, "Этот день (29.10)")
	if i26 < 0 || !(i26 < i27 && i27 < i28 && i28 < today) {
		t.Fatalf("previous days missing or out of order:\n%s", out)
	}
	if !strings.Contains(out, "Предыдущие дни:") {
		t.Fatalf("no header:\n%s", out)
	}
}

func TestPreviousDaysCapAndEmptyDays(t *testing.T) {
	var days []*repo.DailyData
	for d := 1; d <= 12; d++ {
		days = append(days, day(time.Date(2026, 10, d, 0, 0, 0, 0, time.UTC).Format("2006-01-02"), 100+d, 400))
	}
	days = append(days, &repo.DailyData{Date: time.Date(2026, 10, 13, 0, 0, 0, 0, time.UTC)}) // no data at all
	got := previousDays(days, nil)
	if n := strings.Count(got, "\n— "); n != maxPreviousDays-1 && strings.Count(got, "— ") != maxPreviousDays {
		t.Fatalf("want %d day lines:\n%s", maxPreviousDays, got)
	}
	if strings.Contains(got, "данных нет") || strings.Contains(got, "13.10") {
		t.Fatalf("empty day must be skipped:\n%s", got)
	}
	if !strings.Contains(got, "12.10") || strings.Contains(got, "05.10") {
		t.Fatalf("must keep the latest days:\n%s", got)
	}
	if previousDays(nil, nil) != "" {
		t.Fatal("no days, no block")
	}
}
