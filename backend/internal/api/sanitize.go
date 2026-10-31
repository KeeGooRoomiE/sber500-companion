package api

import (
	"strings"
	"unicode"
)

// Limits on free text that ends up inside LLM prompts. Without them a 64 KB request body
// became a 64 KB prompt (about 11x the normal size: cost, timeouts, errors).
const (
	maxCheckInTags = 6
	maxTagRunes    = 24
	maxNoteRunes   = 500
)

// oneLine turns every run of whitespace and control characters (newlines included) into a
// single space and trims the ends, so a value cannot add lines or fake sections to a prompt.
func oneLine(s string) string {
	var b strings.Builder
	space := false
	for _, r := range s {
		if unicode.IsSpace(r) || unicode.IsControl(r) {
			space = true
			continue
		}
		if space && b.Len() > 0 {
			b.WriteByte(' ')
		}
		space = false
		b.WriteRune(r)
	}
	return b.String()
}

// truncateRunes cuts s to at most n runes.
func truncateRunes(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// cleanTags keeps at most maxCheckInTags distinct, non-empty one-line tags of at most
// maxTagRunes runes each. Never nil, so the column stays an empty array rather than NULL.
func cleanTags(in []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, t := range in {
		t = truncateRunes(oneLine(t), maxTagRunes)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
		if len(out) == maxCheckInTags {
			break
		}
	}
	return out
}

// cleanNote bounds the optional note: one line, at most maxNoteRunes runes; empty becomes nil.
func cleanNote(in *string) *string {
	if in == nil {
		return nil
	}
	s := truncateRunes(oneLine(*in), maxNoteRunes)
	if s == "" {
		return nil
	}
	return &s
}
