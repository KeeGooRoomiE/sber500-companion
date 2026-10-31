# edge-case-rd

Задача #6 из `internal-docs/dev/LLM_EVAL_TASKS.md`: вырожденные/экстремальные входы для
`day_review_system.md` → промт не должен ломаться/галлюцинировать/советовать.

7 синтетических кейсов (`cases.py`), один на ось робастности: почти нет данных, новый юзер
без личной нормы, экстремально высокая/низкая активность, метрики vs чек-ин в противоречии
(в обе стороны), сон/приложения отсутствуют целиком. Формат блока — визуально тот же, что
реальный серверный промт (структура из `tools/followup-rd/data/real_prompts.json`).

## Запуск

```bash
bash tools/edge-case-rd/run.sh run   --model gigachat-3-pro
bash tools/edge-case-rd/run.sh judge --judge gpt-5.1 --from edge_gigachat-3-pro.json
```

Judge получает под каждый кейс свой критерий «что значит робастно» (`CASE_GUIDANCE` в
`judge_edge.py`) — у «почти нет данных» и «метрики противоречат чек-ину» разные ответы
считаются правильными.

`data/` в `.gitignore`. Итог — `internal-docs/edge-cases/EDGE_CASE_RD.md`.
