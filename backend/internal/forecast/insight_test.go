package forecast

import "testing"

// The cache key is also the gate: an argument that gets a key gets to the model, so an
// unknown one must never produce a plausible-looking key.
func TestInsightCacheKind(t *testing.T) {
	ok := map[string][2]string{
		"in:midday":           {InsightMidday, ""},
		"in:question":         {InsightQuestion, ""},
		"in:stat:screen":      {InsightStat, "screen"},
		"in:stat:sleep":       {InsightStat, "sleep"},
		"in:stat:unlocks":     {InsightStat, "unlocks"},
		"in:retro:2026-10-01": {InsightRetro, "2026-10-01"},
		"in:tag:Работа":       {InsightTag, "Работа"},
	}
	for want, in := range ok {
		got, err := insightCacheKind(in[0], in[1])
		if err != nil || got != want {
			t.Errorf("insightCacheKind(%q, %q) = %q, %v; want %q", in[0], in[1], got, err, want)
		}
	}

	bad := [][2]string{
		{"", ""},
		{"unknown", ""},
		{InsightStat, ""},
		{InsightStat, "battery"},     // not a tile we show
		{InsightRetro, "вчера"},      // not a date
		{InsightRetro, "2026-13-45"}, // not a real date
		{InsightTag, ""},             // empty tag
		{InsightTag, "ооооооооооооооооооооооооооооооооочень длинный тег"},
	}
	for _, in := range bad {
		if got, err := insightCacheKind(in[0], in[1]); err == nil {
			t.Errorf("insightCacheKind(%q, %q) = %q, want an error", in[0], in[1], got)
		}
	}
}

func TestHasTagIsForgiving(t *testing.T) {
	tags := []string{" Работа ", "Спорт"}
	for _, want := range []string{"работа", "Работа", "РАБОТА", "спорт"} {
		if !hasTag(tags, want) {
			t.Errorf("hasTag(%v, %q) = false", tags, want)
		}
	}
	if hasTag(tags, "Люди") {
		t.Error("hasTag matched a tag that is not there")
	}
}
