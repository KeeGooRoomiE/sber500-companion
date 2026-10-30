#!/usr/bin/env python3
"""prompt-lab: прогон вариантов системного промта по набору входов через выбранные модели + проверки правил.

  python3 tools/prompt-lab/lab.py --batch data/batch_morning_system.json \
      --variant base=git:HEAD:backend/internal/llm/prompts/morning_system.md \
      --variant draft=/path/to/draft.md \
      --models gigachat-3-pro,deepseek-v4-pro --samples 2

Параметры генерации как в проде: temperature 0.75, max_tokens 100, обрезка до 220 символов.
Проверки — простые правила (не judge): число предложений, длина, условия-советы, личные закономерности.
Ключ и адрес прокси: LLM_API_KEY / LLM_BASE_URL (run.sh берёт их из backend/.env).
"""
import argparse, json, os, re, subprocess, sys, time, urllib.request, collections
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'llm-audit'))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")

CHECKS = {
    "не 2 предложения": lambda t: len(sentences(t)) != 2,
    "длина >220":       lambda t: len(t) > 220,
    "«если»-условие":   lambda t: re.search(r"\bесли\b", t, re.I) is not None,
    "совет-слова":      lambda t: re.search(r"\b(стоит|лучше|следует|рекоменду\w*|попробу\w*|давай\w*|не забуд\w*)\b", t, re.I) is not None,
    "слова-запрета v2": lambda t: re.search(r"\b(если|стоит|лучше|можно|нужно|давай\w*)\b", t, re.I) is not None,
    "личная закономерность": lambda t: re.search(r"(у тебя обычно|твой день обычно|твои вечера|после таких дней ты|тебе это обычно)", t, re.I) is not None,
}

def cap_message(s, max_chars):
    """Как capMessage в backend/internal/llm/morning.go: до max_chars символов и назад до последней . ! ?"""
    if len(s) <= max_chars: return s
    cut = s[:max_chars]
    for i in range(len(cut) - 1, -1, -1):
        if cut[i] in ".!?": return cut[:i + 1].strip()
    return cut.strip()

def sentences(t):
    return [s for s in re.split(r"(?<=[.!?…])\s+", t.strip()) if s]

def load_variant(spec):
    label, src = spec.split("=", 1)
    if src.startswith("git:"):
        _, ref, path = src.split(":", 2)
        text = subprocess.run(["git", "-C", REPO, "show", f"{ref}:{path}"], capture_output=True, text=True).stdout
    else:
        text = open(src, encoding="utf-8").read()
    if not text.strip(): sys.exit(f"пустой промт для варианта {label}")
    return label, text

