# Metrics Page — developer notes

## Назначение

`web/metrics.html` — публичная страница с live-метриками проекта для судей Sber500 x DISRUPT.  
URL после деплоя: `https://keegooroomie.github.io/sber500-companion/metrics.html`

Деплоится автоматически тем же `pages.yml` — отдельного CI не нужно.

**Только десктоп** — мобильная адаптация не предусмотрена намеренно (аудитория: судьи за компьютером).

---

## Источник данных

```
GET https://<API_URL>/api/v1/metrics
```

`API_URL` вшит константой в начале `<script>` в файле. Менять там:

```js
const API_URL = 'https://api.companion.keegooroomie.ru/api/v1/metrics';
```

CORS на бэкенде: `Access-Control-Allow-Origin: *` — fetch работает с GitHub Pages без прокси.

---

## Формат ответа бэкенда

```json
{
  "dau_today":             42,
  "dau_7d_avg":            38,
  "calls_per_dau":         3.2,
  "llm_calls_total":       1240,
  "llm_cost_rub_per_dau":  1.4,
  "users_total":           156,
  "p95_latency_ms":        820,
  "error_rate_pct":        0.3,
  "updated_at":            "2026-09-24T10:00:00Z",
  "dau_history": [          // опционально — если есть, рендерится бар-чарт
    { "date": "2026-09-18", "dau": 30 },
    { "date": "2026-09-19", "dau": 35 }
  ]
}
```

---

## Отображаемые тайлы

| Ключ                   | Подпись         | Ед. изм.               | Предупреждение         |
|------------------------|-----------------|------------------------|------------------------|
| `dau_today`            | DAU сегодня     | пользователей          | —                      |
| `dau_7d_avg`           | DAU 7д avg      | пользователей          | —                      |
| `users_total`          | Всего юзеров    | зарегистрировано       | —                      |
| `calls_per_dau`        | Calls / DAU     | запросов на юзера      | —                      |
| `llm_calls_total`      | LLM calls total | вызовов                | —                      |
| `llm_cost_rub_per_dau` | Cost / DAU      | ₽ на активного юзера   | —                      |
| `p95_latency_ms`       | P95 latency     | мс                     | > 1500ms жёлтый, > 3000ms красный |
| `error_rate_pct`       | Error rate      | %                      | > 1% жёлтый, > 3% красный |

---

## Поведение при ошибке

Если `fetch` падает (бэкенд недоступен / CORS / timeout) — показывается заглушка:  
> "Бэкенд ещё не задеплоен"  
Страница не крашится, авторефреш продолжает работать.

---

## Авторефреш

- `setInterval(fetchMetrics, 30000)` — каждые 30 секунд
- Кнопка «Обновить» вызывает `fetchMetrics()` вручную
- Timestamp `updated_at` из ответа отображается в шапке

---

## Мини-график DAU

Если в ответе есть поле `dau_history` (массив `{date, dau}`):
- Рендерится bar chart inline SVG без библиотек
- Bars окрашены с opacity пропорционально высоте (визуальный акцент на пиковые дни)
- Подписи дат под каждым баром (`DD.MM`)
- Если `dau_history` нет — блок с графиком не появляется

---

## Дизайн

- Палитра и шрифт идентичны лендингу (`#6B5CE7`, `#F7F6FF`, Inter)
- Nav — тот же floating pill, ссылка назад на `index.html`
- Grid: `auto-fill minmax(160px, 1fr)` — тайлы растягиваются по ширине
- Цветовые состояния тайлов: normal / `.warn` (жёлтый фон) / `.danger` (красный фон)
