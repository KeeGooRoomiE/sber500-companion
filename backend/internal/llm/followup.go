package llm

import (
	_ "embed"
	"strings"
)

//go:embed prompts/followup_system.md
var DefaultFollowupSystem string

// BuildFollowup is the user prompt for one follow-up chip under an insight.
//
// It reuses the insight's own prompt as the data block, so the answer is grounded in exactly
// what the insight was built from — no second, subtly different assembly to drift out of sync.
// The insight's text goes in too: half the questions («Почему ты так решил?») are about the
// wording itself, and without it the model would have to guess what it had said.
func BuildFollowup(insightPrompt, insight, question string) string {
	var b strings.Builder
	b.WriteString(dropFinalInstruction(insightPrompt))
	b.WriteString("\n\nТы уже сказал человеку:\n«" + strings.TrimSpace(insight) + "»\n\n")
	b.WriteString("Человек уточняет: «" + question + "»\n\n")
	b.WriteString("Ответь только на это уточнение.")
	return b.String()
}

// dropFinalInstruction cuts the closing line each insight prompt ends with («Объясни этот
// день.», «Подведи итоги недели.»). The follow-up brings its own instruction, and two of them
// in one prompt compete for what the model should actually do.
func dropFinalInstruction(p string) string {
	t := strings.TrimRight(p, " \t\n")
	if i := strings.LastIndex(t, "\n\n"); i > 0 {
		return strings.TrimRight(t[:i], " \t\n")
	}
	return t
}
