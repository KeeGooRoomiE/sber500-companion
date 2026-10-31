#!/usr/bin/env python3
"""R&D #5: кандидатные поведенческие сигналы/паттерны на РЕАЛЬНЫХ данных пользователей.

Для каждого пользователя (из data/users.json, см. fetch.py) просит модель найти 2-3
кандидатных закономерности, обоснованных конкретными числами. Один вызов на юзера,
строго последовательно (не параллелим модели и юзеров — общий ключ, прокси нестабилен).

Запуск: python3 tools/signal-rd/brainstorm.py --model deepseek-v4-pro [--limit 5]
Результат: data/brainstorm_<model>.json + печать в stdout по ходу.
"""
import argparse, json, os, re, sys
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "design-research"))
from research import llm_patient, extract_json, init_spend, key_spend  # noqa: E402

_CLAIM_RE = re.compile(r'"claim"\s*:\s*"((?:[^"\\]|\\.)*)"')
_CONF_RE = re.compile(r'"confidence"\s*:\s*"(\w+)"')
_EV_RE = re.compile(r'"evidence"\s*:\s*(.*?)\s*,\s*"confidence"', re.S)


def salvage_patterns(raw):
    """Падает извлечение валидного JSON (битые/лишние скобки на конце у некоторых моделей,
    особенно gigachat) — но поля внутри обычно целые. Вытаскиваем claim/evidence/confidence
    по порядку появления, не полагаясь на валидность скобочной структуры документа."""
    claims = list(_CLAIM_RE.finditer(raw))
    confs = list(_CONF_RE.finditer(raw))
    evs = list(_EV_RE.finditer(raw))
    out = []
    for i, cm in enumerate(claims):
        evidence = evs[i].group(1).strip().strip('"[] ') if i < len(evs) else ""
        conf = confs[i].group(1) if i < len(confs) else "?"
        out.append({"claim": cm.group(1), "evidence": evidence, "confidence": conf, "_salvaged": True})
    return out

SYS = """Ты аналитик поведенческих паттернов для цифрового компаньона на пассивных данных телефона
(сон, шаги, экранное время, разблокировки, подъём/отбой, топ-приложения, вечерний чек-ин настроения ok/meh/hard).

Тебе дают реальную историю ОДНОГО человека по дням. Найди 2-3 кандидатных закономерности,
которые можно превратить в продуктовый инсайт вида «X обычно связано с Y». Каждая закономерность
должна явно опираться на КОНКРЕТНЫЕ числа и даты из данных. Если по паре дней не видно системной
связи — не выдумывай, лучше верни меньше паттернов или ни одного.

НИКОГДА: советов, инструкций, рекомендаций. Только наблюдение закономерности.

Верни СТРОГО JSON без пояснений:
{"patterns": [{"claim": "кратко формулировка закономерности", "evidence": "конкретные даты и числа, подтверждающие claim", "confidence": "high|medium|low"}]}
Если закономерностей нет: {"patterns": []}
ОТВЕЧАЙ ТОЛЬКО JSON. Первый символ ответа — {."""


def fmt_day(d):
    def hm(t):
        return (t or "")[:5]
    apps = d.get("top_apps") or []
    if isinstance(apps, str):
        try:
            apps = json.loads(apps)
        except Exception:
            apps = []
    apps_s = ", ".join(f"{a.get('name', a.get('package', '?'))} {a.get('minutes', a.get('min', '?'))}м" for a in apps[:3]) if apps else "—"
    feel = f", чек-ин: {d['feel']}" if d.get("feel") else ""
    tags = f" [{', '.join(d['tags'])}]" if d.get("tags") else ""
    return (f"{d['date']}: сон {d.get('sleep_min', '—')}мин, экран {d.get('screen_min', '—')}мин, "
            f"шаги {d.get('steps', '—')}, разблок {d.get('unlocks', '—')}, "
            f"подъём {hm(d.get('wakeup'))}, первая/последняя разблок {hm(d.get('first_unlock'))}/{hm(d.get('last_unlock'))}, "
            f"топ: {apps_s}{feel}{tags}")


def build_user_block(u):
    lines = [fmt_day(d) for d in u["days"]]
    return f"Данные пользователя за {len(lines)} дней:\n" + "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--users-file", default="users.json", help="файл в data/ (см. fetch.py --out)")
    ap.add_argument("--tag", default="", help="суффикс выходного файла, напр. wide")
    ap.add_argument("--limit", type=int, default=0, help="сколько юзеров (0 = все из файла)")
    ap.add_argument("--max-tokens", type=int, default=800,
                     help="reasoning-модели (deepseek/gpt-5.x) тратят большую часть на reasoning_tokens — поднимать до 2500-3000")
    args = ap.parse_args()

    users = json.load(open(f"{HERE}/data/{args.users_file}", encoding="utf-8"))
    if args.limit:
        users = users[: args.limit]

    os.makedirs(f"{HERE}/data", exist_ok=True)
    init_spend(Path(HERE) / "data")
    suffix = f"_{args.tag}" if args.tag else ""
    out_path = f"{HERE}/data/brainstorm_{args.model}{suffix}.json"
    # Возобновляемо: уже обработанных юзеров (из предыдущего прерванного прогона) пропускаем.
    results = []
    done_ids = set()
    if os.path.exists(out_path):
        results = json.load(open(out_path, encoding="utf-8"))
        done_ids = {r["user_id"] for r in results}
        if done_ids:
            print(f"  возобновление: {len(done_ids)} юзеров уже готовы, пропускаю")
    print(f"=== brainstorm, модель={args.model}, юзеров={len(users)} ===")
    for u in users:
        uid = u["user_id"]
        if uid in done_ids:
            continue
        block = build_user_block(u)
        msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": block}]
        try:
            resp = llm_patient(args.model, msgs, max_tokens=args.max_tokens)
        except Exception as e:
            print(f"  {uid}: ОШИБКА {e}")
            continue
        text = resp["choices"][0]["message"].get("content") or ""
        if not text:
            usage = resp.get("usage", {})
            print(f"  {uid}: ПУСТОЙ content (reasoning_tokens={usage.get('completion_tokens_details', {}).get('reasoning_tokens', '?')}, "
                  f"completion_tokens={usage.get('completion_tokens', '?')}/{args.max_tokens}) — пропускаю, увеличьте --max-tokens")
            continue
        data = extract_json(text)
        if data is None:
            salvaged = salvage_patterns(text)
            data = {"patterns": salvaged} if salvaged else {"patterns": [], "_raw": text}
        results.append({"user_id": uid, "model": args.model, "days": len(u["days"]), **data})
        n = len(data.get("patterns", []))
        print(f"  {uid} ({len(u['days'])}д): {n} паттерн(ов)")
        for p in data.get("patterns", []):
            print(f"    [{p.get('confidence', '?')}] {p.get('claim', '')}")
            print(f"       -> {p.get('evidence', '')}")
        # Чекпоинт после каждого юзера — долгий прогон не теряет всё при обрыве/перезагрузке.
        json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    json.dump(results, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    sp = key_spend()
    print(f"\n{len(results)} юзеров обработано -> {out_path}")
    print(f"спенд ключа сейчас: {sp}")


if __name__ == "__main__":
    main()
