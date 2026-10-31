#!/usr/bin/env python3
"""Кластеризация grounded=1 кандидатов (из validate.py, широкий прогон) по ТИПАМ механики —
не дословные claim'ы, а переиспользуемые шаблоны («экран ↔ настроение», «регулярность
подъёма», «co-occurrence приложений»...). Цель: увидеть, какие типы повторяются у многих
разных юзеров — это и есть кандидаты на `backend/internal/signals`, а не факты про одного
человека.

Запуск: python3 tools/signal-rd/cluster.py --validate FILE1,FILE2,... --model M
Результат: data/clusters.json + печать в stdout.
"""
import argparse, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "design-research"))
from research import llm_patient, extract_json  # noqa: E402

SYS = """Ты аналитик продуктовых паттернов для цифрового компаньона на пассивных данных телефона.
Тебе дают список УЖЕ ПРОВЕРЕННЫХ (прошли judge на реальных данных) поведенческих закономерностей
разных пользователей. Каждая привязана к конкретному user_id.

Сгруппируй их в содержательные ТИПЫ паттернов — не дословно, а по сути механики, которая могла бы
стать переиспользуемым шаблоном сигнала в продукте (пример типов: «диапазон экрана ↔ настроение»,
«конкретное приложение формирует пик экрана», «регулярность времени первой разблокировки»,
«co-occurrence двух приложений», «активность падает/сдвигается со временем», «тег чек-ина ↔
приложение» и т.п.). Один claim может подходить только под один тип (выбери ближайший).

Верни СТРОГО JSON без пояснений:
{"types": [{"name": "...", "description": "суть механики одной фразой", "users": ["u_...", ...], "count": N}],
 "notes": "короткое наблюдение, если что-то не кластеризуется или выделяется"}
Сортируй types по count убыванию. ОТВЕЧАЙ ТОЛЬКО JSON. Первый символ ответа — {."""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", required=True, help="файлы в data/ через запятую")
    ap.add_argument("--model", default="gemini-3.1-pro-preview")
    ap.add_argument("--max-tokens", type=int, default=4000)
    args = ap.parse_args()

    grounded = []
    for fname in args.validate.split(","):
        rows = json.load(open(f"{HERE}/data/{fname.strip()}", encoding="utf-8"))
        for r in rows:
            v = r.get("verdict") or {}
            if v.get("grounded") == 1:
                grounded.append({"user": r["user_id"], "claim": r["claim"], "strength": v.get("strength", "")})

    print(f"=== кластеризация {len(grounded)} grounded-кандидатов, модель={args.model} ===")
    lines = [f"- user={g['user']} strength={g['strength']}: {g['claim']}" for g in grounded]
    user_msg = "Кандидаты:\n" + "\n".join(lines)
    msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": user_msg}]
    resp = llm_patient(args.model, msgs, max_tokens=args.max_tokens)
    text = resp["choices"][0]["message"].get("content") or ""
    data = extract_json(text)
    if data is None:
        sys.exit(f"не удалось распарсить ответ:\n{text[:2000]}")

    out_path = f"{HERE}/data/clusters.json"
    json.dump(data, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for t in data.get("types", []):
        print(f"\n[{t.get('count', len(t.get('users', [])))}] {t.get('name', '')}")
        print(f"    {t.get('description', '')}")
        print(f"    users: {', '.join(u[:12] for u in t.get('users', []))}")
    if data.get("notes"):
        print(f"\nnotes: {data['notes']}")
    print(f"\n-> {out_path}")


if __name__ == "__main__":
    main()
