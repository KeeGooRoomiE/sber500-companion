#!/usr/bin/env python3
"""budget-guard: следит за расходом ключа прокси. При достижении CUTOFF останавливает локальные LLM-задачи
(аудит, prompt-lab, A/B) и пишет data/CUTOFF.flag. Дальше ничего не перезапускает: возобновление вручную.

ENV: LLM_API_KEY, LLM_BASE_URL (run через `bash tools/budget-guard/run.sh`), CUTOFF (50000), WARN (45000),
EVERY (300 с), HOURS (12). Лог в stdout: HEARTBEAT / WARN / CUTOFF / BLIND.
"""
import json, os, subprocess, sys, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
KEY = os.environ["LLM_API_KEY"]; BASE = os.environ["LLM_BASE_URL"].rstrip("/").removesuffix("/v1")
CUTOFF = float(os.getenv("CUTOFF", "50000")); WARN = float(os.getenv("WARN", "45000"))
EVERY = int(os.getenv("EVERY", "300")); HOURS = float(os.getenv("HOURS", "12"))
KILL = ["audit_waves.py", "audit.py", "prompt-lab/lab.py", "prompt-lab/data/seq.sh", "weekly_ab.py", "llm_eval", "eval_harness.py"]

def spend():
    req = urllib.request.Request(f"{BASE}/key/info", headers={"Authorization": f"Bearer {KEY}"})
    for _ in range(3):
        try:
            d = json.loads(urllib.request.urlopen(req, timeout=25).read())["info"]
            return float(d["spend"]), float(d.get("max_budget") or 0)
        except Exception:
            time.sleep(4)
    return None, None

def log(tag, msg): print(f"{time.strftime('%d.%m %H:%M')} {tag} {msg}", flush=True)

end = time.time() + HOURS * 3600
hist = []; last_hb = 0; warned = False; blind_since = None
log("START", f"cutoff={CUTOFF:.0f} warn={WARN:.0f} каждые {EVERY}с, {HOURS:.0f}ч")
while time.time() < end:
    s, mx = spend()
    now = time.time()
    if s is None:
        blind_since = blind_since or now
        if now - blind_since > 1800: log("BLIND", "key/info недоступен >30 минут, расход неизвестен")
    else:
        blind_since = None
        hist.append((now, s)); hist = [h for h in hist if now - h[0] <= 3600]
        rate = (s - hist[0][1]) / max((now - hist[0][0]) / 3600, 0.05) if len(hist) > 1 else 0.0
        if now - last_hb >= 3600:
            log("HEARTBEAT", f"spend={s:.0f} из {mx:.0f}, осталось до cutoff {CUTOFF - s:.0f}, темп ~{rate:.0f} ₽/ч")
            last_hb = now
        if s >= CUTOFF:
            log("CUTOFF", f"spend={s:.0f} >= {CUTOFF:.0f}: останавливаю локальные LLM-задачи")
            for pat in KILL: subprocess.run(["pkill", "-f", pat], capture_output=True)
            open(f"{HERE}/data/CUTOFF.flag", "w").write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} spend={s:.0f} cutoff={CUTOFF:.0f}\n")
            sys.exit(0)
        if s >= WARN and not warned:
            log("WARN", f"spend={s:.0f}, до cutoff {CUTOFF - s:.0f}, темп ~{rate:.0f} ₽/ч"); warned = True
        elif rate > 0 and (CUTOFF - s) / rate < 2 and (CUTOFF - s) > 0:
            log("WARN", f"ПРОГНОЗ: при темпе {rate:.0f} ₽/ч cutoff через {(CUTOFF - s) / rate:.1f} ч (spend={s:.0f})")
    time.sleep(EVERY)
log("END", "срок наблюдения истёк без превышения cutoff")
