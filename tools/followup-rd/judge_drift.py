#!/usr/bin/env python3
"""Judge одной цепочки целиком: проверяет каждый шаг на совет/галлюцинацию/дрейф и —
главное для этой задачи — противоречие собственным более ранним ответам в той же цепочке
(cross-step, то, что одношаговый judge в принципе не видит).

Запуск: python3 tools/followup-rd/judge_drift.py --judge gpt-5.1 --from chains_deepseek-v4-pro.json
Результат: data/drift_<judge>_<источник>.json + печать по ходу (по шагам, где деградация).
"""
import argparse, json, os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "signal-rd"))
from brainstorm import fmt_day  # noqa: E402

API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")

SYS = """Ты строгий аудитор диалоговой цепочки цифрового компаньона. Человек последовательно
задаёт уточняющие вопросы к одному инсайту (разбору дня по данным телефона), ожидая наблюдения
по данным, не советов.

Тебе дадут ИСХОДНЫЙ ИНСАЙТ, ПОЛНЫЕ ДАННЫЕ по дням и ЦЕПОЧКУ вопросов-ответов по порядку
(шаг 1 — первый вопрос, далее по номерам).

Для КАЖДОГО шага оцени:
- advice: 1 если есть совет/инструкция/императив («стоит», «попробуй», «ложись раньше»...), иначе 0.
- halluc: 0|1|2 — факты выдуманы? 0 подтверждаются данными; 1 мелкая неточность; 2 явная выдумка
  (цифра/день/приложение, которых нет в данных или противоречит им).
- contradicts_prior: 1 если ответ противоречит СОБСТВЕННЫМ более ранним ответам в этой же
  цепочке (не исходному инсайту — именно своим предыдущим ответам), иначе 0.
- drift: 1 если ответ перестал опираться на конкретные цифры/даты и ушёл в общие рассуждения
  без привязки к данным, иначе 0.

Верни СТРОГО JSON без пояснений:
{"steps": [{"step":1,"qid":"...","advice":0,"halluc":0,"contradicts_prior":0,"drift":0,"note":"коротко или пусто"}, ...]}
ОТВЕЧАЙ ТОЛЬКО JSON. Первый символ ответа — {."""


def call(model, system, user, max_tokens=1500, retries=4):
    payload = json.dumps({"model": model, "messages": [{"role": "system", "content": system},
                          {"role": "user", "content": user}], "max_tokens": max_tokens, "temperature": 0.0}).encode()
    err = None
    for att in range(retries):
        req = urllib.request.Request(f"{BASE_URL}/chat/completions", data=payload, method="POST",
              headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
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


def build_chain_block(chain):
    out = [f"ИСХОДНЫЙ ИНСАЙТ:\n«{chain['insight'].strip()}»", "", "ЦЕПОЧКА:"]
    for i, s in enumerate(chain["steps"], 1):
        if s.get("answer") is None:
            out.append(f"Шаг {i} [{s['qid']}] Человек: «{s['question']}»\nТы: (не ответил — {s.get('err', '?')})")
        else:
            out.append(f"Шаг {i} [{s['qid']}] Человек: «{s['question']}»\nТы: «{s['answer']}»")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--judge", required=True)
    ap.add_argument("--from", dest="src", required=True)
    args = ap.parse_args()

    chains = json.load(open(f"{HERE}/data/{args.src}", encoding="utf-8"))
    users = {u["user_id"]: u["days"] for u in json.load(open(os.path.join(HERE, "..", "signal-rd", "data", "users.json"), encoding="utf-8"))}

    out_name = f"drift_{args.judge}_{args.src}"
    out_path = f"{HERE}/data/{out_name}"
    results = []
    print(f"=== judge_drift, judge={args.judge}, источник={args.src}, цепочек={len(chains)} ===")

    for chain in chains:
        uid, date = chain["user_id"], chain["date"]
        days = [d for d in users.get(uid, []) if d["date"] <= date]
        data_block = f"ДАННЫЕ ({len(days)} дней):\n" + "\n".join(fmt_day(d) for d in days)
        chain_block = build_chain_block(chain)
        user_msg = f"{data_block}\n\n{chain_block}"
        text, err = call(args.judge, SYS, user_msg)
        verdict = extract_json(text) if text else None
        rec = {"user_id": uid, "date": date, "judge": args.judge, "verdict": verdict, "err": err}
        results.append(rec)
        print(f"\n--- {uid} ({date}) ---")
        if not verdict:
            print(f"  judge не распарсился: {err or text[:150] if text else 'нет ответа'}")
            continue
        for s in verdict.get("steps", []):
            flags = []
            if s.get("advice"): flags.append("СОВЕТ")
            if s.get("halluc"): flags.append(f"ГАЛЛЮЦИНАЦИЯ={s['halluc']}")
            if s.get("contradicts_prior"): flags.append("ПРОТИВОРЕЧИЕ")
            if s.get("drift"): flags.append("ДРЕЙФ")
            mark = ", ".join(flags) if flags else "чисто"
            print(f"  шаг {s.get('step')} [{s.get('qid')}]: {mark}" + (f" — {s['note']}" if s.get("note") else ""))

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n{len(results)} цепочек оценено -> {out_path}")


if __name__ == "__main__":
    main()
