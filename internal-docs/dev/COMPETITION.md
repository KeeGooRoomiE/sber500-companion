# Competition context — Sber500 x DISRUPT

## Key facts

- Participant: Александр, ID **29754**, solo developer
- 143 teams selected → 50 advance to Stage 2
- Target nomination: **"User Experience"** (1000+ DAU, 10+ calls/DAU, UX/UI quality, **5M₽**)

## Deadlines

| Date | Milestone |
|------|-----------|
| 2026-10-06 | MVP v0 publicly deployed (landing + APK + backend running) |
| 2026-10-15 | Stage 1 final submission |
| TBD | Stage 2 begins (top 50) |

## Stage 1 MVP checklist

### Готово ✓
- [x] GitHub repo доступен `wow-distrupt` (collaborator добавлен)
- [x] Landing page (`web/index.html`) — описание, download link, палитра
- [x] APK на GitHub Pages (`companion-latest.apk`)
- [x] Live метрики — `web/metrics.html` (тянет с бэкенда, fallback error card)
- [x] Mock метрики для судей — `web/metrics-mock.html` (22-дневная симуляция: DAU/воронка/ретеншн/LLM)
- [x] LLM-cost/DAU считается (`prompt_tokens × 73.03₽/1M` + `completion_tokens × 176.39₽/1M`)
- [x] `call_log` anti-fraud логгер + `scenario_completed` событие
- [x] Android: онбординг, домашний экран, уведомления (morning + checkin), data collectors
- [x] Backend: все эндпоинты, планировщик 04:10, LLM prompting
- [x] **VPS задеплоен** — `https://api.94-183-236-169.sslip.io/`, живой, метрики отдаёт
- [x] **61 пользователь, DAU ~30 (7-дн 29.7), D1 90%, D7 ~53% (тёплые 76%)** — на 09.10.2026
- [x] **#1 на батчкемпе Sber500** (динамика стартапов)
- [x] MVP раньше дедлайна 06.10 ✅

### Внешнее — закрыто
- [x] cloud.ru API key — получен, LLM работает. Эндпоинт `https://shared1.multitool.works:4000/v1`
      (прокси к моделям cloud.ru), модель `gigachat-3-pro`. Потрачено ~121₽ из 50 000.
- [x] **AppMetrica** — ✅ активна (ключ из CI-секрета, геолокация отключена). События онбординга
      собираются (funnel дозреет на новых инсталлах — инструментовка на старых билдах рваная).

### Условия Стадии 1 (по инфе ментора — проход = выполнить, не ранжирование)
- [x] Звонки с ментором — Н1/Н2 прошли, Н3 ожидается
- [x] 20 кастдевов ✅ **27 проведено** (формально 28, 28-й не внесён)
- [x] Завершение MVP — 1.0.0
- [x] MVP в открытый доступ — лендинг + APK (RuStore 1.0.3 — для чистоты, см. RUSTORE.md)
- [~] Пользователи (кап 100) — **61**, добор к 100 тёплыми (GROWTH.md). Выше 100 очков не даёт.
- [x] 10 RPS на живом сервере — 100% успешных, отчёт в LOADTEST.md
- [x] README.md + архитектура в репозитории

## Metrics the competition measures

| Metric | Our strategy |
|--------|-------------|
| DAU | AppMetrica SDK on Android |
| Calls/DAU | `call_log` table — every LLM call logged |
| TPM / TPS | logged per call in `morning_messages.prompt_tokens + completion_tokens` |
| LLM-cost/DAU | token counts × pricing from cloud.ru |
| User retention | AppMetrica + `user_activity` (D1 90%, D7 ~53%) |

**Calls/DAU (истинный, только LLM):** сейчас **~3.8–6.9** (не 13/18 — те считали весь `call_log`,
включая tool-вызовы; метрика починена на llm-only). KPI номинации ≥10 — цель; добор через
вовлечённость механик 1.0.0 (insight/follow-up/explore) и планируемый **чат** (следующий месяц).
Детали доступа к статистике — `STATS.md`.

## LLM API

- Provider: cloud.ru Foundation Models (модель `gigachat-3-pro`)
- API: OpenAI-compatible, эндпоинт `https://shared1.multitool.works:4000/v1` (прокси к моделям cloud.ru)
- Budget: 50,000₽ for Stage 1 (потрачено ~121₽)
- Key delivered via submission form (PAT + API key form submitted)
- Never commit to repo — goes into server's `/opt/companion/.env` as `LLM_API_KEY`

## Infrastructure budget

- cloudcore.ru VPS: ~60₽/month (cheapest tier)
- GitHub Pages: free
- AppMetrica: free tier

## GitHub access for Sber500 judges

- Account `wow-distrupt` added as collaborator with read access
- Repo must remain public or collaborator access maintained

## UX nomination requirements

- 1000+ DAU
- 10+ LLM calls per DAU (morning message + evening check-in + weekly)
- UX/UI quality — Compose UI, smooth onboarding, single-tap check-in
- No dark patterns, no fake engagement

## Product differentiation

- Passive collection — zero daily friction
- One-tap evening check-in (3 buttons in notification, no app open)
- Morning forecast tailored to actual phone usage data (not self-report)
- Character companion creates emotional connection vs. generic wellness apps

## Custdev status

- **Проведено 27 интервью** (формально 28, 28-й не внесён) — условие «20 кастдевов» перевыполнено.
- Script + расшифровки всех 27: `internal-docs/custdev-script.md`
- Sources: `internal-docs/custdev-sources.md`
- Questions for organizers: `internal-docs/questions.md`
