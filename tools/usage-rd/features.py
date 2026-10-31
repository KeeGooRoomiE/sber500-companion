#!/usr/bin/env python3
"""Общая матрица «пользователь × функция × день» для пунктов 4 и 4.1. Импортируется из heavy.py и longhist.py.
Функции (13): открытие утра, отметка, разбор дня, итоги недели, follow-up, «Хочу ещё», и 7 видов карточек-инсайтов
(midday «как идёт день», stat плитки, tag после отметки, question вопрос дня, profile профиль, retro «как вышло», orbtap орб)."""
import json, collections
from datetime import datetime, date
from zoneinfo import ZoneInfo
HERE = __file__.rsplit("/", 1)[0]; MSK = ZoneInfo("Europe/Moscow")
L = lambda n: [json.loads(l) for l in open(f"{HERE}/data/{n}.jsonl", encoding="utf-8")]
def day(s): return datetime.fromisoformat(s).astimezone(MSK).date()

FEATURES = ["утро открыто", "отметка", "разбор дня", "итоги недели", "follow-up", "Хочу ещё",
            "как идёт день", "плитки", "тег после отметки", "вопрос дня", "профиль", "как вышло", "орб"]
def review_feature(kind):
    if kind == "day": return "разбор дня"
    if kind == "week": return "итоги недели"
    if kind.startswith("fu:"): return "follow-up"
    if kind == "in:midday": return "как идёт день"
    if kind.startswith("in:stat"): return "плитки"
    if kind.startswith("in:tag"): return "тег после отметки"
    if kind.startswith("in:question"): return "вопрос дня"
    if kind.startswith("in:profile"): return "профиль"
    if kind.startswith("in:retro"): return "как вышло"
    if kind.startswith("in:orbtap"): return "орб"
    return None

def load():
    """Возвращает: users, active (u→set дат), feat (u→дата→Counter(функция)), llm_user (u→дата→число LLM-вызовов по инициативе человека),
    llm_all (u→дата→все LLM), calls (список вызовов)."""
    calls = L("calls"); reviews = L("reviews"); explore = L("explore"); checkins = L("checkins"); activity = L("activity")
    active = collections.defaultdict(set)
    for a in activity: active[a["u"]].add(date.fromisoformat(a["date"][:10]))
    feat = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    for c in calls:
        if c["comp"] == "morning_api" and c["trig"] == "user_action": feat[c["u"]][day(c["ts"])]["утро открыто"] += 1
    for c in checkins: feat[c["u"]][day(c["created"])]["отметка"] += 1
    for r in reviews:
        f = review_feature(r["kind"])
        if f: feat[r["u"]][day(r["created"])][f] += 1
    for e in explore: feat[e["u"]][day(e["created"])]["Хочу ещё"] += 1
    llm_user = collections.defaultdict(collections.Counter); llm_all = collections.defaultdict(collections.Counter)
    for c in calls:
        if c["type"] == "llm":
            d = day(c["ts"]); llm_all[c["u"]][d] += 1
            if c["trig"] in ("user_action", "followup"): llm_user[c["u"]][d] += 1
    users = {u["u"]: day(u["created"]) for u in L("users")}
    return users, active, feat, llm_user, llm_all, calls

if __name__ == "__main__":
    import statistics as st
    users, active, feat, llm_user, llm_all, calls = load()
    n_act = {u: len(d) for u, d in active.items()}
    print(f"пользователей с активностью: {len(n_act)}; активных дней: медиана {st.median(n_act.values())}, макс {max(n_act.values())}")
    for k in (5, 7, 14, 21):
        print(f"  ≥{k} активных дней: {sum(1 for v in n_act.values() if v >= k)}")
    cover = {u: len({f for d in feat[u].values() for f in d}) for u in active}
    print("покрытие функций (число из 13, когда-либо использованных):", dict(sorted(collections.Counter(cover.values()).items())))
    per_day = {}
    for u in active:
        ds = active[u]; tot = sum(llm_user[u][d] for d in ds)
        per_day[u] = tot / len(ds) if ds else 0
    vals = sorted(per_day.values()); q = lambda p: vals[int(p * (len(vals) - 1))]
    print(f"LLM по инициативе человека на активный день: медиана {q(.5):.1f}, p75 {q(.75):.1f}, p90 {q(.9):.1f}, макс {vals[-1]:.1f}")
    print("  пользователей: ≥3 в день:", sum(v >= 3 for v in vals), "| ≥5:", sum(v >= 5 for v in vals), "| ≥10:", sum(v >= 10 for v in vals))
    lh = [u for u, v in n_act.items() if v >= 14]
    print(f"долгая история (≥14 активных дней): {len(lh)}; покрытие у них:", dict(sorted(collections.Counter(cover[u] for u in lh).items())))
