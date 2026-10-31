#!/usr/bin/env python3
"""Задача #2: latency/cost/quality по моделям → рекомендация прод-модели.

Честный бенчмарк на РЕАЛЬНЫХ prod-промтах (tools/followup-rd/data/real_prompts.json —
байт-в-байт с сервера) и РЕАЛЬНОМ прод-лимите токенов (DayReviewLimits.Tokens=260,
backend/internal/forecast/review.go) — отвечает на вопрос «что будет, если поставить эту
модель сегодня без правок кода», а не на «какая модель лучше при щедром бюджете».

Один вызов — одна попытка, без ретраев/fallback (research.py::llm_patient маскирует именно
то, что тут нужно увидеть: реальную latency и реальные отказы). Кост считается через дельту
/key/info до/после (как init_spend/guard в research.py), не оценкой по токенам.

Запуск: python3 tools/model-compare/bench.py --model claude-sonnet-4.6 [--n 8] [--max-tokens 260]
Результат: data/bench_<model>.json + печать по ходу.
"""
import argparse, json, os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")
PROXY_ROOT = BASE_URL.rstrip("/v1").rstrip("/")

SYS = open(os.path.join(HERE, "..", "..", "backend", "internal", "llm", "prompts", "day_review_system.md"),
           encoding="utf-8").read()
REAL_PROMPTS = json.load(open(os.path.join(HERE, "..", "followup-rd", "data", "real_prompts.json"), encoding="utf-8"))


def key_spend(retries=4):
    """Только для учёта кост до/после — не часть замера latency, поэтому ретраим:
    прокси общий и под нагрузкой от параллельных прогонов (несколько форков разом)."""
    req = urllib.request.Request(f"{PROXY_ROOT}/key/info", headers={"Authorization": f"Bearer {API_KEY}"})
    for i in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return float(json.loads(r.read())["info"]["spend"])
        except Exception:
            if i == retries - 1:
                raise
            time.sleep(5)


CONN_ERRORS = ("Broken pipe", "Connection reset", "Errno 32", "Errno 54", "Errno 60", "Errno 61")


def call_once(model, user, max_tokens, timeout=60, conn_retries=3):
    """Один логический замер = latency одного ответа модели. Ретраим ТОЛЬКО обрывы ДО того,
    как запрос ушёл на прокси (broken pipe/connection reset — транзитная перегрузка общего
    прокси от параллельных прогонов, не имеет отношения к модели) — иначе момент перегрузки
    у одной модели нечестно портит её тайминги относительно других. Таймаут/медленный ответ
    ПОСЛЕ отправки — настоящая latency-данные, не ретраим."""
    payload = json.dumps({"model": model, "messages": [{"role": "system", "content": SYS},
                          {"role": "user", "content": user}], "max_tokens": max_tokens}).encode()
    for attempt in range(conn_retries):
        req = urllib.request.Request(f"{BASE_URL}/chat/completions", data=payload, method="POST",
              headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                d = json.loads(r.read())
            latency_ms = (time.time() - t0) * 1000
            content = d["choices"][0]["message"].get("content") or ""
            usage = d.get("usage", {})
            rt = usage.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
            return {"ok": bool(content), "latency_ms": round(latency_ms), "chars": len(content),
                    "prompt_tokens": usage.get("prompt_tokens"), "completion_tokens": usage.get("completion_tokens"),
                    "reasoning_tokens": rt, "err": None if content else "empty_content"}
        except Exception as e:
            elapsed_ms = (time.time() - t0) * 1000
            msg = f"{e.__class__.__name__}: {e}"
            is_conn_err = elapsed_ms < 500 and any(s in msg for s in CONN_ERRORS)
            if is_conn_err and attempt < conn_retries - 1:
                time.sleep(3)
                continue
            return {"ok": False, "latency_ms": round(elapsed_ms), "err": msg[:150],
                     "conn_retries_used": attempt}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--n", type=int, default=9, help="вызовов (циклом по 3 реальным промтам)")
    ap.add_argument("--max-tokens", type=int, default=260, help="прод-лимит DayReviewLimits по умолчанию")
    args = ap.parse_args()

    os.makedirs(f"{HERE}/data", exist_ok=True)
    spend_before = key_spend()
    print(f"=== bench, модель={args.model}, n={args.n}, max_tokens={args.max_tokens} (прод-лимит) ===")
    print(f"спенд до: {spend_before:.2f}")

    results = []
    for i in range(args.n):
        row = REAL_PROMPTS[i % len(REAL_PROMPTS)]
        r = call_once(args.model, row["prompt"], args.max_tokens)
        r["uid"] = row["user_id"]
        results.append(r)
        tag = "OK" if r["ok"] else f"FAIL({r['err']})"
        extra = f", reasoning_tokens={r.get('reasoning_tokens', 0)}" if r.get("reasoning_tokens") else ""
        print(f"  [{i+1}/{args.n}] {tag}  {r['latency_ms']}ms{extra}")

    spend_after = key_spend()
    cost_total = spend_after - spend_before
    cost_per_call = cost_total / args.n if args.n else 0
    ok = [r for r in results if r["ok"]]
    lat = sorted(r["latency_ms"] for r in ok)
    def pct(p):
        if not lat:
            return None
        idx = min(len(lat) - 1, int(len(lat) * p))
        return lat[idx]

    summary = {
        "model": args.model, "n": args.n, "max_tokens": args.max_tokens,
        "ok_rate": len(ok) / args.n if args.n else 0,
        "p50_ms": pct(0.5), "p95_ms": pct(0.95), "max_ms": lat[-1] if lat else None,
        "cost_total_rub": round(cost_total, 3), "cost_per_call_rub": round(cost_per_call, 4),
        "results": results,
    }
    out_path = f"{HERE}/data/bench_{args.model}.json"
    json.dump(summary, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\nok_rate={summary['ok_rate']:.0%}  p50={summary['p50_ms']}ms  p95={summary['p95_ms']}ms  "
          f"max={summary['max_ms']}ms  cost/call={summary['cost_per_call_rub']:.4f}₽")
    print(f"-> {out_path}")


if __name__ == "__main__":
    main()
