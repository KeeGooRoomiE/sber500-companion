package api

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
)

// Runs only against a throwaway database with the migrations applied:
// TEST_DATABASE_URL=postgres://… go test ./internal/api -run DailyCompute
// It truncates users, user_activity and call_log — never point it at real data.
func TestDailyComputeOnDatabase(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.Exec(ctx, q, args...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	exec(`TRUNCATE call_log, user_activity, users CASCADE`)

	// Moscow is UTC+3: 23:30 UTC on the 2nd is already the 3rd there.
	exec(`INSERT INTO users (id, created_at, is_dev) VALUES
	        ('u1',     '2026-11-01T07:00:00Z', false),
	        ('u2',     '2026-11-02T23:30:00Z', false),
	        ('envdev', '2026-11-01T07:00:00Z', false),
	        ('dbdev',  '2026-11-01T07:00:00Z', true),
	        ('mock_a', '2026-11-01T07:00:00Z', false)`)
	exec(`INSERT INTO user_activity (user_id, date) VALUES
	        ('u1', '2026-11-02'), ('u1', '2026-11-03'), ('u2', '2026-11-03'),
	        ('dbdev', '2026-11-03'), ('envdev', '2026-11-03'), ('mock_a', '2026-11-03')`)
	call := func(user, ts, typ string) {
		exec(`INSERT INTO call_log (user_id, ts, call_type, component, trigger, user_visible, result)
		      VALUES ($1, $2::timestamptz, $3, 'x', 'user_action', true, 'ok')`, user, ts, typ)
	}
	call("u1", "2026-11-02T20:30:00Z", "llm")        // 23:30 Moscow, the 2nd
	call("u1", "2026-11-02T21:30:00Z", "llm")        // 00:30 Moscow, the 3rd
	call("u1", "2026-11-03T09:00:00Z", "tool")       // the 3rd
	call("u2", "2026-11-03T09:00:00Z", "background") // neither llm nor tool
	call("admin", "2026-11-03T09:00:00Z", "llm")     // operator
	call("dbdev", "2026-11-03T09:00:00Z", "llm")     // debug build
	call("envdev", "2026-11-03T09:00:00Z", "tool")   // DEV_USER_IDS
	call("mock_a", "2026-11-03T09:00:00Z", "llm")    // mock persona

	m := &DailyMetrics{db: db, devIDs: []string{"envdev"}}
	day := func(s string) time.Time { d, _ := time.Parse(dailyDateLayout, s); return d }
	got, err := m.compute(ctx, day("2026-11-01"), day("2026-11-04"))
	if err != nil {
		t.Fatal(err)
	}
	want := []DailyRecord{
		{Date: "2026-11-01", TotalUsers: 1, NewUsers: 1, IsFinal: true},
		{Date: "2026-11-02", DAU: 1, LLMRequests: 1, TotalUsers: 1, IsFinal: true},
		{Date: "2026-11-03", DAU: 2, LLMRequests: 1, ToolRequests: 1, TotalUsers: 2, NewUsers: 1, IsFinal: true},
		{Date: "2026-11-04", TotalUsers: 2, IsFinal: true}, // a quiet day is a record of zeros, not a gap
	}
	if len(got) != len(want) {
		t.Fatalf("got %d records, want %d: %+v", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("day %d:\n got  %+v\n want %+v", i, got[i], want[i])
		}
	}

	// today is still open; the same call twice gives the same closed days
	today := clock.Today()
	open, err := m.compute(ctx, today.AddDate(0, 0, -1), today)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 2 || !open[0].IsFinal || open[1].IsFinal {
		t.Fatalf("is_final wrong around today: %+v", open)
	}
	// the widest allowed range still answers, one record per day
	wide, err := m.compute(ctx, today.AddDate(0, 0, -89), today)
	if err != nil || len(wide) != 90 {
		t.Fatalf("90-day range: %d records, err %v", len(wide), err)
	}
}
