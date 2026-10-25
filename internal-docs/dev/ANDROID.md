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

**Расписание:** `notifications/NotificationScheduler.kt` — на **AlarmManager** (точный будильник,
переживает Doze; переехали с WorkManager в 0.9.2). Утреннее привязано к первой разблокировке,
вечернее — на время из профиля; вечернее пропускается, если чек-ин уже есть. Тексты: `MorningCopies`
(12) / `EveningCopies` (5). Будильники взводятся заново после перезагрузки (`RECEIVE_BOOT_COMPLETED`).

**Каналы:**
- `ch_morning` (IMPORTANCE_DEFAULT) — утренний прогноз
- `ch_checkin` (IMPORTANCE_DEFAULT) — вечерний чекин
- `ch_review` (IMPORTANCE_DEFAULT) — разбор дня (после чек-ина, полный текст в BigText)
- `ch_update` (IMPORTANCE_LOW) — «можно обновиться» (новая версия APK)

**Лимиты:**
- Collapsed preview: `contentText` = первое предложение, до 80 символов
- Expanded BigText: `bigText` = полный текст, ≤220 символов (Android BigTextStyle, ~5 строк × 45 символов)
- LLM MaxTokens=100, hard cap в `capMessage()` по последней точке, ≤220 рун

**CheckInReceiver** (`notifications/CheckInReceiver.kt`):
- `@EntryPoint @InstallIn(SingletonComponent::class)` + `EntryPointAccessors` — Hilt в BroadcastReceiver
- Action buttons: `ru.keegoo.companion.ACTION_CHECKIN` + `EXTRA_FEEL` (OK/MEH/HARD)
- При нажатии: cancel уведомления → toast → `saveCheckIn()` локально → `repo.postCheckIn()` (через `goAsync()`)
- После чек-ина (и из уведомления, и in-app) ставит `ReviewNotificationWorker` → снапшот сегодня
  (`DailyCollectWorker.runNowAndWait`) → `POST /review/day` → уведомление «разбор дня» (канал `ch_review`,
  дедуп раз в день). Прочие воркеры: `DailyCollectWorker` (12h), `UpdateCheckWorker` (12h, новая версия APK).

## Data layer

### API

`CompanionApi.kt` (Retrofit interface) — полный набор:
- `POST /data/passive`, `POST /checkin`, `GET /morning`, `POST /ping`, `GET/PUT /profile`
- `GET /history`, `POST /review/day`, `POST /review/week`, `POST /followup`, `POST /insight`
- `GET/POST /explore`, `POST /feedback`, `POST /event`, `GET /version`, `POST /register`

`PassiveDataRequest` fields: date, screen_min, unlocks, first_unlock, last_unlock, top_apps[], sleep_min, bedtime, wakeup, **steps**, battery_morning, hourly_unlocks/screen/steps

**X-User-ID**: OkHttp interceptor добавляет SHA256(ANDROID_ID+"sber500-companion-v1") в каждый запрос

### BuildConfig URLs

- Debug: `http://10.0.2.2:8080/` (эмулятор localhost)
- Release: `https://YOUR_DOMAIN/` → **заменить после VPS**

## TODO — оставшееся

1. ~~AppMetrica~~ — ✅ активна (ключ из CI-секрета, `AppMetrica.activate()` в `CompanionApp`,
   геолокация отключена; события — `analytics/Events.kt`)
