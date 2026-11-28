package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Uses only a supplied test database with migrations applied; the model is a local fixture.
// The IDs issued by /register isolate each run without truncating any tables.
func TestChatFlowOnDatabase(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var attempts atomic.Int32
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts.Add(1)
		var request struct{ Messages []struct{ Content string } }
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			return
		}
		var input struct {
			Mode    string
			Facts   []repo.ChatFact
			Message string
		}
		if err := json.Unmarshal([]byte(request.Messages[1].Content), &input); err != nil {
			t.Error(err)
			return
		}
		decision := llm.ChatDecision{Kind: "answer", Text: "Сегодня пока мало данных.", Pills: []string{"Что было вчера?", "Что известно о моём дне?"}}
		switch input.Mode {
		case "remember":
			decision.Kind = "remember"
			decision.Key = "work_schedule"
			decision.Fact = "работаю по ночам"
			if len(input.Facts) > 0 {
				decision.ReplaceID = input.Facts[0].ID
				decision.Fact = "работаю днём"
			}
		case "memory_list":
			if strings.Contains(input.Message, "точно") {
				http.Error(w, "temporary provider failure", http.StatusServiceUnavailable)
				return
			}
			decision.Kind = "memory_list"
		case "forget":
			decision.Kind = "forget"
			if len(input.Facts) > 0 {
				decision.ReplaceID = input.Facts[0].ID
			}
		}
		content, _ := json.Marshal(decision)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"role": "assistant", "content": string(content)}}}, "usage": map[string]int{"prompt_tokens": 100, "completion_tokens": 35}})
	}))
	defer fixture.Close()
	t.Setenv("LLM_API_KEY", "")
	t.Setenv("LLM_BASE_URL", fixture.URL)
	t.Setenv("LLM_FALLBACK_MODELS", "")
	t.Setenv("LLM_DAILY_CAP", "5000")
	logger := analytics.NewLogger(db, nil)
	generator := forecast.NewGenerator(db, repo.NewDailyRepo(db), repo.NewCheckInRepo(db), repo.NewMorningRepo(db), llm.NewClient(), logger, prompts.NewStore(db, map[string]string{prompts.ChatSystem: llm.DefaultChatSystem}))
	r := chi.NewRouter()
	Mount(r, Deps{DB: db, CallLog: logger, Generator: generator})
	post := func(path, token string, body any, code int) []byte {
		t.Helper()
		data, _ := json.Marshal(body)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/"+path, bytes.NewReader(data))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != code {
			t.Fatalf("%s returned %d, want %d: %s", path, w.Code, code, w.Body.String())
		}
		return w.Body.Bytes()
	}
	register := func() RegisterResponse {
		var u RegisterResponse
		if err := json.Unmarshal(post("register", "", RegisterRequest{}, 201), &u); err != nil {
			t.Fatal(err)
		}
		return u
	}
	first, second := register(), register()
	msg := func(id, text string) forecast.ChatInput {
		return forecast.ChatInput{ID: id, Text: text, Source: "typed"}
	}
	read := msg("read", "Что ты обо мне помнишь?")
	post("chat", "", read, 401)
	if got := string(post("chat", second.Token, read, 200)); !strings.Contains(got, "Пока у меня нет") {
		t.Fatal("empty memory")
	}
	save := msg("save", "Я хочу, чтобы ты запомнил обо мне: работаю по ночам")
	firstReply := string(post("chat", first.Token, save, 200))
	calls := attempts.Load()
	if got := string(post("chat", first.Token, save, 200)); got != firstReply || attempts.Load() != calls {
		t.Fatal("non-idempotent replay")
	}
	post("chat", first.Token, msg("save", "Другой вопрос"), 409)
	if got := string(post("chat", first.Token, read, 200)); !strings.Contains(got, "работаю по ночам") {
		t.Fatal("fact missing")
	}
	if got := string(post("chat", first.Token, msg("read_fallback", "Что ты точно обо мне помнишь?"), 200)); !strings.Contains(got, "работаю по ночам") {
		t.Fatal("fact missing during provider failure")
	}
	if got := string(post("chat", second.Token, msg("read_again", read.Text), 200)); strings.Contains(got, "работаю по ночам") {
		t.Fatal("other user's fact")
	}
	post("chat", first.Token, msg("correct", "Запомни, теперь работаю днём"), 200)
	if got := string(post("chat", first.Token, msg("read_again", read.Text), 200)); strings.Contains(got, "ночам") || !strings.Contains(got, "работаю днём") {
		t.Fatal("correction missing")
	}
	post("chat", first.Token, msg("forget", "Забудь мой рабочий график"), 200)
	if got := string(post("chat", first.Token, msg("read_final", read.Text), 200)); !strings.Contains(got, "Пока у меня нет") {
		t.Fatal("deletion missing")
	}
	calls = attempts.Load()
	if got := string(post("chat", first.Token, msg("offtopic", "Расскажи мне анекдот"), 200)); !strings.Contains(got, "Давай поговорим о тебе") || attempts.Load() != calls {
		t.Fatal("off-topic handling")
	}
	starter := msg("start", "Расскажи подробнее про сегодняшний день")
	starter.Source = "starter"
	if got := string(post("chat", first.Token, starter, 200)); !strings.Contains(got, "мало данных") || attempts.Load() != calls {
		t.Fatal("empty observations")
	}
	var charged int32
	if err := db.QueryRow(ctx, "SELECT count(*) FROM chat_model_usage WHERE user_id IN ($1,$2)", first.UserID, second.UserID).Scan(&charged); err != nil || charged != attempts.Load() {
		t.Fatal("attempt accounting", charged, attempts.Load(), err)
	}
}