def gen(model, system, user, temp, max_tokens, retries=4):
    payload = json.dumps({"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                          "max_tokens": max_tokens, "temperature": temp}).encode()
    for a in range(retries):
        try:
            req = urllib.request.Request(f"{BASE_URL}/chat/completions", data=payload, method="POST",
                  headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
            with urllib.request.urlopen(req, timeout=90) as r:
                d = json.loads(r.read())
            return d["choices"][0]["message"]["content"].strip()
        except Exception:
            time.sleep(3 + 3 * a)
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch", required=True)
    ap.add_argument("--variant", action="append", required=True, help="label=путь | label=git:REF:путь")
    ap.add_argument("--models", default="gigachat-3-pro")
    ap.add_argument("--samples", type=int, default=2)
    ap.add_argument("--temp", type=float, default=0.75)
    ap.add_argument("--max-tokens", type=int, default=100)
    ap.add_argument("--cap", type=int, default=220)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--judge", action="store_true", help="оценить ответы двумя judge (gpt-5.1, claude-sonnet-4.6) по ВХОДУ, который видела модель")
    ap.add_argument("--out", default="")
    ap.add_argument("--show", type=int, default=12, help="сколько нарушающих примеров печатать на вариант")
    a = ap.parse_args()
    if not API_KEY: sys.exit("нет LLM_API_KEY (используйте run.sh)")
    batch = json.load(open(a.batch if os.path.isabs(a.batch) else os.path.join(HERE, a.batch), encoding="utf-8"))
    variants = [load_variant(v) for v in a.variant]
    models = a.models.split(",")
    jobs = [(vl, vs, m, it, s) for vl, vs in variants for m in models for it in batch for s in range(a.samples)]
    print(f"персон {len(batch)} × вариантов {len(variants)} × моделей {len(models)} × сэмплов {a.samples} = {len(jobs)} вызовов", flush=True)

    done = [0]
    def run(j):
        vl, vs, m, it, s = j
        t = gen(m, vs, it["user"], a.temp, a.max_tokens)
        raw = t
        if t: t = cap_message(t, a.cap)
        done[0] += 1
        if done[0] % 10 == 0 or done[0] == len(jobs): print(f"  … {done[0]}/{len(jobs)}", flush=True)
        return {"variant": vl, "model": m, "persona": it["user_id"], "sample": s, "text": t, "raw_extra_paragraph": bool(raw and "\n\n" in raw),
                "viol": [k for k, f in CHECKS.items() if t and f(t)] if t else ["нет ответа"]}
    with ThreadPoolExecutor(a.workers) as ex:
        res = list(ex.map(run, jobs))

    if a.judge:
        import audit
        users = {it["user_id"]: it["user"] for it in batch}
        def jrun(r):
            if not r["text"]: return
            msg = f"ТИП: утренний прогноз\n\nТЕКСТ:\n{r['text']}\n\nФАКТЫ (это ровно то, что видела модель):\n{users[r['persona']]}"
            r["judge"] = {}
            for j in audit.JUDGES:
                for _ in range(3):
                    t, _e = audit.call(j, audit.SYS, msg)
                    sc = audit.parse(t) if t else None
                    if sc: r["judge"][j] = sc; break
        print(f"judge-проход: {sum(1 for r in res if r['text'])} ответов × {len(audit.JUDGES)} judge", flush=True)
        with ThreadPoolExecutor(a.workers) as ex: list(ex.map(jrun, res))
    out = a.out or os.path.join(HERE, "data", f"lab_{time.strftime('%Y%m%d_%H%M%S')}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(res, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print("\nНАРУШЕНИЯ (доля среди полученных ответов; сбои прокси считаются отдельно):")
    keys = list(CHECKS)
    print(f"{'вариант / модель':34} {'отв/всего':>9} | " + " | ".join(f"{k[:14]:>14}" for k in keys) + " | лишний абзац")
    for vl, _ in variants:
        for m in models:
            rr = [r for r in res if r["variant"] == vl and r["model"] == m]
            ok = [r for r in rr if r["text"]]
            row = [f"{100 * sum(1 for r in ok if k in r['viol']) / max(len(ok), 1):13.0f}%" for k in keys]
            extra = f"{100 * sum(1 for r in ok if r['raw_extra_paragraph']) / max(len(ok), 1):.0f}%"
            print(f"{vl + ' / ' + m:34} {str(len(ok)) + '/' + str(len(rr)):>9} | " + " | ".join(row) + f" | {extra:>12}")
    if a.judge:
        G, S = "gpt-5.1", "claude-sonnet-4.6"
        print("\nJUDGE (консенсус обоих / любой), среди ответов, оценённых обоими:")
        for vl, _ in variants:
            for m in models:
                rr = [r for r in res if r["variant"] == vl and r["model"] == m and r.get("judge") and G in r["judge"] and S in r["judge"]]
                n = max(len(rr), 1)
                f = lambda k, v, mode: sum(1 for r in rr if (mode == "all" and r["judge"][G].get(k) == v and r["judge"][S].get(k) == v) or (mode == "any" and (r["judge"][G].get(k) == v or r["judge"][S].get(k) == v)))
                print(f"  {vl + ' / ' + m:34} n={len(rr):3} | совет {100*f('advice',1,'all')/n:3.0f}%/{100*f('advice',1,'any')/n:3.0f}% | выдумка {100*f('halluc',2,'all')/n:3.0f}%/{100*f('halluc',2,'any')/n:3.0f}% | шаблон {100*f('generic',2,'any')/n:3.0f}%")
    for vl, _ in variants:
        bad = [r for r in res if r["variant"] == vl and r["text"] and r["viol"]]
        print(f"\n--- {vl}: примеры нарушений ({len(bad)} из {sum(1 for r in res if r['variant'] == vl)}) ---")
        for r in bad[:a.show]:
            print(f"[{r['persona'].replace('mock_', '')} · {r['model']}] {','.join(r['viol'])}\n   {r['text']}")
    print(f"\nсохранено: {out}")

if __name__ == "__main__":
    main()
