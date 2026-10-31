package scheduler

import (
	"os"
	"sort"
	"strconv"
	"time"
)

// Slot mode (MORNING_MODE=slots): instead of one batch at 04:10, each person's message is
// generated shortly before they usually open the app, in 5-minute slots of at most PACK_SIZE
// people. Off by default; the 04:10 run stays as a control pass for whoever is left.
const (
	// A habit needs this many days with a first open to be believed.
	minHabitDays = 5
	habitWindow  = 14 // days of call_log looked at

	// Without a habit a person is treated as opening at the batch time, as before.
	defaultHabitMinute = MorningHour*60 + MorningMinute

	// Generation target is kept inside this window of the product day, in minutes.
	windowStart = 30  // 00:30
	windowEnd   = 420 // 07:00
	// Slot ticks stop shortly after the window; whoever is still pending is served on demand.
	tickEnd = windowEnd + 60
)

// Config of the slot scheduler. Zero Mode means the legacy single batch.
type Config struct {
	Slots    bool
	PackSize int
	Slot     time.Duration
	Lead     time.Duration
}

// ConfigFromEnv reads MORNING_MODE, PACK_SIZE, SLOT and LEAD; bad values fall back to defaults.
func ConfigFromEnv() Config {
	c := Config{PackSize: 100, Slot: 5 * time.Minute, Lead: 45 * time.Minute}
	c.Slots = os.Getenv("MORNING_MODE") == "slots"
	if n, err := strconv.Atoi(os.Getenv("PACK_SIZE")); err == nil && n > 0 {
		c.PackSize = n
	}
	if d, err := time.ParseDuration(os.Getenv("SLOT")); err == nil && d >= time.Minute && d <= time.Hour {
		c.Slot = d
	}
	if d, err := time.ParseDuration(os.Getenv("LEAD")); err == nil && d >= 0 && d <= 3*time.Hour {
		c.Lead = d
	}
	return c
}

// Target is the minute of the day to generate a person's message: the median of their first
// opens minus the lead, or the default habit when there are too few days. Always inside
// [windowStart, windowEnd].
func Target(opens []int, lead time.Duration) int {
	habit := defaultHabitMinute
	if len(opens) >= minHabitDays {
		s := append([]int(nil), opens...)
		sort.Ints(s)
		habit = s[len(s)/2]
		if len(s)%2 == 0 {
			habit = (s[len(s)/2-1] + s[len(s)/2]) / 2
		}
	}
	t := habit - int(lead/time.Minute)
	if t < windowStart {
		t = windowStart
	}
	if t > windowEnd {
		t = windowEnd
	}
	return t
}

// slotStart is the first minute of the slot the target minute falls in.
func slotStart(minute int, slot time.Duration) int {
	n := int(slot / time.Minute)
	return minute - minute%n
}

// Plan picks who is generated in the slot that starts at nowMin. A person is due once their
// slot has started; anyone beyond `pack` (or left over from an earlier slot) stays in the
// pending list and is carried to the next tick — earliest target first, so nobody is overtaken
// for long. Pending people without an entry in targets get defaultTarget.
func Plan(pending []string, targets map[string]int, defaultTarget, nowMin int, slot time.Duration, pack int) (batch []string, carried int) {
	type due struct {
		id     string
		target int
	}
	var d []due
	for _, id := range pending {
		t, ok := targets[id]
		if !ok {
			t = defaultTarget
		}
		if slotStart(t, slot) <= nowMin {
			d = append(d, due{id, t})
		}
	}
	sort.Slice(d, func(i, j int) bool {
		if d[i].target != d[j].target {
			return d[i].target < d[j].target
		}
		return d[i].id < d[j].id
	})
	for i, x := range d {
		if i < pack {
			batch = append(batch, x.id)
		}
	}
	return batch, len(d) - len(batch)
}