2. **Glance widget** — виджет на рабочий стол (утренний прогноз) — не начато
3. ~~FCM~~ — удалён 2026-10-02. Доставка — `NotificationScheduler` на AlarmManager, см. NOTIFICATIONS.md
4. Бэклог из запросов/дизайна — ниже; план по экранам — [HOME_UX.md](HOME_UX.md#план)

**Фичи 1.0.0 (в проде):** разбор дня после чек-ина (`ReviewNotificationWorker`), follow-up чипы под
разборами, «пять инсайтов» (`/insight`), «вопрос дня», «Твой профиль», «Хочу ещё». Устройство — `SIGNALS.md`.

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

---

## Бэклог Android (из пользовательских запросов, 2026-10-08)

Не начато. Записано, чтобы не потерялось: текущая работа идёт по бэкенду и сигналам.

### 1. Третья плитка не видна на старых устройствах

Запрос: «у кого телефон старый — не видят третью плашку „телефон без экрана“, видят только
разблокировки и экранное время».

Третья плитка — это сон, и она пустая не из-за возраста устройства, а из-за отсутствия
Health Connect: на Android 13 и ниже это отдельное приложение, которое надо устанавливать, а
на старых телефонах его часто просто нет. То есть симптом описан верно, причина другая.

Замер на 2026-10-08: сон есть у 11% пользователей (6 из 54), шаги — у 46%.

Что делать — решить: либо вернуть в плитку device-side фоллбэк («самая длинная пауза без
экрана»), который уже считается локально в `TodayRepository`, но на сервер не уходит; либо
показывать в плитке явное объяснение, почему она пустая, со ссылкой на выдачу разрешения.
Второе честнее, первое полезнее. Скорее всего нужно и то и другое.

### 2. Детальный разбор экранного времени по приложениям

Запрос: более подробная раскладка экранного времени — связано с панелью почасовой графики.

**Формулировка самих пользователей (повторялась на интервью):** «можно увидеть экран с
пользованием приложений?» Спрашивают так, будто такого экрана нет нигде — хотя на Android он
есть во встроенном «Цифровом благополучии». То есть люди либо не знают о нём, либо не находят:
на iOS Screen Time на виду, а здесь закопано в настройках.

Это не только бэклог-задача, но и позиционирование: мы конкурируем не с функцией, а с её
недоступностью. Один из опрошенных сказал прямо — если не ответить «чем лучше встроенного», не
поймёт, зачем ставить отдельное приложение.

Сырьё уже отправляется: `top_apps[]` (package + minutes, заполнено на 85%) и
`hourly_screen[24]` (100%). То есть данные для такой панели есть, нужна только UI-часть.

### 3. Ретроспектива статистики

Запрос: возможность смотреть статистику за прошлые периоды, а не только текущий день и неделю.

На сервере история есть (`daily_data` хранит всё), `GET /history` отдаёт прогнозы за две
недели. Для ретроспективы по цифрам понадобится либо расширить существующий эндпоинт, либо
новый — это единственный пункт из трёх, которому нужна правка бэкенда.

### 4. Онбординг: превью ценности на экране разрешений («проявляющаяся фраза» + live-peek)

Идея (2026-10-09): вместо таблицы «до/после» на экране запроса разрешений — одно
предложение-пример, фрагменты которого заблюрены и **раскрываются по мере выдачи доступов**.
До/после живёт внутри одной фразы, а не в двух колонках — так экран не захламляется.

Механика:
- **Модульно**: один доступ = один смысловой фрагмент фразы (разблокировки / сон / шаги).
- **Live-peek**: при выдаче Usage Access телефон мгновенно читает `UsageStats` и подставляет
  **реальное** число («сегодня 47 разблокировок») — это буквально запрос #18 «дайте подглядеть».
- **Честность**: сон/шаги на онбординге показываем примером с тегом «пример» + «наполнится за
  пару дней» — персональных данных ещё нет, обещать нельзя (иначе риск #2/#7 «прогноз не в точку
  → удалю»).
- **Шкала точности** вместо списка фич — один растущий индикатор (прямой запрос #13).
- **Негатив по каждому доступу** — «что я НЕ вижу: что на экране / геолокацию» (#17, #22).
- Разрешения не форсятся, мягкий выход «можно позже» (#18).

Кликабельный прототип (не в `web/`, в Pages не едет): `internal-docs/mockups/onboarding-reveal.html`.
Открытые места для переноса в Compose: веса точности — плейсхолдер, привязать к числу сигналов,
что открывает каждый доступ; «читаю…» — имитация, в проде мгновенное чтение `UsageStats`.
Полностью клиентская правка (онбординг), бэку ничего не нужно.

---

## Разбор дизайнера (2026-10-09)

Широкий разбор экранов от дизайнера. Почти всё — клиентский UI (Compose), бэку не нужно.
Не начато, записано для будущей работы. Сгруппировано по экранам.

### Главный (Home)

| # | Замечание | Тип | Заметка |
|---|---|---|---|
| 1 | «Почему такой прогноз?» — лишний заголовок поверх уже существующего, разные размеры/КАПС | copy + layout | Убрать дублирующий заголовок раскрытия. Перекликается с недавней перестройкой `MorningCard` (раздел «Почему такой прогноз ▾») — заголовков стало два |
| 2 | Точка над цифрами в блоках использования — убрать | style | Мелочь |
| 3 | Шрифты мелкие, «Итоги недели» трудно читать | typography/a11y | Пересмотреть типографическую шкалу, особенно плотные блоки |
| 4 | Стандартные эмодзи удешевляют — заменить на иконки другого типа | icon set | **Сильно совпадает с кастдевом** (#16/#17/#20 — против эмодзи и зазывания). Касается и чек-ина (😊😐😮‍💨), и прочего. Единый кастомный набор иконок |
| 5 | «Итоги недели»: больше отступ от текста до разделителя | spacing | |
| 6 | Чипы-причины понравились + добавить «Другое» | **валидация + доработка** | Дизайнер подтвердил V2 cause-chips из обсуждения чек-ина. Добавить фоллбэк «Другое» на случай, если причины нет в списке |
| 7 | Зелёный смущает, «не к месту» — сменить цвет | theming | Зелёный сейчас = позитивные сигналы. Пересмотреть палитру; `--ok` трогает и мок онбординга |

### Онбординг

| # | Замечание | Тип | Заметка |
|---|---|---|---|
| 1 | Анимация формы (орб) нравится, трансформация между экранами «крутая» | ✅ оставить | |
| 2 | Кнопка «Пропустить» мелкая — шрифт как внутри большой фиолетовой кнопки | layout | Независимо совпало с отзывом по моку (там «можно позже» тоже сделан мелким). Выровнять размер |
| 3 | Заголовок «Смотрю твои данные» отталкивает / пугает за данные — переформулировать | copy | **Прямо в точку с кастдевом #17** и с идеей превью выше: заголовок задаёт рамку раньше, чем читается уточнение. Формулировать через ценность + негатив («что НЕ вижу»), а не «смотрю твои данные» |

### Вопросы / Профиль

| # | Замечание | Тип | Заметка |
|---|---|---|---|
| 1 | Кнопка «изменить прошлые ответы» мелкая — увеличить | layout | Как и «Пропустить» |
| 2 | «Вопрос на сегодня» + лейблы-варианты выглядят странно, «всё поехало» → либо просто поле ручного ввода | layout | Это рендер `question`+`options` чипов. Варианты ценны (один тап), ронять в свободный текст жаль — скорее починить Compose-лейаут чипов. Бэкенд отдаёт `options` корректно (проверено 2026-10-03), проблема в вёрстке |
| 3 | Что происходит при клике «Уточнить» в профиле? | вопрос, не задача | `profile_clarify`: генерит уточняющий вопрос с вариантами, ответ сохраняется как `gen_`-факт и обогащает описание профиля (см. `SIGNALS.md`). Раз вопрос возник — аффорданс неочевиден, стоит подписать, что делает кнопка |

**Сквозное:** эмодзи → единый набор иконок (#Home-4) и переформулировка пугающего заголовка
онбординга (#Онбординг-3) — два изменения, которые бьют одновременно по дизайну и по кастдев-рискам.
Почти все пункты клиентские; бэкенд не затронут ни одним.
