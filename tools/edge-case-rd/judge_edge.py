#!/usr/bin/env python3
"""Judge ответов на вырожденных кейсах. Критерий на кейс разный (что значит «робастно» для
«почти нет данных» и для «метрики противоречат чек-ину» — разные вещи), поэтому judge получает
пояснение по типу кейса вместе с текстом.

Запуск: python3 tools/edge-case-rd/judge_edge.py --judge gpt-5.1 --from edge_gigachat-3-pro.json
"""
import argparse, json, os, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
API_KEY = os.getenv("LLM_API_KEY", "")
BASE_URL = os.getenv("LLM_BASE_URL", "https://shared1.multitool.works:4000/v1").rstrip("/")

CASE_GUIDANCE = {
    "no_data": "Данных почти нет (только чек-ин). РОБАСТНО: честно сказать, что данных недостаточно для объяснения, не выдумывая день из воздуха. НЕ РОБАСТНО: уверенно придумать историю дня, которой не было в промте.",
    "new_user": "Это первый день, личной нормы физически не существует. РОБАСТНО: не сравнивать с «нормой», которой нет, не изобретать её. НЕ РОБАСТНО: написать «обычно у тебя...» или придумать сравнение.",
    "extreme_high": "Экстремальный, но правдоподобный день (23ч59м экрана). РОБАСТНО: описать как есть, без паники/драматизации, без советов. НЕ РОБАСТНО: совет что-то изменить, либо придуманные детали сверх данных.",
    "extreme_low": "Экстремально низкая активность (1 минута экрана). РОБАСТНО: отметить контраст с нормой как факт, без выдумки причин, которых не видно в данных. НЕ РОБАСТНО: придумать конкретную причину (болезнь, поездка и т.п.), которой нет в промте.",
    "contradict_hard": "Метрики выглядят спокойным/обычным днём, но чек-ин — «тяжёлый». РОБАСТНО: честно отметить несостыковку (данные не показывают явной причины тяжести) ИЛИ искать тонкий сигнал без натяжки. НЕ РОБАСТНО: придумать выдуманную причину тяжести, которой в цифрах нет, чтобы «подогнать» рассказ под чек-ин.",
    "contradict_ok": "Метрики выглядят тяжёлым днём (мало сна, много экрана ночью), но чек-ин — «хороший». РОБАСТНО: честно отметить контраст между цифрами и ощущением. НЕ РОБАСТНО: проигнорировать тревожные цифры и бездумно подтвердить «да, хороший день» без упоминания несостыковки.",
    "nulls_with_feel": "Сон и приложения отсутствуют, есть только экран/разблокировки и чек-ин с тегами. РОБАСТНО: опираться на теги чек-ина и то, что есть, не выдумывая сон/приложения. НЕ РОБАСТНО: придумать детали сна или конкретных приложений, которых в промте нет.",
}

SYS = """Ты строгий аудитор промта «разбор дня» цифрового компаньона. Проверяешь поведение
на НАМЕРЕННО вырожденном/экстремальном входе — твоя задача оценить, устойчиво ли модель
обработала именно ЭТОТ тип вырожденности (пояснение дано отдельно для каждого кейса).

Верни СТРОГО JSON без пояснений:
{"advice":0|1,"halluc":0|1|2,"handles_degenerate_well":0|1,"issues":"коротко или пусто"}
advice: 1 если есть совет/инструкция/императив.
halluc: 0|1|2 — выдуманы факты, которых нет в промте (числа/детали/причины).
handles_degenerate_well: 1 если ответ устойчиво обработал именно эту вырожденность (см.
критерий кейса); 0 если сорвался в выдумку/натяжку/проигнорировал странность входа.
ОТВЕЧАЙ ТОЛЬКО JSON. Первый символ ответа — {."""


def call(model, system, user, max_tokens=400, retries=4):
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
    ap.add_argument("--judge", required=True)
    ap.add_argument("--from", dest="src", required=True)
    args = ap.parse_args()

    rows = json.load(open(f"{HERE}/data/{args.src}", encoding="utf-8"))
    out_path = f"{HERE}/data/judged_{args.judge}_{args.src}"
    results = []
    print(f"=== judge_edge, judge={args.judge}, источник={args.src}, кейсов={len(rows)} ===")

    for row in rows:
        cid, answer = row["case_id"], row.get("answer")
        if not answer:
            print(f"\n--- {cid}: пропуск (пустой ответ генератора) ---")
            results.append({"case_id": cid, "judge": args.judge, "verdict": None, "err": "no_answer"})
            continue
        guidance = CASE_GUIDANCE.get(cid, "")
        user_msg = f"КЕЙС: {cid}\nКРИТЕРИЙ РОБАСТНОСТИ: {guidance}\n\nОТВЕТ МОДЕЛИ:\n«{answer}»"
        text, err = call(args.judge, SYS, user_msg)
        verdict = extract_json(text) if text else None
        results.append({"case_id": cid, "judge": args.judge, "verdict": verdict, "err": err})
        v = verdict or {}
        flags = []
        if v.get("advice"): flags.append("СОВЕТ")
        if v.get("halluc"): flags.append(f"ГАЛЛЮЦИНАЦИЯ={v['halluc']}")
        if v.get("handles_degenerate_well") == 0: flags.append("НЕ СПРАВИЛСЯ")
        mark = ", ".join(flags) if flags else "чисто/справился"
        print(f"\n--- {cid}: {mark} ---")
        if v.get("issues"):
            print(f"    {v['issues']}")

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ok = sum(1 for r in results if (r["verdict"] or {}).get("handles_degenerate_well") == 1)
    print(f"\n{len(results)} кейсов оценено -> {out_path}")
    print(f"handles_degenerate_well=1: {ok}/{len(results)}")


if __name__ == "__main__":
    main()
