#!/usr/bin/env python3
"""Аудит волнами: по 50 случайных текстов из каждой категории за волну, категории по кругу.
Использует judge/парсер/чекпоинты из audit.py (тот же OUT, готовые пары пропускаются)."""
import os, sys, json, random, time, subprocess
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit

WAVE = int(os.getenv("WAVE", "10"))
WORKERS = int(os.getenv("WORKERS", "4"))

def cat(kind):
    if kind.startswith("in:question"): return "in:question"
    if kind.startswith("in:profile"):  return "in:profile"
    if kind.startswith("in:retro"):    return "in:retro"
    if kind.startswith("in:tag"):      return "in:tag"
    if kind.startswith("in:stat"):     return "in:stat"
    if kind.startswith("in:orbtap"):   return "in:orbtap"
    if kind.startswith("fu:day"):      return "fu:day"
    if kind.startswith("fu:week"):     return "fu:week"
    if kind.startswith("explore"):     return "explore"
    return kind  # morning, day, week, in:midday

def main():
    rows = [json.loads(l) for l in open(audit.INP, encoding="utf-8")]
    bycat = {}
    for i, r in enumerate(rows):
        bycat.setdefault(cat(r["kind"]), []).append(i)
    rnd = random.Random(42)
    for c in bycat: rnd.shuffle(bycat[c])
    print("категории:", {c: len(v) for c, v in sorted(bycat.items())}, flush=True)

    done = set()
    if os.path.exists(audit.OUT):
        for l in open(audit.OUT, encoding="utf-8"):
            try:
                r = json.loads(l)
                if not r.get("err"): done.add((r["id"], r["judge"]))
            except Exception: pass

    maxw = max((len(v) + WAVE - 1) // WAVE for v in bycat.values())
    t0 = time.time()
    for w in range(maxw):
        slices = [v[w * WAVE:(w + 1) * WAVE] for v in bycat.values()]
        order = []
        for k in range(WAVE):                     # по кругу: i-й элемент каждой категории
            for s in slices:
                if k < len(s): order.append(s[k])
        items = [(i, rows[i], j) for i in order for j in audit.JUDGES if (i, j) not in done]
        print(f"=== ВОЛНА {w + 1}/{maxw}: {len(order)} текстов, к выполнению {len(items)} вызовов ===", flush=True)
        n = 0
        with ThreadPoolExecutor(WORKERS) as ex:
            for rec in ex.map(audit.task, items):
                n += 1
                if not rec.get("err"): done.add((rec["id"], rec["judge"]))
                if n % 100 == 0: print(f"  волна {w + 1}: {n}/{len(items)}  ({time.time() - t0:.0f}с всего)", flush=True)
        print(f"=== ВОЛНА {w + 1} завершена ({time.time() - t0:.0f}с) ===", flush=True)
        # автосводка: файл на каждую волну + строка «ВСЕ» в лог, чтобы динамику видно без запуска вручную
        try:
            here = os.path.dirname(os.path.abspath(__file__))
            out = subprocess.run([sys.executable, os.path.join(here, "compare_v2.py" if str(audit.INP).endswith("_v2.jsonl") else "summarize.py")], capture_output=True, text=True, timeout=120).stdout
            os.makedirs(os.path.join(here, "data", "summaries"), exist_ok=True)
            open(os.path.join(here, "data", "summaries", f"wave_{w + 1:02d}.txt"), "w", encoding="utf-8").write(out)
            first = out.splitlines()[0] if out else ""
            total = next((l.strip() for l in out.splitlines() if l.strip().startswith("ВСЕ")), "")
            print(f"  СВОДКА ВОЛНЫ {w + 1}: {first} | {total}", flush=True)
        except Exception as e:
            print(f"  (автосводка не удалась: {e})", flush=True)

if __name__ == "__main__":
    main()
