> **АРХИВ. Не исполняется.** Решение: iOS за бортом by design — на iOS нет той
> половины данных, которая даёт ценность («ты этого о себе не знал»): данные Screen
> Time живут в песочнице расширения и их нельзя вынести в приложение или на сервер,
> а без Apple Watch воронка сна и активности не окупает разработку.
>
> Документ оставлен ради раздела 0 — это и есть зафиксированная причина отказа.
> План из разделов 3–6 устарел и к работе не предлагать.

---

# iOS — план приложения

Статус: черновик-план (2026-09-28). Триггер: доля iPhone среди пользователей растёт
(11 интервью / 9 установок; iOS-юзеры уже в ЦА — Кирилл, Ксения на iPhone).

---

## 0. Главное ограничение платформы (определяет весь план)

Android-приложение построено на пассивных данных телефона: экранное время, разблокировки,
топ-приложений по минутам (`UsageStatsManager`). Именно это даёт «ты этого о себе не знал».
На iOS этой половины почти нет.

| Сигнал | Android | iOS |
|---|---|---|
| Экран / разблокировки / приложения по минутам | `UsageStatsManager` — полный доступ | **Screen Time API (DeviceActivity)** — данные живут в песочнице-расширении, их нельзя вытащить в приложение/на сервер. Только пороговые события + агрегат для показа внутри. Требует entitlement `Family Controls` (заявка Apple). |
| Сон | Health Connect | **HealthKit** — полноценно, фоновая доставка |
| Шаги / пульс / HRV / тренировки | Health Connect | **HealthKit** — полноценно |
| Фон | WorkManager | `BGTaskScheduler` — жёстче, но хватает на дневной снапшот |
| Пуши | FCM | **APNs** (можно через FCM) + локальные уведомления с actions |
| Виджет | Glance | WidgetKit |

**Вывод:** экранную половину честно не воспроизвести. iOS-версия строится вокруг
**сна + восстановления + активности + чек-инов.**

---

## 1. Переосмысление ценности для iOS

Не «почему твой экран сожрал день», а **«почему день был таким — по сну, восстановлению и
движению»**. Пульс покоя и HRV (которых нет на дешёвых Android) на iPhone + Apple Watch дают
сигнал восстановления даже сильнее экрана — это отдельный козырь, а не компромисс.

---

## 2. Что переиспользуется без изменений

- **Go-бэкенд целиком** — iOS-клиент бьёт в те же `/api/v1/*`.
- `PassiveDataRequest` уже терпит null-экран (`screen_min/unlocks/top_apps` nullable,
  `DailyCollectWorker` не шлёт пустые строки) → iOS-снапшот сон+шаги+пульс сервер переваривает.
- `call_log` / anti-fraud — без изменений (те же эндпоинты, то же логирование).
- AppMetrica — iOS SDK есть, аналитика в паритете.

**Что доработать на бэке:**
- iOS-осведомлённость промтов: сейчас утро/разбор опираются на экран. Для iOS — опора на
  сон/активность/пульс. Либо отдельный вариант промта, либо деградация существующего.
- Push: добавить APNs-ключ (через Firebase или нативно). Типы `morning | day_review` уже есть.
- Схему БД не трогаем.

---

## 3. Архитектура iOS (SwiftUI, MVVM)

```
iOS app (SwiftUI)
├── HealthKitCollector   — сон, шаги, пульс покоя, HRV, тренировки
│                          фон: HKObserverQuery + enableBackgroundDelivery
├── APIClient            — те же /api/v1/* (URLSession, без сторонних)
├── Push                 — APNs; проще всего через FCM (Firebase маршрутизирует в APNs) →
│                          бэкендовый push-слой переиспользуется 1:1
├── Notifications        — UNUserNotificationCenter + категории с actions
│                          (чек-ин OK/MEH/HARD прямо из шторки)
├── BGTaskScheduler      — BGAppRefreshTask: дневной снапшот + утренняя генерация
├── UI                   — Onboarding / Home / CheckIn / Review / Profile (зеркало Android)
├── WidgetKit            — виджет (фаза 2)
└── DeviceActivityReport — грубый экран-агрегат внутри приложения (фаза 2+, опционально)
```

Экраны (зеркало Android):
- **Onboarding** + запрос HealthKit-разрешений
- **Home** — прогноз + факты + чек-ин
- **Review** — разбор дня / итоги недели
- **Profile** — профиль/контекст о человеке

---

## 4. Что нужно для разработки

