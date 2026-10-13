# Architecture

Актуально на 2026-10-13. Обзор системы; детали подсистем — в соседних доках
(`BACKEND.md`, `ANDROID.md`, `SIGNALS.md`, `NOTIFICATIONS.md`, `STATS.md`).

## System overview

```
Android App (Kotlin / Compose)
  ├── UI (Compose, MVVM) — онбординг → Home → профиль (орб), motion/Backdrop
  ├── WorkManager
  │     ├── DailyCollectWorker      — 12h periodic + runNowAndWait: usage/sleep/steps/battery → сервер
  │     ├── ReviewNotificationWorker — после чек-ина: снапшот → /review/day → уведомление «разбор дня»
  │     └── UpdateCheckWorker        — 12h: проверка новой версии APK
  ├── Collectors: UsageStats (экран/разблокировки/приложения), HealthConnect (сон/шаги), Battery
  ├── Notifications (ЛОКАЛЬНЫЕ, FCM удалён 2026-10-02)
  │     каналы: ch_morning, ch_checkin, ch_review, ch_update
  │     доставка: NotificationScheduler + AlarmManager (точный будильник на первую разблокировку)
  ├── AppMetrica SDK — продуктовые события (онбординг, экраны), геолокация отключена
  └── Retrofit → Backend API

Backend (Go: chi, pgx/v5, slog, go-openai)
  ├── HTTP API /api/v1/* (см. ниже)
  ├── Планировщик 04:10 MSK — генерация утренних прогнозов для всех (4 воркера, ретрай)
  ├── Prompts store — версии промтов в БД + админ-API (локальный порт), hot-swap без редеплоя
  ├── call_log — anti-fraud: каждый LLM/tool-вызов логируется
  └── LLM-клиент → cloud.ru Foundation Models

LLM: cloud.ru Foundation Models, модель gigachat-3-pro
  └── Эндпоинт: https://shared1.multitool.works:4000/v1 (OpenAI-совместимый прокси к моделям cloud.ru)

PostgreSQL (на VPS) — вся серверная персистентность (см. «Схема БД»)

Web (GitHub Pages)
  ├── index.html   — лендинг (APK, описание)
  ├── metrics.html — живые метрики (тянет GET /api/v1/metrics)
  └── privacy.html — политика конфиденциальности
```

