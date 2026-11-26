package api

import (
	"context"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
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
	        ('mock_a', '2026-11-01T07:00:00Z', false),
	        ('admin',  '2026-11-01T07:00:00Z', false)`)
	exec(`INSERT INTO user_activity (user_id, date) VALUES
	        ('u1', '2026-11-02'), ('u1', '2026-11-03'), ('u2', '2026-11-03'),
	        ('dbdev', '2026-11-03'), ('envdev', '2026-11-03'), ('mock_a', '2026-11-03'),
	        ('admin', '2026-11-02'), ('dbdev', '2026-11-02'),
	        ('envdev', '2026-11-02'), ('mock_a', '2026-11-02')`)
	full := func(user, ts, typ, component, trigger string) {
		exec(`INSERT INTO call_log (user_id, ts, call_type, component, trigger, user_visible, result)
		      VALUES ($1, $2::timestamptz, $3, $4, $5, true, 'ok')`, user, ts, typ, component, trigger)
	}
	call := func(user, ts, typ string) { full(user, ts, typ, "x", "user_action") }
	call("u1", "2026-11-02T20:30:00Z", "llm")        // 23:30 Moscow, the 2nd
	call("u1", "2026-11-02T21:30:00Z", "llm")        // 00:30 Moscow, the 3rd
	call("u2", "2026-11-03T09:00:00Z", "background") // neither llm nor tool
	call("admin", "2026-11-03T09:00:00Z", "llm")     // operator
	call("dbdev", "2026-11-03T09:00:00Z", "llm")     // debug build
	call("envdev", "2026-11-03T09:00:00Z", "tool")   // DEV_USER_IDS
	call("mock_a", "2026-11-03T09:00:00Z", "llm")    // mock persona

	// The nightly forecast counts as a request only when the person came and collected it.
	// u1 fetched theirs (morning_api on the same Moscow day), u2 never did.
	full("u1", "2026-11-03T00:10:00Z", "llm", "llm_morning", "scheduled")
	full("u1", "2026-11-03T09:00:00Z", "tool", "morning_api", "user_action")
	full("u2", "2026-11-03T00:10:00Z", "llm", "llm_morning", "scheduled")

	m := &DailyMetrics{db: db, devIDs: []string{"envdev"}}
	day := func(s string) time.Time { d, _ := time.Parse(dailyDateLayout, s); return d }
	today := day("2026-11-25")
	got, err := m.compute(ctx, day("2026-11-01"), day("2026-11-04"), today)
	if err != nil {
		t.Fatal(err)
	}
	zero, one := 0, 1
	want := []DailyRecord{
		{Date: "2026-11-01", TotalUsers: 1, NewUsers: 1, IsFinal: true, RetainedD1: &one, RetainedD3: &zero, RetainedD7: &zero, RetainedD14: &zero},
		{Date: "2026-11-02", DAU: 1, LLMRequests: 1, TotalUsers: 1, IsFinal: true, RetainedD1: &zero, RetainedD3: &zero, RetainedD7: &zero, RetainedD14: &zero},
		// u1's 00:30 call plus u1's collected forecast; u2's uncollected one is nobody's request.
		// ToolRequests is always 0: the product makes no tool/function calls.
		{Date: "2026-11-03", DAU: 2, LLMRequests: 2, TotalUsers: 2, NewUsers: 1, IsFinal: true, RetainedD1: &zero, RetainedD3: &zero, RetainedD7: &zero, RetainedD14: &zero},
		{Date: "2026-11-04", TotalUsers: 2, IsFinal: true, RetainedD1: &zero, RetainedD3: &zero, RetainedD7: &zero, RetainedD14: &zero},
	}
	if len(got) != len(want) {
		t.Fatalf("got %d records, want %d: %+v", len(got), len(want), got)
	}
	for i := range want {
		if !reflect.DeepEqual(got[i], want[i]) {
			t.Errorf("day %d:\n got  %+v\n want %+v", i, got[i], want[i])
		}
	}

	// Today is still open.
	open, err := m.compute(ctx, today.AddDate(0, 0, -1), today, today)
	if err != nil {
		t.Fatal(err)
	}
	if len(open) != 2 || !open[0].IsFinal || open[1].IsFinal {
		t.Fatalf("is_final wrong around today: %+v", open)
	}
	// the widest allowed range still answers, one record per day
	wide, err := m.compute(ctx, today.AddDate(0, 0, -89), today, today)
	if err != nil || len(wide) != 90 {
		t.Fatalf("90-day range: %d records, err %v", len(wide), err)
	}

	exec(`INSERT INTO users (id, created_at) VALUES
	        ('cohort10', '2026-11-10T07:00:00Z'),
	        ('cohort16', '2026-11-15T21:30:00Z'),
	        ('cohort16late', '2026-11-16T10:00:00Z'),
	        ('cohort17', '2026-11-17T07:00:00Z')`)
	exec(`INSERT INTO user_activity (user_id, date) VALUES
	        ('cohort10', '2026-11-11'), ('cohort10', '2026-11-13'),
	        ('cohort10', '2026-11-17'), ('cohort10', '2026-11-24'),
	        ('cohort16', '2026-11-23'), ('cohort16late', '2026-11-24'),
	        ('cohort17', '2026-11-24')`)
	call("cohort16", "2026-11-17T09:00:00Z", "background") // without a DAU action
	checkCounts := func(r DailyRecord, d1, d3, d7, d14 *int) {
		t.Helper()
		for i, got := range []*int{r.RetainedD1, r.RetainedD3, r.RetainedD7, r.RetainedD14} {
			want := []*int{d1, d3, d7, d14}[i]
			switch {
			case want == nil && got != nil:
				t.Errorf("%s D%d = %d, want null", r.Date, [4]int{1, 3, 7, 14}[i], *got)
			case want != nil && (got == nil || *got != *want):
				t.Errorf("%s D%d = %v, want %d", r.Date, [4]int{1, 3, 7, 14}[i], ptrValue(got), *want)
			}
		}
	}
	recent, err := m.compute(ctx, day("2026-11-10"), day("2026-11-24"), today)
	if err != nil || len(recent) != 15 {
		t.Fatalf("15-day range: %d records, err %v", len(recent), err)
	}
	checkCounts(recent[0], &one, &one, &one, &one)
	checkCounts(recent[1], &zero, &zero, &zero, nil) // empty cohort, D14 is today
	if recent[6].NewUsers != 2 {
		t.Fatalf("registration cohort for November 16: got %d users, want 2", recent[6].NewUsers)
	}
	checkCounts(recent[6], &zero, &zero, &one, nil) // D8 is not D7
	checkCounts(recent[14], nil, nil, nil, nil)     // yesterday's cohort

	from := day("2026-11-16")
	for _, c := range []struct {
		today string
		want  *int
	}{{"2026-11-23", nil}, {"2026-11-24", &one}} {
		oneDay, err := m.compute(ctx, from, from, day(c.today))
		if err != nil || len(oneDay) != 1 {
			t.Fatalf("historical cohort on %s: %d records, err %v", c.today, len(oneDay), err)
		}
		// The activity date is outside the requested cohort range.
		checkCounts(oneDay[0], &zero, &zero, c.want, nil)
	}
}

func ptrValue(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}