### Инструменты
- **Mac + Xcode** (последний стабильный), Swift 5.x / 6, SwiftUI.
- **Реальный iPhone** — HealthKit не работает в симуляторе как надо.
- **Apple Watch** — для пульса покоя / HRV (желательно; без часов сигнал восстановления беднее).

### Аккаунт и App Store Connect
- **Apple Developer (individual)** — ✅ есть.
- **App record** в App Store Connect + уникальный **Bundle ID** (напр. `ru.keegoo.companion`).
- **Distribution-сертификат** + **provisioning profile** (App Store). Xcode может автоматически.

### Capabilities / entitlements (в проекте Xcode)
- **HealthKit** (entitlement) + Info.plist: `NSHealthShareUsageDescription` (мы только читаем,
  писать не нужно → `NSHealthUpdateUsageDescription` не обязателен).
- **Push Notifications** (APNs).
- **Background Modes**: Background fetch, Background processing, Remote notifications.
  (HealthKit background delivery включается кодом, но требует HealthKit capability.)
- **(Фаза 2+, НЕ для MVP)** Family Controls — отдельная заявка Apple, долго; для тонкого MVP не брать.

### Ключи для бэкенда (чтобы сервер слал пуши)
- **APNs Auth Key (.p8)** + Key ID + Team ID.
  - Проще: загрузить `.p8` в проект **Firebase** → Go-код с FCM не меняется, iOS получает пуши.
  - Нативно: слать APNs напрямую из Go (JWT на .p8) — больше кода, минус Firebase-зависимость.

### Фреймворки / зависимости
- Системные: HealthKit, UserNotifications, BackgroundTasks, WidgetKit.
- Сеть — URLSession (сторонних не надо).
- AppMetrica iOS SDK — паритет аналитики с Android.
- Firebase iOS SDK — только если пуши идут через FCM.
- Privacy policy (URL) — обязательна для HealthKit и для External-ревью.

---

## 5. Чек-лист «от нуля до Install у тестера» (Internal TestFlight — без модерации)

1. **ASC:** создать App record + Bundle ID.
2. **Xcode:** проект, capabilities (HealthKit, Push, Background Modes), Info.plist usage-strings.
3. **Подпись:** distribution-сертификат + provisioning profile (Xcode автоматически или вручную).
4. **Archive:** Product → Archive (таргет — реальное устройство).
5. **Upload:** Xcode Organizer или Transporter.
6. **Export Compliance:** ответить на вопрос про шифрование (обычно exempt).
7. **Обработка билда:** ~15–60 мин (первый билд иногда дольше).
8. **TestFlight:** ASC → TestFlight → добавить **Internal-тестеров** (Users, до 100) →
   они ставят приложение TestFlight → **Install**.

- **Internal — модерации НЕТ**, тестеры ставят в тот же час.
- **External** (публичная ссылка, до 10 000) = первое **Beta App Review ~24ч** (плавает, бывает быстрее).

---

## 6. Фазы и сроки

**Тонкий MVP** (трек «здесь и сейчас», internal TestFlight):
- Онбординг + HealthKit-разрешения
- Дневной снапшот (сон / шаги / пульс) → сервер
- Утренний прогноз (пуш)
- Чек-ин из уведомления
- Разбор дня
- Бэкенд: iOS-вариант промта + APNs-ключ

**v1** (после MVP):
- WidgetKit-виджет
- Итоги недели
- Follow-up-кнопки (см. план follow-up)
- HRV / восстановление как отдельная фича
- Screen Time агрегат внутри приложения

**Сроки честно:** натив с нуля. Тонкий MVP реалистично к Stage 1 (15.10) — тяжело для соло;
вести отдельным треком. Быстрый ход — internal TestFlight-сборка MVP, чтобы iOS-юзеры уже были
в воронке, а экранную часть не обещать.

---

## 7. Открытые решения

1. **Ценность iOS:** сон/восстановление как ядро (рекомендую) — или пытаться в Screen Time.
2. **Пуши:** через FCM (реюз бэка) — или нативный APNs из Go.
3. **Промты:** отдельный iOS-вариант — или деградация существующих на сон/активность.
4. **Сроки:** iOS как трек после Stage 1 — или форсировать TestFlight-MVP раньше.

---

## 8. Риски

- HealthKit требует реального устройства (+ Apple Watch для полного сигнала).
- Фон iOS жёстче Android — прогноз может опаздывать; APNs как бэкстоп (роль FCM на Android).
- Privacy policy обязательна (HealthKit + External review).
- Без экрана эффект «ты этого о себе не знал» слабее — компенсируем HRV/восстановлением.
