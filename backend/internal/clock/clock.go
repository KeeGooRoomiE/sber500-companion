// Package clock pins "today" to the product's time zone instead of the server's.
//
// The server may run in UTC while users live in Moscow: a date computed with
// time.Now().Truncate(24*time.Hour) is a UTC midnight and, between 00:00 and
// 03:00 MSK, still yesterday. Everything date-shaped goes through here.
package clock

import (
	"log/slog"
	"os"
	"time"
)

var loc = load()

func load() *time.Location {
	name := os.Getenv("APP_TZ")
	if name == "" {
		name = "Europe/Moscow"
	}
	l, err := time.LoadLocation(name)
	if err != nil {
		slog.Error("clock: bad APP_TZ, falling back to UTC", "tz", name, "err", err)
		return time.UTC
	}
	return l
}

// Location is the product time zone (APP_TZ, default Europe/Moscow).
func Location() *time.Location { return loc }

// Now is the current time in the product time zone.
func Now() time.Time { return time.Now().In(loc) }

// Today is today's calendar date as a UTC midnight — safe to pass to a DATE column.
func Today() time.Time { return DateOf(Now()) }

// DateOf returns t's calendar date (in the product zone) as a UTC midnight.
func DateOf(t time.Time) time.Time {
	y, m, d := t.In(loc).Date()
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
}

// NextAt returns the next wall-clock hour:minute in the product time zone.
func NextAt(hour, minute int) time.Time {
	now := Now()
	next := time.Date(now.Year(), now.Month(), now.Day(), hour, minute, 0, 0, loc)
	if !next.After(now) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}
