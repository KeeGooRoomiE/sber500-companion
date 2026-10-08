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

// The behavioural personas exist for the shape of their day, not its totals. A regenerated
// personas.json that smooths them out would still pass every other check here while quietly
// making the behavioural signals untestable — which is the failure this guards against.
func TestBehaviouralPersonasKeepTheirShape(t *testing.T) {
	personas, err := Personas()
	if err != nil {
		t.Fatal(err)
	}
	by := map[string]Persona{}
	for _, p := range personas {
		by[p.ID] = p
	}

	yesterday := func(id string) Day {
		p, ok := by[id]
		if !ok {
			t.Fatalf("persona %s is gone", id)
		}
		return p.Days[len(p.Days)-1]
	}
	val := func(p *int) int {
		if p == nil {
			return 0
		}
		return *p
	}
	session := func(d Day) float64 {
		if val(d.Unlocks) == 0 {
			return 0
		}
		return float64(val(d.ScreenMin)) / float64(val(d.Unlocks))
	}

	// A short burst, then at least one fully empty hour, then the real day.
	fs := yesterday("mock_falsestart")
	if fs.HourlyScreen[6] == 0 || fs.HourlyScreen[6] > 10 {
		t.Errorf("false start: hour 6 should hold a brief glance, got %d min", fs.HourlyScreen[6])
	}
	if fs.HourlyScreen[7] != 0 {
		t.Errorf("false start: hour 7 must be empty — the gap is the signal, got %d min", fs.HourlyScreen[7])
	}
	if fs.HourlyScreen[9] == 0 {
		t.Error("false start: the real day should have started by 9")
	}

	// Session length has to separate the checker from the immersed day by a wide margin.
	ch, im := session(yesterday("mock_checker")), session(yesterday("mock_immersed"))
	if ch >= 3 {
		t.Errorf("checker: session should be short, got %.1f min", ch)
	}
	if im <= 10 {
		t.Errorf("immersed: session should be long, got %.1f min", im)
	}
	if im/ch < 5 {
		t.Errorf("checker vs immersed: %.1f and %.1f are too close to tell apart", ch, im)
	}

	// Never puts the phone down during the work day.
	chk := yesterday("mock_checker")
	for h := 9; h < 18; h++ {
		if chk.HourlyScreen[h] == 0 {
			t.Errorf("checker: hour %d is empty, the persona is meant to have no break", h)
		}
	}

	// The pair exists to be indistinguishable by phone and separable by steps. If their
	// screen or unlocks ever diverge, the pair stops testing what it was built to test.
	away, desk := yesterday("mock_awayday"), yesterday("mock_deskday")
	if val(away.ScreenMin) != val(desk.ScreenMin) || val(away.Unlocks) != val(desk.Unlocks) {
		t.Errorf("away/desk must look identical by phone: screen %d vs %d, unlocks %d vs %d",
			val(away.ScreenMin), val(desk.ScreenMin), val(away.Unlocks), val(desk.Unlocks))
	}
	if away.Steps == nil || desk.Steps == nil {
		t.Fatal("away/desk: both need steps — steps are the only thing telling them apart")
	}
	if *away.Steps < 5*(*desk.Steps) {
		t.Errorf("away/desk: steps %d vs %d are too close to disambiguate", *away.Steps, *desk.Steps)
	}
}
