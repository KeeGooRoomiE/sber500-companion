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
- [x] **45 пользователей, 28 DAU, calls/DAU 13.3, retention D1 73%** (02.10.2026)
- [x] MVP раньше дедлайна 06.10 ✅

### Ждём внешнего
- [x] ~~cloud.ru API key~~ — получен, LLM работает (282 вызова, 22 ₽ из 50 000)
- [ ] ⚠️ **AppMetrica ключ** → SDK init в CompanionApp. **Главный открытый риск:**
      чекпоинт Недели 2 требует «аналитика подключена», а DAU для организатора
      считается по AppMetrica. Ключ пустой → наш DAU для них равен нулю,
      хотя на своей странице метрик он 28.

### Осталось сделать
- [x] README.md в репозитории
- [ ] Связать README с ARCHITECTURE.md — критерий требует «визуализированную архитектуру» в пакете приёмки
- [x] 10 RPS на живом сервере — 100% успешных при load average 0,07, отчёт в LOADTEST.md
- [x] 20 custdev интервью ✅ **ВЫПОЛНЕНО ПОЛНОСТЬЮ (20/20)**
- [ ] 50 пользователей к 13.10 — **45/50**, идём с опережением
- [ ] Промт v2 залить в БД через admin API (как придёт LLM ключ)

## Metrics the competition measures

| Metric | Our strategy |
|--------|-------------|
| DAU | AppMetrica SDK on Android |
| Calls/DAU | `call_log` table — every LLM call logged |
| TPM / TPS | logged per call in `morning_messages.prompt_tokens + completion_tokens` |
| LLM-cost/DAU | token counts × pricing from cloud.ru |
| User retention | AppMetrica + morning message streak |

## LLM API

- Provider: cloud.ru Foundation Models
- API: OpenAI-compatible (`https://api.cloud.ru/v1`)
- Budget: 50,000₽ for Stage 1
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

- Script: `internal-docs/custdev-script.md`
- Sources: `internal-docs/custdev-sources.md`
- Target: 10 interviews by 2026-09-29
- Questions for organizers: `internal-docs/questions.md`
