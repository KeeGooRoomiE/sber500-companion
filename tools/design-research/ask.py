#!/usr/bin/env python3
"""
Точечное поручение модели-исследователю (с поиском, чтением страниц и просмотром картинок).

  python3 ask.py --model gpt-5.1 --steps 10 --name ab-evidence "найди публичные кейсы A/B-тестов иконок ..."
  python3 ask.py --ctx icon --model claude-opus-4.8 "проверь утверждение ..."

Печатает итоговые заметки модели и сохраняет их в data/ask/agent_<name>.md.
Запускать через run.sh ask (ключ из backend/.env). Состояние сохраняется, упавший вызов продолжается.
"""
import argparse, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import research as R

TAIL = """

## Формат ответа
Короткие заметки на русском, до 30 строк:
- `## Ответ`: по существу, с конкретными фактами и цифрами.
- `## Источники`: URL и что именно взято оттуда (одна строка на источник). Только то, что ты реально открыл.
- `## Не удалось подтвердить`: чего не нашёл или в чём не уверен. Не заполняй пробелы догадками.
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("task")
    ap.add_argument("--model", default="gpt-5.1")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--name", default=None)
    ap.add_argument("--ctx", choices=["none", "icon", "design"], default="none")
    ap.add_argument("--img", action="append", default=[], help="путь к картинке (можно несколько)")
    a = ap.parse_args()

    out = R.DATA / "ask"
    out.mkdir(parents=True, exist_ok=True)
    R.init_spend(out)
    name = a.name or f"{a.model.replace('.', '-')}_{int(time.time())}"
    ctx, imgs = "", []
    if a.ctx in ("icon", "design"):
        ctx = (R.HERE / f"briefs/{a.ctx}.md").read_text(encoding="utf-8").split("## Что нужно сделать")[0].replace("{PRIOR}", "(нет)") + "\n"
        if a.ctx == "icon":
            imgs.append(R.data_url(R.ROOT / "internal-docs/dev/rustore/icon-512.png", 384))
    for p in a.img:
        imgs.append(R.data_url(Path(p), 600, quality=65))
    brief = ctx + "## Задание\n" + a.task + TAIL
    for rnd in range(4):
        rep = R.run_agent(name, a.model, brief, imgs, out, "ask", a.steps)
        if rep:
            break
        print(f"[ask] повтор {rnd + 1} через 60с", file=sys.stderr, flush=True)
        time.sleep(60)
    print(f"===== {name} ({a.model}) =====")
    print(rep or "(не удалось получить ответ)")


if __name__ == "__main__":
    if not R.KEY:
        sys.exit("нет LLM_API_KEY (запускай через run.sh ask)")
    main()
