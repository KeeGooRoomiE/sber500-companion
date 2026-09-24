# Android — developer notes

## Permissions strategy

| Permission | When requested | Why deferred |
|------------|---------------|--------------|
| `POST_NOTIFICATIONS` | Onboarding Day 1 | Core UX |
| `RECEIVE_BOOT_COMPLETED` | Auto (manifest) | WorkManager re-schedule on reboot |
| `READ_CALENDAR` | Onboarding Day 1 | Optional context for forecast |
| `PACKAGE_USAGE_STATS` | Onboarding step 2 | Without it there's no screen time/unlocks at all — the core of the forecast |
| `ACCESS_NETWORK_STATE` | Auto (manifest) | Profile question «это домашний Wi‑Fi?» (only Wi‑Fi yes/no, no SSID) |
| Health Connect | Day 3–4 | Requires separate Health Connect app, sensitive |

`PACKAGE_USAGE_STATS` is `ProtectedPermissions` — user must grant via Settings → Special app access → Usage access. The app must check `hasPermission()` before every collection and prompt gracefully if missing.

## Data collection — UsageStatsCollector

File: `data/collector/UsageStatsCollector.kt`

- Uses `UsageStatsManager.queryEvents(start, end)` — event-by-event parsing
- **Screen time**: `SCREEN_INTERACTIVE` starts timer (subtract timestamp), `SCREEN_NON_INTERACTIVE` stops (add timestamp). Handles day-boundary edge case (`if screenOnMs < 0: screenOnMs += dayEndMs`).
- **Unlocks**: count of `KEYGUARD_HIDDEN` events; track first/last timestamps
- **App time**: `ACTIVITY_RESUMED` → start map; `ACTIVITY_PAUSED` → compute delta; apps with < 1 min filtered
- Top 10 apps by foreground time
- Returns `null` if permission missing (caller should retry or prompt)

## Data collection — HealthConnectCollector

File: `data/collector/HealthConnectCollector.kt`

- Uses `HealthConnectClient.getOrCreate(context)` — lazy init
- `isAvailable()` checks `SDK_AVAILABLE` before any call
- **Sleep**: reads `SleepSessionRecord` for [yesterday 20:00 → today 10:00]; picks longest session
- **Steps**: reads `StepsRecord`, sums `count`
- All methods return `null`/`0` on exception — graceful degradation, no crash

## WorkManager — DailyCollectWorker

File: `work/DailyCollectWorker.kt`

- `@HiltWorker` with `@AssistedInject` — Hilt-provided dependencies
- `CoroutineWorker` — coroutines first-class, `doWork()` is `suspend`
- Interval: every 12h, `setRequiresBatteryNotLow(true)`
- `ExistingPeriodicWorkPolicy.KEEP` — no duplicate jobs on reschedule
- Returns `Result.retry()` if UsageStats permission missing or backend unreachable
- **Полный пайплайн**: usage → sleep → steps → battery → `repository.postDailySnapshot()` → `Result.retry()` on failure
- `steps`: `healthConnect.collectSteps(dayStart, dayEnd)` — передаётся в `DailySnapshot.steps`

## UI Architecture

### HomeScreen + HomeViewModel

Подробно — в [HOME_UX.md](HOME_UX.md). Коротко:

- `HomeViewModel` — `@HiltViewModel`, `StateFlow<HomeUiState>`, **моков нет**: `refresh()` на старте и на каждом `ON_RESUME` читает `TodayRepository.load()` (данные с устройства, без бэка)
- Текст прогноза — `buildLocalForecast()` (правила, без LLM); факты для «Почему такой прогноз»
- Чек-ин и ответы профиля — DataStore (`data/prefs/AppPrefs.kt`); бэк получает чек-ин дополнительно
- Файлы: `ui/HomeScreen.kt` (экран, карточка прогноза, отладка), `ui/home/HomeStats.kt` (плитки, деталка), `ui/home/HomeCheckIn.kt` (чек-ин)

### OnboardingScreen

