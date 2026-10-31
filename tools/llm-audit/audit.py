#!/usr/bin/env python3
"""
Аудит #3: все реальные выходы LLM (2934) -> два независимых judge -> JSONL с чекпоинтами.
Возобновляемый: пары (id, judge), уже записанные в OUT, пропускаются.
ENV: LLM_API_KEY, LLM_BASE_URL (run.sh берёт их из backend/.env). JUDGES / WORKERS / LIMIT можно переопределить через ENV.
"""
import os, sys, json, time, threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor

SCR = os.path.dirname(os.path.abspath(__file__))
INP = os.getenv("AUDIT_IN", f"{SCR}/data/audit_input.jsonl")
OUT = os.getenv("AUDIT_OUT", f"{SCR}/data/audit_results.jsonl")
API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")
JUDGES = os.getenv("JUDGES", "gpt-5.1,claude-sonnet-4.6").split(",")
WORKERS = int(os.getenv("WORKERS", "6"))
LIMIT = int(os.getenv("LIMIT", "0"))

SYS = """Ты строгий аудитор текстов цифрового компаньона, который объясняет день человека по данным его телефона.
Правила компаньона: он НАБЛЮДАТЕЛЬ. НИКОГДА не даёт советов, инструкций, повелительного наклонения («попробуй», «лучше», «стоит», «ложись», «не…») и не выдумывает фактов.

Тебе дадут ТИП текста, САМ ТЕКСТ и ФАКТЫ (данные пользователя за ~8 дней: экран/сон в минутах, разблокировки, шаги, приложения с минутами, чек-ины ok/meh/hard; null = данных нет).
Для вопросов с вариантами ответа (kind in:question, in:profile, explore:*) проверяй в основном тон и совет/не совет.

Верни СТРОГО JSON без пояснений:
{"advice":0|1,"halluc":0|1|2,"generic":0|1|2,"issues":"коротко"}
advice: 1 если есть совет/инструкция/императив/«стоит бы».
halluc: 0 факты подтверждаются данными; 1 мелкая неточность или непроверяемое утверждение; 2 явная выдумка (число/приложение/событие, которых нет в данных или противоречит им). Если данных для проверки нет — 0.
generic: 0 конкретно и по данным; 1 частично шаблонно; 2 пустая общая фраза, подошла бы любому.
issues: главная проблема одной фразой (до 15 слов) или пусто.

ОТВЕЧАЙ ТОЛЬКО JSON-объектом, без рассуждений, пунктов и пояснений. Первый символ ответа — {."""

from datetime import datetime, timezone, timedelta
LOCK = threading.Lock()

def quiet_wait():
    """Не мешаем утреннему батчу прода (04:10 МСК) на общем прокси: пауза 03:55-04:40."""
    while True:
        now = datetime.now(timezone(timedelta(hours=3)))
        m = now.hour * 60 + now.minute
        if 3 * 60 + 55 <= m < 4 * 60 + 40:
            time.sleep(30)
        else:
            return

LIMITS = {"morning": 260, "day": 560, "week": 720}

def call(model, system, user, max_tokens=700, retries=4):
    payload = json.dumps({"model": model, "messages": [{"role": "system", "content": system},
                          {"role": "user", "content": user}], "max_tokens": max_tokens, "temperature": 0.0}).encode()
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

def parse(txt):
    """Берём последний объект с ключом advice: модель могла порассуждать перед JSON."""
    s = txt.strip()
    k = s.rfind('{"advice"')
    if k < 0: k = s.rfind("{")
    while k >= 0:
        e = s.find("}", k)
        while e >= 0:
            try:
                d = json.loads(s[k:e + 1])
                if isinstance(d, dict) and "advice" in d: return d
            except Exception:
                pass
            e = s.find("}", e + 1)
        k = s.rfind("{", 0, k)
    return None

WD = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]

def compact(days):
    out = []
    for d in days:
        apps = ", ".join(f"{a.get('package','?').split('.')[-1]} {a.get('minutes')}м" for a in (d.get("apps") or [])[:4])
        try:
            from datetime import date as _d
            day = f"{d['d']} ({WD[_d.fromisoformat(str(d['d'])[:10]).weekday()]})"
        except Exception:
            day = d['d']
        tags = d.get("tags")
        tag_s = f", теги чек-ина: {', '.join(tags)}" if tags else ""
        out.append(f"{day}: экран {d.get('screen')}м, сон {d.get('sleep')}м, разблок {d.get('unlocks')}, "
                   f"шаги {d.get('steps')}, подъём {d.get('wake')}, [{apps}], чек-ин {d.get('feel')}{tag_s}")
    return "\n".join(out)

V2_NOTE = ("Это полный контекст, который видел и сам генератор текста: ТЕГИ чек-инов, ПРОФИЛЬ человека и НОРМЫ за всю историю тоже являются фактами. "
           "Упоминание тега, рабочего контекста из профиля или сравнение с нормой не выдумка, если согласуется с ними. Дни недели проверяй по датам в скобках; "
           "не называй выдумкой фразы, которые верны в этом контексте. Выдумка это только то, чего нет ни в фактах дней, ни в тегах, ни в профиле, ни в нормах.")

def v2_block(row):
    parts = []
    if row.get("profile"):
        pr = {k: v for k, v in row["profile"].items() if v}
        if pr:
            parts.append("ПРОФИЛЬ: " + "; ".join(f"{k}={v}" for k, v in pr.items()))
    if row.get("norms"):
        n = row["norms"]
        parts.append(f"НОРМЫ по всей истории до даты текста ({n.get('days')} дней): экран {n.get('screen')}м, сон {n.get('sleep')}м, разблок {n.get('unlocks')}, шаги {n.get('steps')}")
    return ("\n\n" + "\n".join(parts) + "\n\n" + V2_NOTE) if parts else ""

def task(item):
    i, row, judge = item
    quiet_wait()
    user = f"ТИП: {row['kind']} (дата текста {row['date']})\n\nТЕКСТ:\n{row['text']}\n\nФАКТЫ:\n{compact(row['days'])}{v2_block(row)}"
    txt, err = call(judge, SYS, user)
    rec = {"id": i, "judge": judge, "kind": row["kind"], "date": row["date"], "chars": len(row["text"])}
    sc = parse(txt) if txt else None
    if sc:
        rec.update({k: sc.get(k) for k in ("advice", "halluc", "generic", "issues")})
    else:
        rec["err"] = err or "parse"
    with LOCK:
        with open(OUT, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return rec

def main():
    if not API_KEY: print("нет LLM_API_KEY"); sys.exit(1)
    rows = [json.loads(l) for l in open(INP, encoding="utf-8")]
    if LIMIT: rows = rows[:LIMIT]
    done = set()
    if os.path.exists(OUT):
        for l in open(OUT, encoding="utf-8"):
            try:
                r = json.loads(l)
                if not r.get("err"): done.add((r["id"], r["judge"]))
            except Exception: pass
    items = [(i, r, j) for i, r in enumerate(rows) for j in JUDGES if (i, j) not in done]
    print(f"к выполнению: {len(items)} из {len(rows) * len(JUDGES)} (уже готово {len(done)}), воркеров {WORKERS}", flush=True)
    n = 0; t0 = time.time()
    with ThreadPoolExecutor(WORKERS) as ex:
        for rec in ex.map(task, items):
            n += 1
            if n % 50 == 0:
                print(f"[{n}/{len(items)}] {time.time() - t0:.0f}с", flush=True)
    print(f"✅ готово: {n} вызовов за {time.time() - t0:.0f}с", flush=True)

if __name__ == "__main__":
    main()
