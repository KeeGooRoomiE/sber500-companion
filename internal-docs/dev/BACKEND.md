# Backend — developer notes

## Stack

- Go 1.22+
- Router: `go-chi/chi` v5
- DB driver: `jackc/pgx/v5` with connection pool
- LLM client: `sashabaranov/go-openai` (OpenAI-compatible, BaseURL overridden to cloud.ru)
- Logging: `log/slog` structured JSON
- Migration: plain SQL files, applied via `migrate` CLI or manually

## Endpoints (planned)

```
POST /api/v1/daily-data      Body: DailyDataRequest (UsageSnapshot + SleepSnapshot + BatterySnapshot)
POST /api/v1/checkin         Body: CheckInRequest (feel: OK|MEH|HARD, tags[], note?)
GET  /api/v1/morning         Returns: MorningResponse (content, date)
GET  /api/v1/weekly          Returns: WeeklyResponse (content, week_start)
GET  /api/v1/metrics         Returns: MetricsResponse (публичные метрики для судей, CORS: *)
GET  /health                 Returns: 200 OK (liveness probe)
```

All endpoints require `X-User-ID` header (SHA256 device fingerprint).

## Database schema

```sql
-- 001_init.sql (already written, see backend/migrations/)

users (id TEXT PK, created_at TIMESTAMPTZ)

daily_data (
  id BIGSERIAL PK,
  user_id TEXT REFERENCES users,
  date DATE,
  screen_minutes INT,
  unlocks INT,
  top_apps JSONB,          -- [{package, minutes}]
  sleep_minutes INT,
  bedtime TIME,
  wakeup TIME,
  steps INT,
  battery_pct INT,
  created_at TIMESTAMPTZ,
  UNIQUE(user_id, date)
)

checkins (
  id BIGSERIAL PK,
  user_id TEXT REFERENCES users,
  date DATE,
  feel TEXT,               -- OK | MEH | HARD
  tags TEXT[],
  note_text TEXT,
  created_at TIMESTAMPTZ,
  UNIQUE(user_id, date)
)

morning_messages (
  id BIGSERIAL PK,
  user_id TEXT REFERENCES users,
  date DATE,
  content TEXT,
  generated_at TIMESTAMPTZ,
  model TEXT,
  prompt_tokens INT,
  completion_tokens INT,
  latency_ms INT,
  UNIQUE(user_id, date)
)

call_log (
  id BIGSERIAL PK,
  user_id TEXT,
  ts TIMESTAMPTZ,
  session_id TEXT,
  call_type TEXT,          -- llm | tool | background
  component TEXT,          -- llm_morning | llm_checkin | usagestats | health_connect | calendar
  trigger TEXT,            -- user_action | scheduled | followup
  user_visible BOOL,
  result TEXT,
  latency_ms INT,
  INDEX (user_id, ts),
  INDEX (ts)
)

weekly_feedback (
  id BIGSERIAL PK,
  user_id TEXT REFERENCES users,
  week_start DATE,
  content TEXT,
  created_at TIMESTAMPTZ,
  UNIQUE(user_id, week_start)
)
```

## Anti-fraud logging

`internal/analytics/call_log.go`:
- `Logger` struct holds `pgxpool.Pool` + `devUserIDs map[string]struct{}`
- `Log()` is non-blocking: persists async in goroutine, falls back to `slog` on error
- Dev users loaded from `DEV_USER_IDS` env var (comma-separated SHA256 IDs)
- Every LLM call: `call_type=llm`, `user_visible=true` (morning/checkin) or `false` (background generation)
- Background cron jobs: `trigger=scheduled`, must be logged — competition verifies these

## LLM integration

Файл: `internal/llm/morning.go`

**Модель:** `GigaChat-3-Pro` (env: `LLM_MODEL`, default = `GigaChat-3-Pro`)
> ⚠️ Старое имя `GigaChat-Pro` — не работает. Только `GigaChat-3-Pro`.

**Параметры:** MaxTokens=100, Temperature=0.75

**Стоимость:** ~0.065₽/вызов (700 input + 80 output токенов). Подробнее в `LLM_BUDGET.md`.

**System prompt — 5 правил:**
1. Сравнивать с личными средними, не с "нормой здорового человека"
2. Называть приложения по имени (Instagram, YouTube, Telegram) — не "соцсети"
3. Одно конкретное действие, не список
4. Замечать положительные отклонения и хвалить
5. Если сон падает 3+ дней подряд — смягчать тон

**Формат ответа:** 2 предложения, СТРОГО ≤200 символов. Первое — ключевой факт (≤80 символов). Без приветствий.

**Hard cap:** `capMessage(s, 220)` — обрезает по последней точке/!/? в пределах 220 рун.

**Что передаётся в промпт:**
- Личные средние за период (экран, сон, шаги, разблокировки)
- Флаг ⚠️ если сон падает 3+ дней подряд
- 7 дней данных: дата, экран, сон, unlocks, last_unlock, steps, battery_morning (<75%), top-2 приложения
- Дельта wakeup→first_unlock если >30 минут (положительный сигнал — не взял телефон сразу)
- Последний чекин с переводом (ok→"хороший день" и т.д.)

**Package→label резолвер:** 30 приложений (Instagram, YouTube, Telegram, VK, WhatsApp, TikTok, Chrome, Notion, Slack, Teams и др.)

Morning message prompt structure:
```
[Личные средние]
[⚠️ флаг тренда если нужен]
[7 строк данных по дням]
[Последний чекин]
Составь утренний прогноз на сегодня.
```

Tokens: target ~80 completion tokens per call. Log `prompt_tokens + completion_tokens + latency_ms` per call.

## Deployment

See `deploy/` directory:
- `Caddyfile` — reverse proxy localhost:8080, auto TLS, security headers
- `companion.service` — systemd unit, `NoNewPrivileges`, `ProtectSystem=strict`
- `setup.sh` — server bootstrap (PostgreSQL, Caddy, ufw)
- `deploy.sh` — cross-compile + scp + migrate + restart

Environment file: `/opt/companion/.env` on server (gitignored, never committed).

## TODO — next backend steps

1. Implement handler stubs (currently placeholders)
2. LLM morning generation cron (4:10 daily)
3. Retrofit-compatible DTOs aligned with Android models
4. Push notification trigger (FCM — needs Firebase project)
5. 10 RPS stress test (wrk or k6)
6. Metrics endpoint or Prometheus scrape for DAU/TPM
