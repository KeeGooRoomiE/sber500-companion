#!/usr/bin/env python3
"""Judge-валидация кандидатных паттернов из brainstorm.py: подтверждается ли claim реальными
данными, или это натяжка/выдумка. Один judge за прогон (как с генерацией — сначала один, потом
другой, консенсус собирается вручную/в summarize).

Запуск: python3 tools/signal-rd/validate.py --model gpt-5.1 --from brainstorm_deepseek-v4-pro.json
Результат: data/validate_<judge>_<источник>.json + печать в stdout по ходу.
"""
import argparse, json, os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from brainstorm import fmt_day  # noqa: E402  — одна версия форматирования дня, чтобы judge видел то же, что и генератор

API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")

SYS = """Ты строгий аудитор продуктовых гипотез о поведении человека по данным его телефона.
Тебе дают ПРЕТЕНЗИЮ (заявленную закономерность), её ОБОСНОВАНИЕ от автора и ПОЛНЫЕ ДАННЫЕ пользователя по дням.

Проверь: действительно ли ПОЛНЫЕ ДАННЫЕ подтверждают претензию, или это натяжка/совпадение на 1-2 днях/
противоречит части данных/выдуманные числа, которых в данных нет.

Верни СТРОГО JSON без пояснений:
{"grounded":0|1,"strength":"strong|weak|none","issues":"коротко, до 20 слов, или пусто"}
grounded: 1 если претензия точно следует из данных и не противоречит остальным дням; иначе 0.
strength: strong — закономерность видна на многих днях; weak — на 2-3 днях, может быть совпадением; none — не подтверждается.
ОТВЕЧАЙ ТОЛЬКО JSON. Первый символ ответа — {."""


def call(model, system, user, max_tokens=300, retries=4):
    payload = json.dumps({"model": model, "messages": [{"role": "system", "content": system},
                          {"role": "user", "content": user}], "max_tokens": max_tokens, "temperature": 0.0}).encode()
    err = None
    for att in range(retries):
        req = urllib.request.Request(f"{BASE_URL}/chat/completions", data=payload, method="POST",
              headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.loads(r.read())
            return d["choices"][0]["message"]["content"].strip(), None
        except Exception as e:
            err = f"{e.__class__.__name__}"
            time.sleep(2 + 3 * att)
    return None, err


def extract_json(txt):
    if not txt:
        return None
    s, e = txt.find("{"), txt.rfind("}")
    if s < 0 or e < s:
        return None
    try:
        return json.loads(txt[s:e + 1])
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="judge-модель")
    ap.add_argument("--from", dest="src", required=True, help="файл в data/ из brainstorm.py")
    args = ap.parse_args()

    brainstorm = json.load(open(f"{HERE}/data/{args.src}", encoding="utf-8"))
    users = {u["user_id"]: u for u in json.load(open(f"{HERE}/data/users.json", encoding="utf-8"))}

    out_name = f"validate_{args.model}_{args.src}"
    out_path = f"{HERE}/data/{out_name}"
    results = []
    total = sum(len(r.get("patterns", [])) for r in brainstorm)
    print(f"=== validate, judge={args.model}, источник={args.src}, кандидатов={total} ===")
    n = 0
    for row in brainstorm:
        uid = row["user_id"]
        u = users.get(uid)
        if not u:
            continue
        data_block = "\n".join(fmt_day(d) for d in u["days"])
        for p in row.get("patterns", []):
            n += 1
            user_msg = (f"ПРЕТЕНЗИЯ: {p.get('claim', '')}\n"
                        f"ОБОСНОВАНИЕ АВТОРА: {p.get('evidence', '')}\n\n"
                        f"ПОЛНЫЕ ДАННЫЕ ({len(u['days'])} дней):\n{data_block}")
            text, err = call(args.model, SYS, user_msg)
            verdict = extract_json(text) if text else None
            rec = {"user_id": uid, "claim": p.get("claim", ""), "confidence": p.get("confidence", ""),
                   "judge": args.model, "verdict": verdict, "err": err}
            results.append(rec)
            v = verdict or {}
            print(f"  [{n}/{total}] {uid}: grounded={v.get('grounded', '?')} strength={v.get('strength', '?')} — {p.get('claim', '')[:60]}")
            if v.get("issues"):
                print(f"       issues: {v['issues']}")

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ok = sum(1 for r in results if (r["verdict"] or {}).get("grounded") == 1)
    print(f"\n{len(results)} кандидатов -> {out_path}")
    print(f"grounded=1: {ok}/{len(results)}")


if __name__ == "__main__":
    main()
