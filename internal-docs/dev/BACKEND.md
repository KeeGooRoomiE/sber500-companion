# Backend — developer notes

## Stack

- Go 1.22+
- Router: `go-chi/chi` v5
- DB driver: `jackc/pgx/v5` with connection pool
- LLM client: `sashabaranov/go-openai` (OpenAI-compatible, BaseURL overridden to cloud.ru)
- Logging: `log/slog` structured JSON
- Migration: plain SQL files, applied via `migrate` CLI or manually

## Endpoints (актуально)

```
POST /api/v1/register        регистрация устройства (публичный, 60/ч на IP)
                             тело (всё необязательно): is_dev, device_key, source (slug канала: rustore, yandex_direct, telegram_ads, organic; [a-z0-9_.-], ≤32), campaign (≤64) — пишутся в users.source/campaign только при первой регистрации устройства
POST /api/v1/data/passive    дневной снапшот (usage + sleep + steps + battery + почасовки)
POST /api/v1/checkin         feel: ok|meh|hard, tags[], note?
GET  /api/v1/morning         утренний прогноз (пре-ген ночью или on-demand)
POST /api/v1/ping            «приложение открыто» (DAU)
GET/PUT /api/v1/profile      контекст о человеке
GET  /api/v1/history         14 дней прогнозов (панели Home)
POST /api/v1/review/day      разбор дня
POST /api/v1/review/week     итоги недели
POST /api/v1/followup        уточнение под инсайтом (follow-up чипы)
POST /api/v1/insight         5 кейсов: midday/stat/retro/tag/question
GET/POST /api/v1/explore     «Хочу ещё» (состояние + ответ)
POST /api/v1/feedback        hit/miss по прогнозу/разбору
POST /api/v1/event           продуктовые события
GET  /api/v1/version         последняя версия APK
GET  /api/v1/metrics         публичные агрегаты (без auth, CORS: *)
GET  /health                 liveness
```

Все кроме публичных требуют `X-User-ID` (SHA256 device fingerprint). Админ-эндпоинты
(`/admin/prompts/*`) слушают локальный порт, наружу не торчат. Полная таблица — `ARCHITECTURE.md`.

## Database schema

> ⚠️ Ниже — ранняя схема `001_init`. С тех пор миграции доросли до **014**; добавились таблицы
> `reviews`, `feedback`, `explore_answers`, `user_activity`, `heartbeats`, `prompts`
> (+ `002_fcm_token` — остаток пуш-инфры, мёртвая). Полный актуальный список — `ARCHITECTURE.md`.

```sql
-- 001_init.sql (see backend/migrations/)

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
  call_type TEXT,          -- llm | tool
  component TEXT,          -- llm_morning | llm_day_review | llm_weekly | llm_explore | llm_followup | llm_insight | usagestats | checkin | scenario_completed | ...
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

Клиент: `internal/llm/` (`sashabaranov/go-openai`, BaseURL override). Генераторы — в
`internal/forecast/` (morning, review day/week, explore, insight, followup).

**Провайдер/модель:** cloud.ru Foundation Models, `gigachat-3-pro` (env `LLM_MODEL`).
Эндпоинт `https://shared1.multitool.works:4000/v1` (прокси к моделям cloud.ru).

**Промты — в БД (`prompts`), не в коде.** Версии + hot-swap через админ-API (`deploy/prompt.sh`),
плюс встроенные дефолты в бинарнике. Имена слотов: `morning_system`, `day_review_system`,
`weekly_system`, `explore_system`.
> ⚠️ `prompt.sh` по умолчанию пишет в `morning_system` — для остальных **обязательно** `PROMPT=<name>`.

**Тон — НАБЛЮДАТЕЛЬ, не советчик.** Ключевое правило всех промтов: НИКОГДА советов, инструкций,
повелительного наклонения — только наблюдения и объяснения. Причина (кастдев): прескриптивный
тон → вина → отток, особенно у не-трекеров. Сравнение — с ЛИЧНОЙ нормой человека; приложения —
по имени; причинность через предыдущие дни (накопленный недосып/экран).

