#!/usr/bin/env python3
"""Пункт 4: чем отличаются пользователи с ≥5 обращениями к модели в день (по инициативе человека).
Группы среди пользователей с ≥5 активными днями: heavy (≥5 вызовов/активный день), mid (2–5), low (<2)."""
import sys, collections, statistics as st
sys.path.insert(0, __file__.rsplit("/", 1)[0])
import features as F
from datetime import timedelta
users, active, feat, llm_user, llm_all, calls = F.load()
pool = [u for u in active if len(active[u]) >= 5]
def per_day(u): ds = active[u]; return sum(llm_user[u][d] for d in ds) / len(ds)
G = {"heavy ≥5": [u for u in pool if per_day(u) >= 5], "mid 2–5": [u for u in pool if 2 <= per_day(u) < 5], "low <2": [u for u in pool if per_day(u) < 2]}
last_date = max(max(v) for v in active.values())
def m(xs): return st.mean(xs) if xs else float("nan")
def md(xs): return st.median(xs) if xs else float("nan")

print(f"Пул: пользователи с ≥5 активными днями: {len(pool)} (всего с активностью {len(active)}); последняя дата в данных {last_date}")
print("\n1) ПРОФИЛЬ ГРУПП: n | активных дней (мед.) | стаж, дн | доля дней с активностью за стаж | покрытие функций (из 13) | вызовов/день | активны последние 7 дней")
for g, us in G.items():
    ad = [len(active[u]) for u in us]; span = [(max(active[u]) - min(active[u])).days + 1 for u in us]
    ratio = [len(active[u]) / s for u, s in zip(us, span)]
    cov = [len({f for d in feat[u].values() for f in d}) for u in us]
    alive = sum(1 for u in us if max(active[u]) >= last_date - timedelta(days=6))
    print(f"   {g:9} n={len(us):2} | {md(ad):4.0f} | {md(span):4.0f} | {md(ratio):.2f} | {m(cov):4.1f} | {m([per_day(u) for u in us]):4.1f} | {alive}/{len(us)}")

print("\n2) ИСПОЛЬЗОВАНИЕ ФУНКЦИЙ: доля пользователей группы, хоть раз использовавших / событий на активный день")
print(f"   {'функция':20}" + "".join(f"{g:>22}" for g in G))
for f in F.FEATURES:
    row = f"   {f:20}"
    for g, us in G.items():
        used = sum(1 for u in us if any(f in feat[u][d] for d in feat[u])); ev = [sum(feat[u][d][f] for d in active[u]) / len(active[u]) for u in us]
        row += f"{100*used/len(us):10.0f}% / {m(ev):5.2f}  "
    print(row)

print("\n3) ИСТОЧНИК ОБЪЁМА у heavy: доля событий по функциям (без открытий утра)")
tot = collections.Counter()
for u in G["heavy ≥5"]:
    for d in feat[u].values():
        for f, n in d.items():
            if f != "утро открыто": tot[f] += n
s = sum(tot.values()); print("   ", {f: f"{100*n/s:.0f}%" for f, n in tot.most_common()})
print("   то же у mid:", end=" ")
tot2 = collections.Counter()
for u in G["mid 2–5"]:
    for d in feat[u].values():
        for f, n in d.items():
            if f != "утро открыто": tot2[f] += n
s2 = sum(tot2.values()); print({f: f"{100*n/s2:.0f}%" for f, n in tot2.most_common(6)})

print("\n4) ХАРАКТЕР АКТИВНОСТИ у heavy: объём в день распределён или всплесками?")
for g in ("heavy ≥5", "mid 2–5"):
    days = [llm_user[u][d] for u in G[g] for d in active[u]]
    print(f"   {g}: дней {len(days)}; вызовов за день: медиана {md(days):.0f}, p90 {sorted(days)[int(.9*(len(days)-1))]}; дней с ≥10 вызовами: {100*sum(1 for x in days if x>=10)/len(days):.0f}%; дней с 0 вызовов: {100*sum(1 for x in days if x==0)/len(days):.0f}%")
print("   доля вызовов heavy-пользователей в 3 самых «громких» днях каждого из них:",
      f"{100*m([sum(sorted((llm_user[u][d] for d in active[u]), reverse=True)[:3])/max(sum(llm_user[u][d] for d in active[u]),1) for u in G['heavy ≥5']]):.0f}%")

print("\n5) ДИНАМИКА heavy: вызовов в активный день в первых и последних 7 активных днях каждого")
rows = []
for u in G["heavy ≥5"]:
    ds = sorted(active[u]); a = ds[:7]; b = ds[-7:]
    rows.append((m([llm_user[u][d] for d in a]), m([llm_user[u][d] for d in b]), len(ds)))
for i, (a, b, n) in enumerate(sorted(rows, key=lambda r: -r[1])): print(f"   heavy#{i+1}: активных дней {n:2}: первые 7 → {a:4.1f}, последние 7 → {b:4.1f}")
print(f"   среднее: первые {m([r[0] for r in rows]):.1f} → последние {m([r[1] for r in rows]):.1f}")

print("\n6) ОТМЕТКА И РАЗБОР: доля активных дней с отметкой и с разбором дня по группам")
for g, us in G.items():
    ci = sum(1 for u in us for d in active[u] if feat[u][d]["отметка"]) / sum(len(active[u]) for u in us)
    rv = sum(1 for u in us for d in active[u] if feat[u][d]["разбор дня"]) / sum(len(active[u]) for u in us)
    print(f"   {g:9} отметка в {100*ci:.0f}% дней, разбор дня в {100*rv:.0f}% дней")

print("\n7) КТО УШЁЛ: последняя активность heavy-пользователей и «коротких» тяжёлых (<5 активных дней, но ≥5 вызовов/день)")
short_heavy = [u for u in active if len(active[u]) < 5 and per_day(u) >= 5]
for name, us in (("heavy (≥5 дн.)", G["heavy ≥5"]), ("короткие тяжёлые", short_heavy)):
    gaps = sorted((last_date - max(active[u])).days for u in us)
    print(f"   {name:18} n={len(us)}; дней с последней активности до конца данных: {gaps}; активных дней у каждого: {sorted(len(active[u]) for u in us)}")
all_heavy = set(G["heavy ≥5"]) | set(short_heavy)

print("\n8) НЕДЕЛИ: вызовов по инициативе человека на активный пользователь-день, и вклад тяжёлых (heavy + короткие)")
import datetime as dt
wk = collections.defaultdict(lambda: [0, 0, 0, 0])   # вызовы всех, дни всех, вызовы тяжёлых, дни тяжёлых
for u in active:
    for d in active[u]:
        y, w, _ = d.isocalendar(); k = f"{y}-W{w:02d}"; n = llm_user[u][d]
        wk[k][0] += n; wk[k][1] += 1
        if u in all_heavy: wk[k][2] += n; wk[k][3] += 1
for k, (n, days, nh, dh) in sorted(wk.items()):
    rest = (n - nh) / max(days - dh, 1)
    print(f"   {k}: активных пользователь-дней {days:3}; вызовов/день у всех {n/days:4.1f}; у остальных (без тяжёлых) {rest:4.1f}; доля тяжёлых в вызовах {100*nh/max(n,1):3.0f}% при {100*dh/days:2.0f}% дней")
