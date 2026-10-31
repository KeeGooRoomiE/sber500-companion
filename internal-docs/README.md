# internal-docs: карта документов

Внутренние материалы проекта (не часть публичного сайта). Структура на 31.10.2026.

| Папка | Что внутри | Когда сюда класть |
|---|---|---|
| [plan/](plan/) | **начинать отсюда**: [TASKS](plan/TASKS.md) (единый ранжированный список), [FINDINGS](plan/FINDINGS.md) (находки), [DESIGN_PLAN](plan/DESIGN_PLAN.md), [EXTERNAL_PLAN](plan/EXTERNAL_PLAN.md), [FIXES_BUCKET](plan/FIXES_BUCKET.md) | планы, списки задач, сводки находок |
| [research/llm-quality/](research/llm-quality/) | качество текстов LLM: аудит, температура, повторы, красная команда, сравнение моделей, follow-up, пограничные случаи, сигналы | исследования качества и промтов |
| [research/user-behavior/](research/user-behavior/) | время использования, тяжёлые пользователи, длинная история | исследования поведения по данным |
| [research/compliance/](research/compliance/) | антифрод, честность метрик | всё про требования организаторов к данным |
| [research/design/](research/design/) | UX-потоки, главный экран, иконка, холодный старт | дизайн-исследования |
| [research/home, icon, ux](research/) | сырые артефакты (картинки, ответы моделей) | прикладывать к дизайн-исследованиям |
| [competition/](competition/) | положение конкурса, запуск этапа 2, воркшопы, подготовка синков, демо-устройство | всё про организаторов и сроки |
| [ops/](ops/) | расход LLM-ключа, журнал автономной работы, задания оценки, индекс исследований | эксплуатация ключей и процессов |
| [dev/](dev/) | архитектура, бэкенд, Android, CI/CD, метрики, рост, RuStore | техническая документация (читать [ARCHITECTURE](dev/ARCHITECTURE.md) перед правками) |
| media/, mockups/ | изображения и макеты | |

Кастдев и общие материалы лежат в корне: [questions](questions.md), [custdev-script](custdev-script.md), [custdev-sources](custdev-sources.md), [UX_DESIGN_RESEARCH](UX_DESIGN_RESEARCH.md).

Индекс исследований: [ops/RESEARCH_SUMMARY.md](ops/RESEARCH_SUMMARY.md). Журнал: [ops/LLM_AUTONOMOUS_LOG.md](ops/LLM_AUTONOMOUS_LOG.md).

Правило: новое исследование идёт в `research/<тема>/`, выводы и задачи из него переносятся в `plan/TASKS.md` и `plan/FINDINGS.md`.
