#!/usr/bin/env python3
"""Пункт 5: репетиция антифрод-аудита по локальной выгрузке (data/*.jsonl). Текста методики Положения у нас нет,
поэтому проверяются (а) требования из CLAUDE.md и (б) типовые эвристики: DAU без реального использования, концентрация,
всплески, фоновые вызовы для давно неактивных пользователей, сценарии подсчёта «обращений на DAU»."""
import sys, collections, statistics as st
sys.path.insert(0, __file__.rsplit("/", 1)[0])
import features as F
from datetime import date, timedelta, datetime
users, active, feat, llm_user, llm_all, calls = F.load()
daily = F.L("daily")
act_days = {(u, d) for u, ds in active.items() for d in ds}
print(f"DAU-дней всего: {len(act_days)}; пользователей: {len(active)}")

print("\nE) ПОДЛИННОСТЬ DAU")
live = {(d["u"], date.fromisoformat(d["date"][:10])) for d in daily if (d.get("screen") or 0) > 0 or (d.get("unlocks") or 0) > 0 or (d.get("steps") or 0) > 0}
print(f"   E1. DAU-дни, в которых есть живые данные телефона (экран, разблокировки или шаги): {100*len(act_days & live)/len(act_days):.0f}% ({len(act_days & live)} из {len(act_days)})")
by_day = collections.defaultdict(collections.Counter)
for c in calls:
    if c["trig"] in ("user_action", "followup"): by_day[(c["u"], F.day(c["ts"]))][c["comp"]] += 1
only_morning = zero = 0
for k in act_days:
    comps = by_day.get(k, {})
    if not comps: zero += 1
    elif set(comps) <= {"morning_api"}: only_morning += 1
print(f"   E2. DAU-дни, где единственное действие пользователя это запрос утра (мог сделать фоновый будильник, а не человек): {100*only_morning/len(act_days):.0f}% ({only_morning})")
print(f"   E3. DAU-дни без единого логируемого действия (активность только через /ping и подобное): {100*zero/len(act_days):.0f}% ({zero})")
print(f"   E4. DAU-дни с реальным взаимодействием кроме запроса утра (отметка, разбор, чип, «Хочу ещё» и т. п.): {100*(len(act_days)-only_morning-zero)/len(act_days):.0f}%")

print("\nF) КОНЦЕНТРАЦИЯ И ВСПЛЕСКИ (вызовы LLM по инициативе человека, без admin)")
ui = collections.Counter(); recent = collections.Counter(); cutoff = max(F.day(c["ts"]) for c in calls) - timedelta(days=13)
for c in calls:
    if c["type"] == "llm" and c["trig"] in ("user_action", "followup"):
        ui[c["u"]] += 1
        if F.day(c["ts"]) >= cutoff: recent[c["u"]] += 1
for name, cnt in (("за всё время", ui), ("за последние 14 дней", recent)):
    tot = sum(cnt.values()); top = sorted(cnt.values(), reverse=True)
    print(f"   {name}: вызовов {tot}; доля топ-1 {100*top[0]/tot:.0f}%, топ-3 {100*sum(top[:3])/tot:.0f}%, топ-5 {100*sum(top[:5])/tot:.0f}%; пользователей {len(cnt)}")
ts_by_u = collections.defaultdict(list)
for c in calls:
    if c["type"] == "llm" and c["trig"] in ("user_action", "followup"): ts_by_u[c["u"]].append((datetime.fromisoformat(c["ts"]), c["comp"]))
bursty = tot_b = 0; peaks = []
for u, evs in ts_by_u.items():
    evs.sort(); j = 0; peak = 0
    for i, (t, _) in enumerate(evs):
        while (t - evs[j][0]).total_seconds() > 60: j += 1
        peak = max(peak, i - j + 1)
    peaks.append(peak)
mins = collections.Counter()
for u, evs in ts_by_u.items():
    for t, comp in evs: mins[(u, t.replace(second=0, microsecond=0))] += 1
busy = [(k, n) for k, n in mins.items() if n >= 10]
share = sum(n for _, n in busy) / sum(ui.values())
print(f"   максимум вызовов одного пользователя за 60 секунд: {max(peaks)}; пользователей с пиком ≥15: {sum(1 for p in peaks if p >= 15)}; пиком ≥10: {sum(1 for p in peaks if p >= 10)}")
print(f"   «горячих» минут (≥10 вызовов одного пользователя): {len(busy)}; доля вызовов в них от всех по инициативе: {100*share:.0f}%")
div = []
for (u, m), n in busy:
    comps = {c for t, c in ts_by_u[u] if m <= t < m + timedelta(minutes=1)}; div.append(len(comps))
if div: print(f"   в «горячих» минутах разных компонентов: медиана {st.median(div):.0f} (1 = один и тот же компонент повторяется, ≥3 = человек ходит по функциям)")

print("\nH) ФОНОВЫЕ ВЫЗОВЫ ДЛЯ ДАВНО НЕАКТИВНЫХ ПОЛЬЗОВАТЕЛЕЙ (утренняя генерация по расписанию)")
last_day = max(F.day(c["ts"]) for c in calls)
def inactive_for(u, d, n):
    return not any((d - timedelta(days=k)) in active[u] for k in range(0, n + 1))
tot = bad3 = bad7 = 0; days = collections.Counter()
for c in calls:
    if c["type"] == "llm" and c["trig"] == "scheduled":
        d = F.day(c["ts"]); u = c["u"]; tot += 1
        i3 = inactive_for(u, d, 3); i7 = inactive_for(u, d, 7)
        bad3 += i3; bad7 += i7
print(f"   фоновых LLM-вызовов всего {tot}; для людей без активности 3 дня и дольше: {bad3} ({100*bad3/tot:.0f}%); 7 дней и дольше: {bad7} ({100*bad7/tot:.0f}%)")
zomb = {c["u"] for c in calls if c["type"] == "llm" and c["trig"] == "scheduled" and inactive_for(c["u"], F.day(c["ts"]), 7)}
print(f"   пользователей, для которых генерация продолжалась при 7+ днях неактивности: {len(zomb)} из {len(active)}")

print("\nС) СЦЕНАРИИ ПОДСЧЁТА «ОБРАЩЕНИЙ НА DAU» (последние 14 дней, делитель = пользователь-дни DAU)")
win = {(u, d) for (u, d) in act_days if d >= cutoff}; dd = len(win)
allc = [c for c in calls if c["type"] == "llm" and F.day(c["ts"]) >= cutoff]
sc = {
    "а) все LLM-вызовы пользователей (как на странице метрик)": len(allc),
    "б) только по инициативе человека (user_action + followup)": sum(1 for c in allc if c["trig"] in ("user_action", "followup")),
    "в) только по инициативе и только у DAU-пользователей в этот день": sum(1 for c in allc if c["trig"] in ("user_action", "followup") and (c["u"], F.day(c["ts"])) in win),
    "г) все вызовы, но без фоновой генерации (scheduled)": sum(1 for c in allc if c["trig"] != "scheduled"),
}
for k, v in sc.items(): print(f"   {k}: {v} вызовов → {v/dd:.2f} на DAU (DAU-дней {dd})")
print("   + 94 вызова admin (оценка промтов, user_action, user_visible=false) лежат в журнале отдельно и в расчёт выше не входят")
