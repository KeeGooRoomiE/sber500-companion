package llm

import "strings"

import "testing"

func TestDropFinalInstruction(t *testing.T) {
	cases := []struct {
		name, in, want string
	}{
		{
			name: "day review prompt loses its closing line",
			in:   "Контекст\n\nДанные дня:\n  экран 320 мин\n\nОбъясни этот день.",
			want: "Контекст\n\nДанные дня:\n  экран 320 мин",
		},
		{
			name: "weekly prompt loses its closing line",
			in:   "По дням:\n  пн: 200\n  вт: 250\n\nПодведи итоги недели.",
			want: "По дням:\n  пн: 200\n  вт: 250",
		},
		{
			name: "trailing newlines do not confuse the cut",
			in:   "Данные\n\nСоставь утренний прогноз на сегодня.\n\n",
			want: "Данные",
		},
		{
			name: "a prompt without a blank line is left alone",
			in:   "Одна строка без инструкции",
			want: "Одна строка без инструкции",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := dropFinalInstruction(c.in); got != c.want {
				t.Errorf("dropFinalInstruction()\n got: %q\nwant: %q", got, c.want)
			}
		})
	}
}

func TestBuildFollowupKeepsDataAndDropsOldInstruction(t *testing.T) {
	prompt := "Контекст о человеке:\n  спит мало\n\nДанные дня:\n  экран 320 мин\n\nОбъясни этот день."
	got := BuildFollowup(prompt, "Вчера экран был выше обычного.", "Почему ты так решил?")

	for _, must := range []string{"экран 320 мин", "спит мало", "Вчера экран был выше обычного.", "Почему ты так решил?"} {
		if !strings.Contains(got, must) {
			t.Errorf("follow-up prompt lost %q:\n%s", must, got)
		}
	}
	// The insight's own instruction must not survive — two instructions compete.
	if strings.Contains(got, "Объясни этот день.") {
		t.Errorf("follow-up prompt kept the original instruction:\n%s", got)
	}
	if !strings.HasSuffix(got, "Ответь только на это уточнение.") {
		t.Errorf("follow-up prompt must end with its own instruction:\n%s", got)
	}
}
