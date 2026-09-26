package mock

import (
	"strings"
	"testing"
)

func TestPersonasAreConsistent(t *testing.T) {
	personas, err := Personas()
	if err != nil {
		t.Fatal(err)
	}
	if len(personas) < 5 {
		t.Fatalf("expected at least 5 personas, got %d", len(personas))
	}
	seen := map[string]bool{}
	for _, p := range personas {
		if seen[p.ID] || !strings.HasPrefix(p.ID, Prefix) {
			t.Errorf("%s: duplicate id or no %q prefix", p.ID, Prefix)
		}
		seen[p.ID] = true
		if len(p.Days) == 0 || p.Days[len(p.Days)-1].Offset != -1 {
			t.Errorf("%s: the last day must be yesterday (offset -1)", p.ID)
		}
		if p.Truth() == "" {
			t.Errorf("%s: yesterday needs a note — eval shows it next to the answer", p.ID)
		}
		for i, d := range p.Days {
			if i > 0 && d.Offset != p.Days[i-1].Offset+1 {
				t.Errorf("%s: offsets must be consecutive", p.ID)
			}
			if len(d.HourlyUnlocks) != 24 || len(d.HourlyScreen) != 24 {
				t.Errorf("%s day %d: hourly series must have 24 values", p.ID, d.Offset)
				continue
			}
			sum := 0
			for _, v := range d.HourlyUnlocks {
				sum += v
			}
			if d.Unlocks != nil && sum != *d.Unlocks {
				t.Errorf("%s day %d: hourly unlocks sum to %d, daily says %d", p.ID, d.Offset, sum, *d.Unlocks)
			}
			if d.Feel != "" && d.Feel != "ok" && d.Feel != "meh" && d.Feel != "hard" {
				t.Errorf("%s day %d: bad feel %q", p.ID, d.Offset, d.Feel)
			}
		}
	}
}
