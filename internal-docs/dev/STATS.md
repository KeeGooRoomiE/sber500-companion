# STATS — статистика: эндпоинты, выгрузка, хранение

Актуально на 2026-10-13. Где лежат данные, как их получить и скачать.

## Три источника

1. **Наша БД (PostgreSQL на VPS)** — источник правды. Сырьё по вызовам, пользователям, активности.
2. **Публичный эндпоинт `GET /api/v1/metrics`** — готовые агрегаты поверх БД (для `metrics.html` и судей).
3. **AppMetrica (Яндекс)** — продуктовая аналитика (онбординг-воронка, сессии, crash-free). Отдельный
   дашборд, в нашу БД не попадает; через API тянется только серверно (токен-секрет).

---

## 1. Публичные агрегаты — `GET /api/v1/metrics`

Без авторизации, CORS включён. Кэш ~несколько минут. Это то, что рисует `web/metrics.html`.

```bash
curl -s https://api.94-183-236-169.sslip.io/api/v1/metrics | python3 -m json.tool
```

Ключевые поля: `users_total`, `new_users_today`, `dau_today`, `dau_7d_avg`,
`retention_d1_pct`, `retention_d7_pct`, `funnel_total/activated/d1/d3/d7` (+ `*_eligible`),
`checkin_rate_pct`, **`calls_per_dau` / `calls_per_dau_avg`** (только LLM-вызовы),
`llm_calls_total/today`, `llm_cost_rub_total`, `llm_cost_rub_per_dau`, `error_rate_pct`,
`uptime_24h_pct`, `forecast_accuracy_pct`; массивы `dau_history`, `checkin_history`,
`retention_cohorts`, `versions`.

Как считается — `backend/internal/api/metrics.go`. Важно: `calls_per_dau(_avg)` считают **только
`call_type='llm'`**, без admin/dev — tool-вызовы (usagestats/checkin/scenario) в KPI не идут.

**Скачать как файл:** `curl -s .../api/v1/metrics > metrics_$(date +%F).json`.

---

## 2. Сырьё — прямой доступ к БД

Подключение (SSH-алиас `companion` в `~/.ssh/config`, `DATABASE_URL` в серверном `.env`):

```bash
ssh companion
set -a; . /opt/companion/.env; set +a
psql "$DATABASE_URL"
```

Или одной командой удалённо:

```bash
ssh companion 'set -a; . /opt/companion/.env; set +a; psql "$DATABASE_URL" -c "SELECT count(*) FROM users;"'
```

### Ключевые таблицы

| Таблица | Что внутри | Для чего |
|---|---|---|
| `call_log` | каждый вызов: `ts, user_id, call_type (llm/tool), component, trigger (scheduled/user_action/followup), user_visible, result (ok/error/empty), latency_ms` | Calls/DAU, error_rate, латентность, анти-фрод |
| `user_activity` | `user_id, date` (день активности) | DAU, ретеншн, когорты |
| `users` | `id, created_at, is_dev` | всего юзеров, регистрации, dev-фильтр |
| `daily_data` | дневной снапшот (экран/сон/шаги/…) | покрытие данными |
| `checkins` | `user_id, date, feel, tags` | checkin-rate, настроение |
| `morning_messages` | прогнозы + токены/латентность | стоимость, объём |
| `reviews` / `explore_answers` / `feedback` | разборы, «Хочу ещё», оценки | вовлечённость |
| `heartbeats` | пинги | аптайм |

### Готовые запросы (проверенные)

**Истинный Calls/DAU (только LLM, без dev/admin) за 7 дней:**
```sql
WITH ex AS (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%')
SELECT
 (SELECT count(*) FROM call_log WHERE call_type='llm' AND user_id<>'admin'
    AND user_id NOT IN (SELECT id FROM ex) AND ts >= current_date-6) AS llm_7d,
 (SELECT coalesce(sum(c),0) FROM (SELECT date,count(*) c FROM user_activity
    WHERE user_id NOT IN (SELECT id FROM ex) AND date>=current_date-6 GROUP BY date) t) AS dau_sum_7d;
-- Calls/DAU = llm_7d / dau_sum_7d
```

**LLM-вызовы сегодня по компонентам:**
```sql
SELECT component, count(*) FROM call_log
WHERE ts >= current_date AND call_type='llm' AND user_id<>'admin'
  AND user_id NOT IN (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%')
GROUP BY component ORDER BY 2 DESC;
```

