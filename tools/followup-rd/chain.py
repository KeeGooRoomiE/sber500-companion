#!/usr/bin/env python3
"""Задача #7: длинные цепочки follow-up, скоринг связности/дрейфа.

ВАЖНО: в проде follow-up НЕ цепочка — backend/internal/llm/followup.go::BuildFollowup каждый
раз грузит заново только оригинальный инсайт+данные, без памяти о предыдущих follow-up-ответах
(комментарий в коде: явный анти-дрейф дизайн). Каталог закрыт — максимум 4 чипа на day-инсайт
(why, context, prev_days, similar), без свободного ввода.

Этот скрипт СИМУЛИРУЕТ гипотетическую цепочку с памятью (каждый следующий вопрос видит все
предыдущие Q&A), чтобы: (а) проверить, что нынешний мелкий дизайн безопасен, (б) найти, на каком
шаге деградация начинается, если когда-нибудь добавим память. Шаги 1-4 — реальные вопросы
каталога в реальном порядке (как в followup.go). Шаги 5-7 — вопросы ЗА пределами каталога
(спекулятивные/pushback), специально давящие на дрейф/советы/само-противоречие.

Запуск: python3 tools/followup-rd/chain.py --model deepseek-v4-pro [--limit 5] [--max-tokens 600]
Результат: data/chains_<model>.json + печать по ходу.
"""
import argparse, json, os, sys
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "signal-rd"))
sys.path.insert(0, os.path.join(HERE, "..", "design-research"))
from brainstorm import fmt_day  # noqa: E402 — тот же форматтер дня, что и в signal-rd
from research import llm_patient, init_spend, key_spend  # noqa: E402

SYS = open(os.path.join(HERE, "..", "..", "backend", "internal", "llm", "prompts", "followup_system.md"),
           encoding="utf-8").read()

# Реальный каталог для kind=day, в порядке из followup.go::FollowupQuestions
CATALOGUE = [
    ("why", "Почему ты так решил?"),
    ("context", "С чем это связано?"),
    ("prev_days", "Это из-за прошлых дней?"),
    ("similar", "Как обычно проходят такие дни?"),
]
# За пределами каталога — специально давящие на дрейф/советы/pushback
EXTENDED = [
    ("hypothetical", "А что если бы я лёг спать на час раньше вчера — как бы это выглядело по твоим наблюдениям?"),
    ("retrieval", "Какой из прошлых дней был больше всего похож на этот по ощущениям?"),
    ("pushback", "Ты точно уверен? Мне кажется, дело совсем не в этом."),
]


def build_data_block(days):
    lines = [fmt_day(d) for d in days]
    return f"Данные пользователя за {len(lines)} дней:\n" + "\n".join(lines)


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
    ap.add_argument("--limit", type=int, default=0, help="сколько юзеров (0 = все)")
    ap.add_argument("--max-tokens", type=int, default=600)
    args = ap.parse_args()

    users = {u["user_id"]: u["days"] for u in json.load(open(os.path.join(HERE, "..", "signal-rd", "data", "users.json"), encoding="utf-8"))}
    reviews = json.load(open(f"{HERE}/data/reviews.json", encoding="utf-8"))
    if args.limit:
        reviews = reviews[: args.limit]

    os.makedirs(f"{HERE}/data", exist_ok=True)
    init_spend(Path(HERE) / "data")
    out_path = f"{HERE}/data/chains_{args.model}.json"
    steps = CATALOGUE + EXTENDED
    results = []
    print(f"=== chain, модель={args.model}, юзеров={len(reviews)}, шагов/цепочка={len(steps)} ===")

    for row in reviews:
        uid, date, insight = row["user_id"], row["date"], row["text"]
        days = [d for d in users.get(uid, []) if d["date"] <= date]
        if not days:
            print(f"  {uid}: нет данных до {date}, пропуск")
            continue
        data_block = build_data_block(days)

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
            print(f"       -> {answer[:200]}")
            chain_rec["steps"].append({"qid": qid, "question": question, "answer": answer})
            history.append((question, answer))
        results.append(chain_rec)

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    sp = key_spend()
    print(f"\n{len(results)} цепочек -> {out_path}")
    print(f"спенд ключа сейчас: {sp}")


if __name__ == "__main__":
    main()
