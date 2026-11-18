package repo

import (
	"context"
	"os"
	"sort"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Runs only against a throwaway database with the migrations applied (TEST_DATABASE_URL).
// It truncates users and everything that hangs off them.
func TestPushTargetsCampaignOnDatabase(t *testing.T) {
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
	exec(`TRUNCATE push_campaign_sends, call_log, user_activity, users CASCADE`)

	// Nobody reported a zone, so everyone is on the default clock (Moscow).
	local := time.Now().UTC().Add(defaultOffsetMin * time.Minute)
	ago := func(days int) string { return local.AddDate(0, 0, -days).Format("2006-01-02") }

	exec(`INSERT INTO users (id, push_token, is_dev) VALUES
	        ('silent3', 'tok', false), ('active', 'tok', false), ('notoken', NULL, false),
	        ('dev', 'tok', true), ('gone', 'tok', false), ('told', 'tok', false), ('never', 'tok', false)`)
	exec(`INSERT INTO user_activity (user_id, date) VALUES
	        ('silent3', $1), ('active', $2), ('notoken', $3), ('dev', $4), ('gone', $5), ('told', $4)`,
		ago(3), ago(1), ago(5), ago(4), ago(20))
	// 'told' already got the reactivation during this silence, 30 hours ago
	exec(`INSERT INTO push_campaign_sends (user_id, campaign, sent_at) VALUES ('told', 'reactivation', NOW() - INTERVAL '30 hours')`)

	r := NewUserRepo(db)
	ids := func(q CampaignQuery) []string {
		t.Helper()
		targets, err := r.PushTargetsCampaign(ctx, q)
		if err != nil {
			t.Fatal(err)
		}
		out := []string{}
		for _, x := range targets {
			out = append(out, x.UserID)
		}
		sort.Strings(out)
		return out
	}
	same := func(name string, got []string, want ...string) {
		t.Helper()
		if len(got) != len(want) {
			t.Fatalf("%s: got %v, want %v", name, got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("%s: got %v, want %v", name, got, want)
			}
		}
	}

	react := CampaignQuery{Campaign: "reactivation", MinSilentDays: 3, MaxSilentDays: 14,
		LocalHour: local.Hour(), Weekday: -1, OncePerStreak: true}
	same("reactivation", ids(react), "silent3")

	wrongHour := react
	wrongHour.LocalHour = (local.Hour() + 1) % 24
	same("another hour", ids(wrongHour))

	if err := r.MarkCampaignSent(ctx, "reactivation", []string{"silent3"}); err != nil {
		t.Fatal(err)
	}
	same("once per streak", ids(react))

	weekly := CampaignQuery{Campaign: "weekly_silent", MinSilentDays: 3, MaxSilentDays: 28,
		LocalHour: local.Hour(), Weekday: int(local.Weekday()), MinGapDays: 6}
	// silent3 was written to minutes ago (20-hour rule); 'told' and 'gone' are due
	same("weekly", ids(weekly), "gone", "told")

	otherDay := weekly
	otherDay.Weekday = (int(local.Weekday()) + 1) % 7
	same("another weekday", ids(otherDay))

	if err := r.MarkCampaignSent(ctx, "weekly_silent", []string{"gone", "told"}); err != nil {
		t.Fatal(err)
	}
	same("weekly not twice", ids(weekly))
}
