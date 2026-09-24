# Architecture

## System overview

```
Android App
  ├── UI (Compose)
  ├── WorkManager (batch, 4:00 night collect)
  │     ├── UsageStatsCollector   → screen time, unlocks, top apps
  │     ├── HealthConnectCollector → sleep, steps
  │     └── BatteryLevel
  ├── Evening check-in notification (3-button: OK / MEH / HARD)
  └── Retrofit → Backend API

Backend (Go)
  ├── POST /api/v1/daily-data      ← passive data from Android
  ├── POST /api/v1/checkin         ← evening 3-button mood
  ├── GET  /api/v1/morning         ← morning forecast for Android
  ├── GET  /api/v1/weekly          ← weekly summary
  └── Internal jobs (scheduled)
        ├── 4:10 — aggregate DailySnapshot → LLM call → store morning_message
        └── Sunday 20:00 — weekly summary generation

LLM (cloud.ru Foundation Models, OpenAI-compatible)
  └── Morning forecast: system prompt + last 7 DailySnapshots + last checkin → message

GitHub Pages
  └── Landing: APK download + links
```

## Data flow (daily cycle)

```
23:00–23:59  UsageStats window closes (Android collects retroactively on wake)
04:00        WorkManager fires DailyCollectWorker
              → UsageStats (yesterday 00:00–23:59)
              → Health Connect (sleep from 20:00 prev day to 10:00 today)
              → Battery level
              → POST /api/v1/daily-data
04:10        Backend cron: LLM call → morning_message stored
07:40        Android push notification: morning forecast displayed
19:30        Evening check-in push notification (3 buttons from notification action)
             → POST /api/v1/checkin (feel: OK/MEH/HARD)
Sunday 20:00 Weekly summary generated, pushed to user
```

## User identity / privacy

- `user_id = SHA256(device_id + server_salt)` — one-way, rotating salt planned
- No geolocation
- No notification content reading
- No foreground service (WorkManager only — avoids battery complaints)
- Health Connect data stays on device; only aggregates sent (duration, bedtime/wakeup, step count)
- `PACKAGE_USAGE_STATS` requested on Day 3–4 of onboarding (show value first)

## Database schema (PostgreSQL)

```sql
users           (id TEXT PK, created_at)
daily_data      (user_id, date UNIQUE, screen_minutes, unlocks, top_apps jsonb, sleep_minutes, bedtime, wakeup, steps, battery_pct)
checkins        (user_id, date UNIQUE, feel TEXT, tags TEXT[], note_text)
morning_messages(user_id, date UNIQUE, content TEXT, generated_at, model, prompt_tokens, completion_tokens, latency_ms)
call_log        (id, user_id, ts, session_id, call_type, component, trigger, user_visible, result TEXT, latency_ms)
weekly_feedback (user_id, week_start UNIQUE, content TEXT)
```

## Android module structure

```
ru.keegoo.companion
  ├── CompanionApp.kt          (Hilt application)
  ├── ui/
  │    ├── MainActivity.kt
  │    ├── NavHost.kt
  │    ├── HomeScreen.kt
  │    ├── onboarding/OnboardingScreen.kt
  │    └── theme/Theme.kt
  ├── domain/model/
  │    ├── DailySnapshot.kt    (UsageSnapshot, SleepSnapshot, BatterySnapshot)
  │    └── CheckIn.kt          (DayFeel enum: OK/MEH/HARD)
  ├── data/collector/
  │    ├── UsageStatsCollector.kt
  │    └── HealthConnectCollector.kt
  └── work/
       └── DailyCollectWorker.kt   (PeriodicWork 12h, CoroutineWorker+Hilt)
```

## Backend module structure

```
backend/
  ├── cmd/server/main.go
  ├── internal/
  │    ├── analytics/call_log.go   (anti-fraud logging, dev-user filter)
  │    ├── handler/                (chi routes — stub implementations)
  │    └── llm/                    (cloud.ru via go-openai BaseURL override)
  ├── migrations/001_init.sql
  └── .env.example
```

## LLM integration

- Library: `sashabaranov/go-openai` — works with any OpenAI-compatible API via `BaseURL` override
- Endpoint: `https://api.cloud.ru/v1`
- Budget: 50k₽ for Stage 1 (first ~2 weeks)
- Anti-fraud: every call logged to `call_log` with `call_type=llm`, `user_visible=true/false`
- Dev user IDs excluded from metrics via `DEV_USER_IDS` env var

## Competition metrics compliance

The competition verifies:
- **Tool calls / model queries + DAU** — every backend LLM call counted
- **TPM, TPS, token count** — logged per call in `morning_messages` table
- **LLM-cost / DAU** — derivable from token counts + pricing
- Background calls (`trigger=scheduled`) logged with `user_visible=false` to prove they're legitimate
