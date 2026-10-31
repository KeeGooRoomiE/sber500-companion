# signal-rd

R&D #5 из `internal-docs/ops/LLM_EVAL_TASKS.md`: кандидатные поведенческие сигналы/паттерны
для explore, найденные на **реальных данных пользователей** (не моках) — результат сразу
сопоставим с тем, что видят живые юзеры.

Переиспользует `tools/design-research/research.py::llm_patient` для генерации (ретраи,
fallback на gpt-5.1, спенд-гвард) и судейский паттерн `tools/llm-audit/audit.py` для
валидации (строгий JSON-вердикт, consensus из двух моделей).

## Пайплайн

1. **fetch** — тянет сырые дневные агрегаты (сон/экран/шаги/разблокировки/топ-приложения) +
   чек-ины для N самых "богатых" по истории реальных юзеров (не dev/mock). Только числа и
   даты, без PII. → `data/users.json`
2. **brainstorm** — для каждого юзера одна модель ищет 2-3 кандидатных паттерна, обязательно
   с опорой на конкретные числа/даты. Один вызов на юзера, последовательно. → `data/brainstorm_<model>.json`
3. **validate** — judge-модель проверяет каждого кандидата против ПОЛНЫХ данных юзера:
   подтверждается или натяжка/выдумка. → `data/validate_<judge>_<источник>.json`

## Запуск

```bash
bash tools/signal-rd/run.sh fetch 5

bash tools/signal-rd/run.sh brainstorm --model deepseek-v4-pro
bash tools/signal-rd/run.sh validate   --model gpt-5.1 --from brainstorm_deepseek-v4-pro.json

# следующая модель — так же, по одной за раз
bash tools/signal-rd/run.sh brainstorm --model gpt-5.1
bash tools/signal-rd/run.sh validate   --model claude-sonnet-4.6 --from brainstorm_gpt-5.1.json
```

Сознательно **без параллелизма** (ни по моделям, ни по юзерам) — ключ общий с другими
прогонами (`llm-audit`, `design-research`), см. `internal-docs/ops/LLM_SPEND_GUARDRAILS.md`.

`data/` в `.gitignore` — реальные агрегаты только локально. Результаты и выводы сводятся в
`internal-docs/dev/SIGNAL_RD.md`.
