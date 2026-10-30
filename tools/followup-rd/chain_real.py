#!/usr/bin/env python3
"""То же, что chain.py, но на БАЙТ-В-БАЙТ реальных серверных промтах (data/real_prompts.json,
см. fetch_real_prompt.py) вместо приближения через signal-rd/fmt_day. Только для юзеров, у
которых есть day_review ровно на серверное "вчера" — это даёт точный контроль: если модель
галлюцинирует даже на полном prod-контексте, вопрос "может, мы просто не доложили данных" снят.

Запуск: python3 tools/followup-rd/chain_real.py --model gigachat-3-pro [--max-tokens 600]
Результат: data/chains_real_<model>.json + печать по ходу.
"""
import argparse, json, os, sys
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "design-research"))
from research import llm_patient, init_spend, key_spend  # noqa: E402

SYS = open(os.path.join(HERE, "..", "..", "backend", "internal", "llm", "prompts", "followup_system.md"),
           encoding="utf-8").read()

CATALOGUE = [
    ("why", "Почему ты так решил?"),
    ("context", "С чем это связано?"),
    ("prev_days", "Это из-за прошлых дней?"),
    ("similar", "Как обычно проходят такие дни?"),
]
EXTENDED = [
    ("hypothetical", "А что если бы я лёг спать на час раньше вчера — как бы это выглядело по твоим наблюдениям?"),
    ("retrieval", "Какой из прошлых дней был больше всего похож на этот по ощущениям?"),
    ("pushback", "Ты точно уверен? Мне кажется, дело совсем не в этом."),
]


def drop_final_instruction(p):
    """Как dropFinalInstruction в backend/internal/llm/followup.go: срезать последнюю строку-
    инструкцию («Объясни этот день.») по последнему пустому переносу."""
    t = p.rstrip()
    i = t.rfind("\n\n")
    return t[:i].rstrip() if i > 0 else t


def build_step_prompt(data_block, insight, history, question):
    parts = [data_block, "", f"Ты уже сказал человеку:\n«{insight.strip()}»"]
    if history:
        parts.append("\nДалее был такой диалог уточнений:")
        for q, a in history:
            parts.append(f"Человек: «{q}»\nТы: «{a}»")
    parts.append(f"\nТеперь человек уточняет: «{question}»\n\nОтветь только на это уточнение.")
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--max-tokens", type=int, default=600)
    args = ap.parse_args()

    real = json.load(open(f"{HERE}/data/real_prompts.json", encoding="utf-8"))
    os.makedirs(f"{HERE}/data", exist_ok=True)
    init_spend(Path(HERE) / "data")
    out_path = f"{HERE}/data/chains_real_{args.model}.json"
    steps = CATALOGUE + EXTENDED
    results = []
    print(f"=== chain_real (byte-exact prod prompt), модель={args.model}, юзеров={len(real)}, шагов/цепочка={len(steps)} ===")

    for row in real:
        uid, date, insight = row["user_id"], row["date"], row["insight"]
        data_block = drop_final_instruction(row["prompt"])
        print(f"\n--- {uid} ({date}) ---")
        print(f"  исходный инсайт: {insight[:100]}...")
        history = []
        chain_rec = {"user_id": uid, "date": date, "insight": insight, "model": args.model, "steps": []}
        for qid, question in steps:
            prompt = build_step_prompt(data_block, insight, history, question)
            msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": prompt}]
            try:
                resp = llm_patient(args.model, msgs, max_tokens=args.max_tokens)
            except Exception as e:
                print(f"    [{qid}] ОШИБКА {e}")
                chain_rec["steps"].append({"qid": qid, "question": question, "answer": None, "err": str(e)})
                break
            answer = resp["choices"][0]["message"].get("content") or ""
            if not answer:
                usage = resp.get("usage", {})
                rt = usage.get("completion_tokens_details", {}).get("reasoning_tokens", "?")
                print(f"    [{qid}] ПУСТОЙ ответ (reasoning_tokens={rt}/{args.max_tokens}) — обрываю цепочку")
                chain_rec["steps"].append({"qid": qid, "question": question, "answer": None, "err": "empty_content"})
                break
            answer = answer.strip()
            print(f"    [{qid}] «{question}»")
            print(f"       -> {answer[:220]}")
            chain_rec["steps"].append({"qid": qid, "question": question, "answer": answer})
            history.append((question, answer))
        results.append(chain_rec)

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    sp = key_spend()
    print(f"\n{len(results)} цепочек -> {out_path}")
    print(f"спенд ключа сейчас: {sp}")


if __name__ == "__main__":
    main()
