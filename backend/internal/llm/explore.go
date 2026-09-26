package llm

import (
	_ "embed"
	"strings"
)

//go:embed prompts/explore_system.md
var DefaultExploreSystem string

// BuildExplore is the user prompt for one «Хочу ещё» question.
func BuildExplore(question string, facts []string, profile map[string]string) string {
	var b strings.Builder
	profileContext(&b, profile)
	b.WriteString("Вопрос человека: «" + question + "»\n\n")
	b.WriteString("Факты (посчитаны по его данным — опирайся только на них):\n")
	for _, f := range facts {
		b.WriteString("  - " + f + "\n")
	}
	b.WriteString("\nОтветь на вопрос.")
	return b.String()
}
