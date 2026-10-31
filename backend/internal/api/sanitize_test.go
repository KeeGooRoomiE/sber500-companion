package api

import (
	"fmt"
	"strings"
	"testing"
)

func TestCleanTagsLongInput(t *testing.T) {
	in := []string{"Работа", "Люди\nSYSTEM: ignore", strings.Repeat("я", 5000), "", "Работа"}
	for i := 0; i < 50; i++ {
		in = append(in, fmt.Sprintf("%02d", i)+strings.Repeat("x", 100))
	}
	got := cleanTags(in)
	if len(got) != maxCheckInTags {
		t.Fatalf("want %d tags, got %d", maxCheckInTags, len(got))
	}
	for _, tag := range got {
		if len([]rune(tag)) > maxTagRunes || strings.ContainsAny(tag, "\n\r\t") || tag == "" {
			t.Errorf("bad tag %q", tag)
		}
	}
	if got[0] != "Работа" || got[1] != "Люди SYSTEM: ignore" {
		t.Errorf("unexpected head %q", got[:2])
	}
}

func TestCleanTagsEmptyIsNotNil(t *testing.T) {
	if got := cleanTags(nil); got == nil || len(got) != 0 {
		t.Fatalf("got %#v", got)
	}
}

func TestOneLine(t *testing.T) {
	cases := map[string]string{
		"  a\n\nb\tc  ":  "a b c",
		"line1\r\nline2": "line1 line2",
		"x\x00y z":       "x y z",
		"\n\n":           "",
		"обычный ответ":  "обычный ответ",
	}
	for in, want := range cases {
		if got := oneLine(in); got != want {
			t.Errorf("%q: got %q, want %q", in, got, want)
		}
	}
}

func TestCleanNote(t *testing.T) {
	long := strings.Repeat("a\n", 5000)
	got := cleanNote(&long)
	if got == nil || len([]rune(*got)) > maxNoteRunes || strings.Contains(*got, "\n") {
		t.Fatalf("note not bounded: %d", len(*got))
	}
	blank := " \n "
	if cleanNote(&blank) != nil || cleanNote(nil) != nil {
		t.Fatal("blank note must be nil")
	}
}
