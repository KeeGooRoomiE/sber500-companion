# Метрики устройства — маппинг Android → БД

Все поля, которые Android-приложение собирает пассивно и передаёт на бэкенд.  
Формат: **Android API источник → Kotlin domain model → JSON DTO → колонка в БД**.

---

## Группа: Экран и активность (UsageStatsManager)

**Разрешение:** `android.permission.PACKAGE_USAGE_STATS` (выдаётся через Settings, не через системный диалог)  
**Сбор:** `UsageStatsManager.queryEvents(dayStart, dayEnd)` — перебор событий за сутки  
**Коллектор:** `UsageStatsCollector.collect()`

| Метрика | Android API event | Kotlin field | JSON key | DB column | Описание |
|---------|-------------------|--------------|----------|-----------|----------|
| Время экрана | `SCREEN_INTERACTIVE` → `SCREEN_NON_INTERACTIVE` (delta) | `UsageSnapshot.screenMinutes: Int` | `screen_min` | `daily_data.screen_minutes` | Суммарное время, когда экран был включён и интерактивен. Единица — минуты. |
| Разблокировок | `KEYGUARD_HIDDEN` (count) | `UsageSnapshot.unlocks: Int` | `unlocks` | `daily_data.unlocks` | Сколько раз пользователь разблокировал телефон за день. |
| Первая разблокировка | `KEYGUARD_HIDDEN` (первое событие) | `UsageSnapshot.firstUnlock: LocalTime?` | `first_unlock` | *(не в схеме v1, добавить)* | Время первой разблокировки — прокси «во сколько встал». Формат `HH:mm`. |
| Последняя разблокировка | `KEYGUARD_HIDDEN` (последнее событие) | `UsageSnapshot.lastUnlock: LocalTime?` | `last_unlock` | *(не в схеме v1, добавить)* | Время последнего использования телефона — прокси «во сколько лёг спать». |
| Топ-приложения | `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED` (delta per package) | `UsageSnapshot.topApps: List<AppUsage>` | `top_apps` (JSONB array) | `daily_data.top_apps` | До 10 приложений (минимум 1 мин/день), отсортированы по убыванию. Каждый элемент: `{ package_name: String, minutes: Int }`. `package_name` — стандартный Android package (например, `com.instagram.android`). |

---

## Группа: Сон (Health Connect)

**Разрешение:** `HealthPermission.READ(SleepSessionRecord)` — системный диалог HC  
**Сбор:** `HealthConnectClient.readRecords(SleepSessionRecord, range)` — берём самую длинную сессию за сутки  
**Коллектор:** `HealthConnectCollector.collectSleep()`  
**Fallback:** если Health Connect недоступен или нет данных — поля `null`, не блокирует остальной сбор.

| Метрика | HC Record / поле | Kotlin field | JSON key | DB column | Описание |
|---------|-----------------|--------------|----------|-----------|----------|
| Длительность сна | `SleepSessionRecord.endTime - startTime` | `SleepSnapshot.durationMinutes: Int` | `sleep_min` | `daily_data.sleep_minutes` | Длина самой длинной сессии сна за ночь. Единица — минуты. |
| Время отхода ко сну | `SleepSessionRecord.startTime` | `SleepSnapshot.bedtime: LocalTime?` | `bedtime` | `daily_data.bedtime` | Момент начала сна. Формат `HH:mm`. Может быть вчерашним (например `23:10`). |
| Время подъёма | `SleepSessionRecord.endTime` | `SleepSnapshot.wakeup: LocalTime?` | `wakeup` | `daily_data.wakeup` | Момент окончания сна. Формат `HH:mm`. |

---

## Группа: Шаги (Health Connect)

**Разрешение:** `HealthPermission.READ(StepsRecord)` — тот же диалог, что и сон  
**Сбор:** `HealthConnectClient.readRecords(StepsRecord, range)` — сумма всех записей за сутки  
**Коллектор:** `HealthConnectCollector.collectSteps()`  
**Fallback:** `0` при недоступности HC (не `null` — шаги всегда числовые).

| Метрика | HC Record / поле | Kotlin field | JSON key | DB column | Описание |
|---------|-----------------|--------------|----------|-----------|----------|
| Шаги за день | `StepsRecord.count` (sum) | *(пока в DailyCollectWorker inline)* | `steps` | `daily_data.steps` | Суммарное количество шагов за сутки по всем записям StepsRecord. |

---

## Группа: Батарея (BatteryManager)

**Разрешение:** не требуется  
**Сбор:** `context.registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` в момент запуска Worker (~00:00–04:00)  
**Коллектор:** `Context.batteryLevel()` extension в DailyCollectWorker

| Метрика | Android API | Kotlin field | JSON key | DB column | Описание |
|---------|-------------|--------------|----------|-----------|----------|
| Уровень заряда | `BatteryManager.EXTRA_LEVEL / EXTRA_SCALE * 100` | `BatterySnapshot.levelPercent: Int` | `battery_morning` | `daily_data.battery_pct` | Процент заряда в момент ночного сбора. Косвенно показывает, заряжал ли пользователь телефон на ночь. |

---

## Группа: Чек-ин (пользовательский ввод)

**Источник:** нажатие кнопки на HomeScreen или action-кнопка в уведомлении  
**Таблица:** `checkins`

| Метрика | Источник | Kotlin field | JSON key | DB column | Описание |
|---------|----------|--------------|----------|-----------|----------|
| Оценка дня | Кнопка OK / MEH / HARD | `DayFeel` enum | `day_feel` | `checkins.feel` | Субъективная оценка дня. Три значения: `ok`, `meh`, `hard`. |
| Теги | *(не реализовано, план)* | `List<String>` | `tags` | `checkins.tags` | Ярлыки вроде «стресс», «спорт», «общение» — для детализации. |
| Заметка | *(не реализовано, план)* | `String?` | `note_text` | `checkins.note_text` | Свободный текст от пользователя, опционально. |

---

## Итоговый JSON (PassiveDataRequest)

```json
{
  "date": "2026-09-24",
  "screen_min": 214,
  "unlocks": 47,
  "first_unlock": "07:32",
  "last_unlock": "23:41",
  "top_apps": [
    { "package_name": "org.telegram.messenger", "minutes": 48 },
    { "package_name": "com.instagram.android", "minutes": 31 }
  ],
  "sleep_min": 382,
  "bedtime": "23:10",
  "wakeup": "05:32",
  "steps": 6240,
  "battery_morning": 87,
  "fcm_token": null
}
```

---

## Что не собираем (и почему)

| Данные | Почему нет |
|--------|-----------|
| Геолокация | Слишком чувствительно, не нужно для UX-номинации |
| Контакты / звонки | Требует отдельного разрешения, высокий отказ |
| Содержимое сообщений | Неприемлемо по privacy |
| Календарь | Требует `READ_CALENDAR`, планируется для v2 |
| Пульс / SpO₂ | Требует `BODY_SENSORS`, данные не у всех |

---

## Расхождения схема v1 ↔ текущим кодом

| Поле | Статус |
|------|--------|
| `first_unlock` / `last_unlock` | Собирается в domain model, передаётся в DTO, но **отсутствует в `daily_data` миграции** — добавить в `002_add_unlock_times.sql` |
| `steps` | Собирается, но domain model `SleepSnapshot` не включает steps — лежит inline в Worker |
| `fcm_token` | Поле в DTO зарезервировано, логика FCM не реализована |