- 4 шага: Знакомство → Доступ к данным (usage access, затем Health Connect) → Уведомления → «Смотрю твои данные»
- Usage access: `Settings.ACTION_USAGE_ACCESS_SETTINGS`, проверка через AppOps (`Context.hasUsageAccess()`)
- HC недоступен → мягкая подсказка, без «ошибки»
- Показывается один раз (флаг `onboarded` в DataStore)

### ProfileScreen

- Открывается по нажатию на орб. Аккордеон вопросов (`domain/profile/ProfileQuestions.kt`), ответы в DataStore
- Время уведомлений из ответов переставляет расписание

## Notifications

File: `notifications/NotificationHelper.kt`

**Расписание:** `notifications/NotificationScheduler.kt` — `PeriodicWorkRequest` 24 ч до 07:40 / 20:30 (или время из профиля). `ReminderWorker` показывает утреннее (12 вариантов `MorningCopies`) или вечернее (5 вариантов `EveningCopies`, пропускается, если чек-ин уже есть). Запускается в `CompanionApp.onCreate()` с `KEEP`.

**Каналы:**
- `ch_morning` (IMPORTANCE_DEFAULT) — утренний прогноз
- `ch_checkin` (IMPORTANCE_DEFAULT) — вечерний чекин

**Лимиты:**
- Collapsed preview: `contentText` = первое предложение, до 80 символов
- Expanded BigText: `bigText` = полный текст, ≤220 символов (Android BigTextStyle, ~5 строк × 45 символов)
- LLM MaxTokens=100, hard cap в `capMessage()` по последней точке, ≤220 рун

**CheckInReceiver** (`notifications/CheckInReceiver.kt`):
- `@EntryPoint @InstallIn(SingletonComponent::class)` + `EntryPointAccessors` — Hilt в BroadcastReceiver
- Action buttons: `ru.keegoo.companion.ACTION_CHECKIN` + `EXTRA_FEEL` (OK/MEH/HARD)
- При нажатии: cancel уведомления → toast → `saveCheckIn()` локально → `repo.postCheckIn()` (через `goAsync()`)

## Data layer

### API

`CompanionApi.kt` (Retrofit interface):
- `POST /api/v1/data/passive` — `PassiveDataRequest`
- `POST /api/v1/checkin` — `CheckInRequest`
- `GET /api/v1/morning` — `MorningMessageResponse`

`PassiveDataRequest` fields: date, screen_min, unlocks, first_unlock, last_unlock, top_apps[], sleep_min, bedtime, wakeup, **steps**, battery_morning, fcm_token (reserved, null)

**X-User-ID**: OkHttp interceptor добавляет SHA256(ANDROID_ID+"sber500-companion-v1") в каждый запрос

### BuildConfig URLs

- Debug: `http://10.0.2.2:8080/` (эмулятор localhost)
- Release: `https://YOUR_DOMAIN/` → **заменить после VPS**

## TODO — оставшееся

1. **AppMetrica**: добавить ключ (ждём аккаунт) — `meta-data` в AndroidManifest + `AppMetrica.activate()` в CompanionApp
2. **Glance widget** — виджет на рабочий стол (утренний прогноз)
3. **FCM** — push-уведомления с сервера (сейчас локальный `NotificationScheduler`)
4. План по экранам — в [HOME_UX.md](HOME_UX.md#план)

## Hilt setup

- `@HiltAndroidApp` on `CompanionApp`
- `@AndroidEntryPoint` on `MainActivity`
- Workers use `@HiltWorker` + `@AssistedInject` — `CompanionApp` implements `Configuration.Provider` with `HiltWorkerFactory`; the default `WorkManagerInitializer` is removed in the manifest
- `@ApplicationContext` injected into collectors — singleton scope

## minSdk = 29 rationale

Android 10+ required for:
- `UsageEvents.Event.KEYGUARD_HIDDEN` (API 26 but reliable from 29)
- Health Connect min API 26, but better support from 29
- WorkManager coroutines stable from 29
- Covers 94%+ of active Android devices

## Health Connect notes

- Requires Health Connect app installed on device (pre-installed on Android 14+, separate install on 13)
- Manifest needs `<activity>` with `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent filter
- Alpha SDK (1.1.0-alpha11) — may break on SDK upgrades, pin version
- No background reads without permission — must be granted explicitly