## HTTP API (`/api/v1`, auth по заголовку X-User-ID кроме публичных)

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/register` | регистрация устройства (60/ч на IP) |
| POST | `/data/passive` | дневной снапшот (экран, разблокировки, топ-приложений, сон, шаги, батарея, почасовки) |
| POST | `/checkin` | вечерняя отметка (ok/meh/hard + теги) |
| GET | `/morning` | утренний прогноз (пре-ген ночью или on-demand) |
| POST | `/ping` | «приложение открыто» (DAU) |
| GET/PUT | `/profile` | контекст о человеке |
| GET | `/history` | 14 дней утренних прогнозов (для панелей Home) |
| POST | `/review/day` | разбор дня (`day_review_system`) |
| POST | `/review/week` | итоги недели (`weekly_system`) |
| POST | `/followup` | уточнение под инсайтом (follow-up чипы) |
| POST | `/insight` | 5 кейсов: midday/stat/retro/tag/question |
| GET/POST | `/explore` | «Хочу ещё» — состояние и ответ на вопрос (`explore_system`) |
| POST | `/feedback` | «Совпало / Не совсем» по прогнозу/разбору |
| POST | `/event` | продуктовые события (напр. `notification_opened:*`) |
| GET | `/version` | последняя версия APK (для in-app «обновиться») |
| GET | `/metrics` | **публичные агрегаты** (без auth, CORS) — для `metrics.html` |
| GET | `/health` | healthcheck |

Админ (локальный порт на сервере, наружу не торчит): `/admin/prompts/*` — list/push/activate/reset
промтов (`deploy/prompt.sh`), `/admin/push` (заглушка), `errors_reset`.

## Схема БД (PostgreSQL, миграции 001–014)

| Таблица | Назначение |
|---|---|
| `users` | устройства (id = SHA256(ANDROID_ID+salt)), `is_dev`, created_at |
| `daily_data` | дневной снапшот на дату (экран, разблокировки, top_apps jsonb, сон, шаги, батарея, почасовки) |
| `checkins` | вечерние отметки (feel, tags, note) |
| `morning_messages` | утренние прогнозы + токены/латентность/модель |
| `reviews` | кэш разборов (day/week; составной kind для follow-up) |
| `feedback` | hit/miss по прогнозам и разборам |
| `explore_answers` | ответы «Хочу ещё» (кэш на день) |
| `call_log` | **anti-fraud**: каждый вызов (call_type=llm/tool, component, trigger, user_visible, result, latency_ms) |
| `user_activity` | активность по дням (DAU/ретеншн) |
| `heartbeats` | аптайм-пинги |
| `prompts` | версии системных промтов (hot-swap) |
| `weekly_feedback` | legacy (ранняя схема) |

(Миграция `004_device_tokens` — остаток пуш-инфры; FCM удалён, таблица мёртвая.)

## Поток данных (суточный цикл)

```
ночь        DailyCollectWorker собирает вчера (полностью) + сегодня (пока) → POST /data/passive
04:10 MSK   Планировщик: LLM-вызов → morning_message сохранён (trigger=scheduled, фон)
утро        Первая разблокировка → локальное уведомление с прогнозом (AlarmManager)
вечер       Чек-ин из уведомления (3 кнопки) → POST /checkin
            → ReviewNotificationWorker: снапшот сегодня → POST /review/day → уведомление «разбор дня»
по запросу  Разбор дня/недели, follow-up, «Хочу ещё», инсайты — user_action LLM-вызовы
```

Home читает данные **на устройстве** (локальный прогноз `LocalForecast`, без бэкенда); бэкенд нужен
для LLM-текста и серверных метрик. Подробности — `HOME_UX.md`.

## Идентичность / приватность

- `user_id = SHA256(ANDROID_ID + salt)` — односторонний, без имени/телефона/почты.
- Нет геолокации (в т.ч. отключена в AppMetrica), не читается содержимое уведомлений/переписок.
- Нет foreground-сервиса (только WorkManager/AlarmManager) — меньше претензий к батарее.
- Health Connect — только чтение сна/шагов, на устройстве; на сервер уходят агрегаты.
- `PACKAGE_USAGE_STATS` запрашивается в онбординге с объяснением ценности.
- В cloud.ru уходят **посчитанные показатели** (числа + имена приложений), без идентификатора устройства.

## LLM-интеграция

- Библиотека `sashabaranov/go-openai` (OpenAI-совместимость через BaseURL override).
- Провайдер: **cloud.ru Foundation Models**, модель **gigachat-3-pro**.
- Эндпоинт (прод): `https://shared1.multitool.works:4000/v1` — прокси к моделям cloud.ru.
- Промты — в БД (`prompts`) с версиями и hot-swap; имена: `morning_system`, `day_review_system`,
  `weekly_system`, `explore_system` (+ встроенные дефолты в бинарнике).
- Anti-fraud: каждый вызов в `call_log`; dev-пользователи исключаются из метрик (`DEV_USER_IDS`,
  `is_dev`, префикс `mock_`).

## Соответствие метрикам конкурса

- **Calls/DAU** — считается по `call_log` **только по LLM-вызовам** (`call_type='llm'`, без admin/dev);
  tool-вызовы (usagestats/checkin/scenario) в KPI не идут.
- `scenario_completed` — утро получено + вечерний чек-ин в один день.
- Фоновые вызовы (`trigger=scheduled`) логируются отдельно (`user_visible=false`) — честность фона.
- DAU/ретеншн — из `user_activity`; дубль-источник AppMetrica (продуктовая аналитика).
- Детали доступа к статистике и выгрузки — `STATS.md`.