**Возврат по когортам регистрации (D1/D3/D7):**
```sql
WITH ex AS (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%'),
u AS (SELECT id,(created_at AT TIME ZONE 'Europe/Moscow')::date reg FROM users WHERE id NOT IN (SELECT id FROM ex))
SELECT reg, count(*) size,
 round(100.0*count(*) FILTER (WHERE EXISTS(SELECT 1 FROM user_activity a WHERE a.user_id=u.id AND a.date>=reg+1))/count(*)) d1,
 round(100.0*count(*) FILTER (WHERE EXISTS(SELECT 1 FROM user_activity a WHERE a.user_id=u.id AND a.date>=reg+3))/count(*)) d3,
 round(100.0*count(*) FILTER (WHERE EXISTS(SELECT 1 FROM user_activity a WHERE a.user_id=u.id AND a.date>=reg+7))/count(*)) d7
FROM u GROUP BY reg ORDER BY reg;
```

**DAU и новые по дням:**
```sql
WITH ex AS (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%')
SELECT d::date AS day,
 (SELECT count(*) FROM user_activity a WHERE a.date=d::date AND a.user_id NOT IN (SELECT id FROM ex)) dau,
 (SELECT count(*) FROM users uu WHERE (uu.created_at AT TIME ZONE 'Europe/Moscow')::date=d::date AND uu.id NOT IN (SELECT id FROM ex)) new_users
FROM generate_series(current_date-13, current_date, interval '1 day') d ORDER BY d;
```

**Ошибки LLM за 24ч по компонентам (разбор error_rate):**
```sql
SELECT component, count(*) FILTER (WHERE result='error') err, count(*) total
FROM call_log WHERE call_type='llm' AND ts>=now()-interval '24 hours' GROUP BY component ORDER BY 2 DESC;
```

---

## 3. Как скачать сырьё (CSV)

### Публичный эндпоинт (проще всего, без SSH)

`GET /api/v1/export/call_log.csv` — весь `call_log` в CSV, **без авторизации**, CORS, **обновляется
раз в 5 минут** (ленивый кэш). dev/mock/admin исключены — цифры совпадают с публичными `/metrics`.
На странице `metrics.html` — кнопка «Скачать сырые данные». Код: `backend/internal/api/export.go`.

```bash
curl -s https://api.94-183-236-169.sslip.io/api/v1/export/call_log.csv -o call_log.csv
```

Колонки: `ts, user_id (SHA256-хеш), session_id, call_type, component, trigger, user_visible, result, latency_ms`.

### Любая другая таблица/срез — через `\copy` на сервере

Клиентская выгрузка (без прав суперюзера):

```bash
ssh companion 'set -a; . /opt/companion/.env; set +a; \
  psql "$DATABASE_URL" -c "\copy (SELECT ts,user_id,call_type,component,trigger,result,latency_ms FROM call_log WHERE ts>=current_date-30) TO STDOUT WITH CSV HEADER"' \
  > call_log_30d.csv
```

Тот же приём для любой таблицы/запроса — меняешь SELECT. Для JSON-агрегатов — просто `curl /metrics`.

---

## 4. Где и как хранится

- **СУБД:** PostgreSQL на том же VPS (`cloudcore.ru`, IP 94.183.236.169). Доступ только с сервера
  (`DATABASE_URL` в `/opt/companion/.env`), наружу порт не открыт.
- **Срок хранения:** TTL нет — `call_log`, `user_activity`, `daily_data` растут. Объёмы маленькие
  (десятки юзеров), чистка пока не нужна.
- **Анти-фрод:** `call_log` фиксирует КАЖДЫЙ вызов с типом/триггером/видимостью — это и есть
  доказательная база для конкурса. Фоновые (`trigger=scheduled`) отделены от пользовательских.
- **Dev/mock исключение:** в метриках и выгрузках исключаем `is_dev` и префикс `mock_` (+ `DEV_USER_IDS`,
  сейчас пустой). Не забывать в ad-hoc запросах, иначе цифры завышены.
- **Часовой пояс:** сервер и даты — Europe/Moscow. В запросах по регистрации — `AT TIME ZONE 'Europe/Moscow'`.

---

## 5. AppMetrica (отдельно)

Продуктовые события (онбординг-степы, `first_forecast_shown`, сессии, crash-free) — в дашборде
AppMetrica (Яндекс), не в нашей БД. Через Reporting API тянется только серверно (OAuth-токен —
секрет, в браузер не кладём). Планы по выводу на `metrics.html` — см. `GROWTH.md`/бэклог; пока
funnel-события инструментованы рвано (дозреют на новых инсталлах 1.0.x).
