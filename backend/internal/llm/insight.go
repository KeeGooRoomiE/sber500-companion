package llm

import (
	_ "embed"
	"fmt"
	"strings"
)

//go:embed prompts/insight_system.md
var DefaultInsightSystem string

// BuildInsight wraps one slice of a person's data with the question being asked of it.
//
// All the insight kinds — the midday read, a single tile, a retrospective, a tag, a generated
// question — are the same shape: some facts, then what to do with them. Keeping one builder
// means the context block is assembled identically everywhere, so an answer about sleep cannot
// quietly be built from different numbers than an answer about the day.
func BuildInsight(task string, facts []string, profile map[string]string) string {
	var b strings.Builder
	profileContext(&b, profile)
	b.WriteString("Данные:\n")
	for _, f := range facts {
		if f = strings.TrimSpace(f); f != "" {
			b.WriteString("  - " + f + "\n")
		}
	}
	b.WriteString("\n" + task)
	return b.String()
}

// FactLine is a small helper for «что: значение (обычно столько-то)» lines.
func FactLine(label, value, usual string) string {
	if usual == "" {
		return fmt.Sprintf("%s: %s", label, value)
	}
	return fmt.Sprintf("%s: %s, обычно %s", label, value, usual)
}