**Форматы по типам:**
- Утро (`morning_system`): ровно 2 предложения, ≤220 символов, «что было вчера и что из этого
  следует сегодня» как вероятность. `capMessage()` — hard cap по последней точке.
- Разбор дня (`day_review_system`): 3–4 предложения, ≤500; хороший день = один механизм, не свалка
  метрик (good-day фикс); причина в предыдущих днях.
- Неделя (`weekly_system`): 4–5 предложений, ≤650.
- Explore/insight/followup — короткие, по данным, без советов (см. `SIGNALS.md`).

**Что передаётся:** личные средние, флаг падения сна 3+ дней, данные по дням (экран/сон/unlocks/
last_unlock/steps/battery/top-приложения), дельта wakeup→first_unlock, последний чек-ин, профиль.
Каждый вызов логируется в `call_log` (`prompt_tokens + completion_tokens + latency_ms`, модель).

Стоимость — см. `LLM_BUDGET.md`.

## Deployment

See `deploy/` directory:
- `Caddyfile` — reverse proxy localhost:8080, auto TLS, security headers
- `companion.service` — systemd unit, `NoNewPrivileges`, `ProtectSystem=strict`
- `setup.sh` — server bootstrap (PostgreSQL, Caddy, ufw)
- `deploy.sh` — cross-compile + scp + migrate + restart

Environment file: `/opt/companion/.env` on server (gitignored, never committed).

## Планировщик утра

По умолчанию — один проход в 04:10 (APP_TZ): `UsersToGenerate` → пул из 4 воркеров с паузой 0.2–1.5 с
между постановками → 2 повтора через 2 и 4 минуты. Для `trigger=scheduled` `Ensure` обходит паузу
`retryAfter` (30 мин), остаётся только лимит `maxAttempts=3`; для запросов пользователя пауза прежняя.
(Раньше повторы планировщика попадали в это окно и ничего не делали, см. LLM_AUTONOMOUS_LOG, 05.10.)

**Режим слотов** (`MORNING_MODE=slots`, по умолчанию выключен; код в `internal/scheduler/slots.go`,
`slot_loop.go`):
- Привычное время человека — медиана минуты суток первого `morning_api`/`user_action` за 14 дней
  (≥5 дней, иначе 04:10). Цель генерации = привычное − `LEAD` (45 мин), в окне 00:30–07:00.
  Пересчёт раз в сутки, в памяти (`MorningRepo.HabitualOpenMinutes`).
- Тик каждые `SLOT` (5 мин) с 00:30 до 08:00: люди, чей слот наступил и у кого нет текста, не более
  `PACK_SIZE` (100), раньше — те, у кого цель раньше; остаток уходит на следующий тик. Повторы внутри
  слота через 90 с и 3 мин.
- В 04:10 — контрольный проход: все наступившие, без лимита пакета, паузы 2 и 4 мин.
- Фоновые вызовы пишут в `call_log.session_id` значение `sched:<дата>:<ЧЧММ>` (обычный режим — `…:0410`).
- Включить: `MORNING_MODE=slots` в `/opt/companion/.env` + `systemctl restart companion`
  (опционально `PACK_SIZE`, `SLOT`, `LEAD`). Откат: убрать строку или поставить любое другое значение + рестарт.
- Риск: `DailyCollectWorker` шлёт «вчера целиком» раз в 12 ч (WorkManager, без привязки ко времени),
  так что при ранней генерации (02:00–04:00 МСК) вчерашний день может быть неполным, если последний
  запуск воркера был до вечера. Не проверено на данных; смотреть долю неполных «вчера» перед включением.

## Статус (всё из раннего TODO — сделано)

Хендлеры, планировщик 04:10, DTO, метрики, 10 RPS (LOADTEST.md) — готово и в проде. FCM удалён
2026-10-02 (доставка на AlarmManager, NOTIFICATIONS.md).

Открытое / беклог:
- Выгрузка статистики через `call_log` (методы на бэке) — см. `STATS.md` (пока доступ через psql).
- Прод-ключ cloud.ru (выделенный) — сейчас общий прокси; его нестабильность = источник скачков
  error_rate в утреннем батче. Митигатор (keep-alive + размазать батч) — в беклоге.
- Чат (следующий месяц) — поднимет Calls/DAU.
