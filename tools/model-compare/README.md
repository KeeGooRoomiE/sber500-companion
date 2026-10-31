# model-compare

Задача #2 из `internal-docs/ops/LLM_EVAL_TASKS.md`: latency/cost по моделям → рекомендация
прод-модели. Quality не передублирует — переиспользует находки `tools/signal-rd/` (signal-
finding) и `tools/followup-rd/` (честность на follow-up-цепочках).

## Что меряет

Один логический замер = один вызов на реальном `day_review`-промте (байт-в-байт с сервера,
`tools/followup-rd/data/real_prompts.json`) с **реальным прод-лимитом токенов**
(`DayReviewLimits.Tokens = 260`) — отвечает на «что будет сегодня, без правок кода», не
«при щедром бюджете». Без fallback/retry (маскируют реальную latency) — ретраится только
мгновенный connection-reset до отправки (перегрузка общего прокси, не вина модели).

## Запуск

```bash
bash tools/model-compare/run.sh bench --model gpt-5.1 [--n 9] [--max-tokens 260]
```

Строго последовательно — не параллелим модели. Результат — `data/bench_<model>.json`
(ok_rate, p50/p95/max latency, ₽/вызов) + печать по ходу.

`data/` в `.gitignore`. Итог — `internal-docs/research/llm-quality/MODEL_COMPARISON.md`.
