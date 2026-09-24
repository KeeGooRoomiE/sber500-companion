# Android — developer notes

## Permissions strategy

| Permission | When requested | Why deferred |
|------------|---------------|--------------|
| `POST_NOTIFICATIONS` | Onboarding Day 1 | Core UX |
| `RECEIVE_BOOT_COMPLETED` | Auto (manifest) | WorkManager re-schedule on reboot |
| `READ_CALENDAR` | Onboarding Day 1 | Optional context for forecast |
| `PACKAGE_USAGE_STATS` | Day 3–4 (after value shown) | System-level, requires AppOps grant — show value first |
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

- `HomeViewModel` (`ui/home/HomeViewModel.kt`) — `@HiltViewModel`, `StateFlow<HomeUiState>`
- `init { if (BuildConfig.DEBUG) loadMock() else loadReal() }` — mock в debug без бэкенда
- Mock данные: screenMin=214, sleepMin=382, unlocks=47, morning message заглушка
- `collectAsStateWithLifecycle()` в composable — lifecycle-aware
- `onCheckIn(feel)` — обновляет state + `repository.postCheckIn()` в coroutine

**HomeUiState:**
```kotlin
data class HomeUiState(
    val morningMessage: String? = null,
    val screenMin: Int? = null,
    val sleepMin: Int? = null,
    val unlocks: Int? = null,
    val checkedIn: DayFeel? = null,
    val isLoading: Boolean = true,
)
```

### OnboardingScreen

- 3 шага: Знакомство → Health Connect → Уведомления
- HC: если `SDK_AVAILABLE` — launcher, иначе `hcUnavailable=true`, `step++`
- Warning Surface на шаге 2 если `hcUnavailable` — "HC недоступен, сон и шаги не соберутся"
- Notifications: `Build.VERSION.SDK_INT >= TIRAMISU` check перед запросом

## Notifications

File: `notifications/NotificationHelper.kt`

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
- При нажатии: cancel уведомления → toast → `repo.postCheckIn()` в coroutine

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
3. **PACKAGE_USAGE_STATS permission** — запрос на Day 3–4 онбординга (сейчас деферировано)
4. **FCM** — push-уведомления с сервера (сейчас local WorkManager scheduler)

## Hilt setup

- `@HiltAndroidApp` on `CompanionApp`
- `@AndroidEntryPoint` on `MainActivity`
- Workers use `@HiltWorker` + `@AssistedInject` — requires `HiltWorkerFactory` registered via `WorkManager.initialize()` or `Configuration.Provider`
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
