package push

import (
	"testing"
	"time"
)

func TestParseMessage(t *testing.T) {
	m, ok := ParseMessage("  Привет | Загляни на минуту ")
	if !ok || m.Title != "Привет" || m.Body != "Загляни на минуту" {
		t.Fatalf("got %+v %v", m, ok)
	}
	for _, bad := range []string{"", "без разделителя", " | только текст", "только заголовок | "} {
		if _, ok := ParseMessage(bad); ok {
			t.Fatalf("%q must not parse", bad)
		}
	}
	// the body may contain the separator itself
	if m, _ := ParseMessage("А | б | в"); m.Body != "б | в" {
		t.Fatalf("body cut wrong: %q", m.Body)
	}
}

func TestPickPrefersConfiguredAndFallsBack(t *testing.T) {
	day := time.Date(2026, 11, 14, 12, 0, 0, 0, time.UTC)
	if m := pick([]string{"мусор", "Свой | текст"}, defaultReactivation, day); m.Title != "Свой" {
		t.Fatalf("configured text not used: %+v", m)
	}
	if m := pick([]string{"мусор"}, defaultWeekly, day); m != defaultWeekly[0] {
		t.Fatalf("no fallback: %+v", m)
	}
	// two built-in lines alternate from one day to the next
	a, b := pick(nil, defaultReactivation, day), pick(nil, defaultReactivation, day.AddDate(0, 0, 1))
	if a == b {
		t.Fatal("texts must rotate by day")
	}
}

func TestNextTick(t *testing.T) {
	now := time.Date(2026, 11, 14, 17, 59, 30, 0, time.UTC)
	if got := nextTick(now); !got.Equal(time.Date(2026, 11, 14, 18, 2, 0, 0, time.UTC)) {
		t.Fatalf("got %v", got)
	}
	// right after a tick the next one is an hour away, not the same minute again
	now = time.Date(2026, 11, 14, 18, 2, 0, 1, time.UTC)
	if got := nextTick(now); !got.Equal(time.Date(2026, 11, 14, 19, 2, 0, 0, time.UTC)) {
		t.Fatalf("got %v", got)
	}
}
