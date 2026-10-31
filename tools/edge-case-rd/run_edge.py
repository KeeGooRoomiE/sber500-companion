#!/usr/bin/env python3
"""Задача #6: прогон day_review_system.md на вырожденных/экстремальных входах (cases.py).

Запуск: python3 tools/edge-case-rd/run_edge.py --model gigachat-3-pro [--max-tokens 600]
Результат: data/edge_<model>.json + печать по ходу.
"""
import argparse, json, os, sys
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "design-research"))
from research import llm_patient, init_spend, key_spend  # noqa: E402
from cases import CASES  # noqa: E402

SYS = open(os.path.join(HERE, "..", "..", "backend", "internal", "llm", "prompts", "day_review_system.md"),
           encoding="utf-8").read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--max-tokens", type=int, default=600)
    args = ap.parse_args()

    os.makedirs(f"{HERE}/data", exist_ok=True)
    init_spend(Path(HERE) / "data")
    out_path = f"{HERE}/data/edge_{args.model}.json"
    results = []
    print(f"=== edge-case run, модель={args.model}, кейсов={len(CASES)} ===")

    for case_id, prompt in CASES.items():
        msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": prompt}]
        try:
            resp = llm_patient(args.model, msgs, max_tokens=args.max_tokens)
        except Exception as e:
            print(f"  [{case_id}] ОШИБКА {e}")
            results.append({"case_id": case_id, "model": args.model, "answer": None, "err": str(e)})
            continue
        answer = resp["choices"][0]["message"].get("content") or ""
        if not answer:
            usage = resp.get("usage", {})
            rt = usage.get("completion_tokens_details", {}).get("reasoning_tokens", "?")
            print(f"  [{case_id}] ПУСТОЙ ответ (reasoning_tokens={rt}/{args.max_tokens})")
            results.append({"case_id": case_id, "model": args.model, "answer": None, "err": "empty_content"})
            continue
        answer = answer.strip()
        print(f"\n  [{case_id}] ({len(answer)}симв.)")
        print(f"    {answer}")
        results.append({"case_id": case_id, "model": args.model, "answer": answer})

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    sp = key_spend()
    print(f"\n{len(results)} кейсов -> {out_path}")
    print(f"спенд ключа сейчас: {sp}")


if __name__ == "__main__":
    main()
