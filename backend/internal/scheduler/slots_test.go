package scheduler

import (
	"fmt"
	"testing"
	"time"
)

const (
	slot = 5 * time.Minute
	lead = 45 * time.Minute
)

func TestTarget(t *testing.T) {
	cases := []struct {
		name  string
		opens []int
		want  int
	}{
		{"no history: 04:10 minus lead", nil, 250 - 45},
		{"4 days is too few", []int{400, 400, 400, 400}, 250 - 45},
		{"median of 5", []int{420, 430, 440, 900, 100}, 430 - 45},
		{"even count averages the middle", []int{400, 410, 420, 430, 440, 450}, 425 - 45},
		{"early riser clamps to 00:30", []int{60, 60, 60, 60, 60}, windowStart},
		{"late riser clamps to 07:00", []int{700, 700, 700, 700, 700}, windowEnd},
	}
	for _, c := range cases {
		if got := Target(c.opens, lead); got != c.want {
			t.Errorf("%s: got %d, want %d", c.name, got, c.want)
		}
	}
}

func TestPlanPicksOwnSlot(t *testing.T) {
	targets := map[string]int{"a": 180, "b": 183, "c": 185, "d": 300}
	// Slot 03:00 (180): a and b share it (183 is in 180–184), c is the next slot.
	batch, carried := Plan([]string{"d", "c", "b", "a"}, targets, 205, 180, slot, 100)
	if fmt.Sprint(batch) != "[a b]" || carried != 0 {
		t.Fatalf("slot 180: batch %v carried %d", batch, carried)
	}
	batch, _ = Plan([]string{"c", "d"}, targets, 205, 185, slot, 100)
	if fmt.Sprint(batch) != "[c]" {
		t.Fatalf("slot 185: %v", batch)
	}
}

func TestPlanDefaultTargetForUnknownUsers(t *testing.T) {
	batch, _ := Plan([]string{"new"}, nil, 205, 200, slot, 100)
	if len(batch) != 0 {
		t.Fatalf("not due at 03:20: %v", batch)
	}
	batch, _ = Plan([]string{"new"}, nil, 205, 205, slot, 100)
	if len(batch) != 1 {
		t.Fatalf("due at 03:25: %v", batch)
	}
}

func TestPlanPackLimitCarriesOver(t *testing.T) {
	var pending []string
	targets := map[string]int{}
	for i := 0; i < 250; i++ {
		id := fmt.Sprintf("u%03d", i)
		pending = append(pending, id)
		targets[id] = 180
	}
	batch, carried := Plan(pending, targets, 205, 180, slot, 100)
	if len(batch) != 100 || carried != 150 {
		t.Fatalf("batch %d carried %d", len(batch), carried)
	}
	// Next tick: the first 100 are done (no longer pending); the overflow goes first.
	rest := pending[100:]
	batch, carried = Plan(rest, targets, 205, 185, slot, 100)
	if len(batch) != 100 || batch[0] != "u100" || carried != 50 {
		t.Fatalf("second slot: %d first=%s carried %d", len(batch), batch[0], carried)
	}
}

func TestPlanEarlierTargetsFirst(t *testing.T) {
	targets := map[string]int{"late": 200, "early": 100}
	batch, carried := Plan([]string{"late", "early"}, targets, 205, 200, slot, 1)
	if fmt.Sprint(batch) != "[early]" || carried != 1 {
		t.Fatalf("batch %v carried %d", batch, carried)
	}
}

func TestNextBoundary(t *testing.T) {
	loc := time.FixedZone("x", 3*3600)
	cases := map[string]string{
		"2026-10-05T04:07:30": "04:10",
		"2026-10-05T04:10:00": "04:15",
		"2026-10-05T23:58:00": "00:00",
	}
	for in, want := range cases {
		now, _ := time.ParseInLocation("2006-01-02T15:04:05", in, loc)
		if got := nextBoundary(now, slot).Format("15:04"); got != want {
			t.Errorf("%s: got %s, want %s", in, got, want)
		}
	}
}

func TestConfigDefaultsOff(t *testing.T) {
	t.Setenv("MORNING_MODE", "")
	t.Setenv("PACK_SIZE", "x")
	c := ConfigFromEnv()
	if c.Slots || c.PackSize != 100 || c.Slot != slot || c.Lead != lead {
		t.Fatalf("%+v", c)
	}
	t.Setenv("MORNING_MODE", "slots")
	t.Setenv("PACK_SIZE", "50")
	if c = ConfigFromEnv(); !c.Slots || c.PackSize != 50 {
		t.Fatalf("%+v", c)
	}
}

// Simulation: 1000 people with first opens spread like the research (peak 03:00–04:00 MSK,
// the rest through the morning), stepped slot by slot through the night.
func TestSimulateNight(t *testing.T) {
	const n = 1000
	targets := map[string]int{}
	pending := map[string]bool{}
	for i := 0; i < n; i++ {
		id := fmt.Sprintf("u%04d", i)
		var habit int
		switch {
		case i%10 < 6: // 60%: 03:00–04:00 MSK
			habit = 180 + (i*7)%60
		case i%10 < 9: // 30%: 05:00–07:30
			habit = 300 + (i*13)%150
		default: // 10%: no history
			habit = defaultHabitMinute
		}
		opens := []int{habit, habit, habit, habit, habit}
		if i%10 == 9 {
			opens = nil
		}
		targets[id] = Target(opens, lead)
		pending[id] = true
	}

	maxBatch, done := 0, 0
	for now := windowStart; now < tickEnd; now += 5 {
		var ids []string
		for id := range pending {
			ids = append(ids, id)
		}
		batch, _ := Plan(ids, targets, Target(nil, lead), now, slot, 100)
		if len(batch) > 100 {
			t.Fatalf("slot %d: batch of %d", now, len(batch))
		}
		for _, id := range batch {
			if now < targets[id]-4 {
				t.Fatalf("%s generated at %d before target %d", id, now, targets[id])
			}
			delete(pending, id)
			done++
		}
		if len(batch) > maxBatch {
			maxBatch = len(batch)
		}
	}
	t.Logf("done %d of %d, largest slot %d, left %d", done, n, maxBatch, len(pending))
	if len(pending) != 0 {
		t.Fatalf("%d users never generated", len(pending))
	}
}
