#!/usr/bin/env python3
"""Пункт 4.1: пользователи с долгой историей (≥14 активных дней), но НЕ с полным покрытием функций.
Что они не используют (только из того, что уже было доступно в их период), что используют, как менялась их активность.
Дата выхода функции = первая дата её использования кем-либо (приближение)."""
import sys, collections, statistics as st
sys.path.insert(0, __file__.rsplit("/", 1)[0])
import features as F
from datetime import timedelta
users, active, feat, llm_user, llm_all, calls = F.load()
last_date = max(max(v) for v in active.values())
release = {}
for u in feat:
    for d, c in feat[u].items():
        for f in c:
            if f not in release or d < release[f]: release[f] = d
print("Дата первой встречи функции в данных (приближение выхода):", {f: f"{release[f]:%d.%m}" for f in F.FEATURES if f in release})
long_ = [u for u in active if len(active[u]) >= 14]
def used(u): return {f for d in feat[u].values() for f in d}
def avail(u): return {f for f in F.FEATURES if f in release and release[f] <= max(active[u])}
cov = {u: len(used(u) & avail(u)) / len(avail(u)) for u in long_}
vals = sorted(cov.values()); med = st.median(vals)
print(f"\nДолгая история (≥14 активных дней): {len(long_)} пользователей; покрытие доступных функций: мин {vals[0]:.2f}, медиана {med:.2f}, макс {vals[-1]:.2f}")
print("   распределение (доля доступных функций, которыми пользовался):", dict(sorted(collections.Counter(round(v, 1) for v in vals).items())))
broad = [u for u in long_ if cov[u] >= 0.75]; narrow = [u for u in long_ if cov[u] < 0.75]
print(f"   «широкие» (≥75% доступных): {len(broad)}; «узкие» (<75%): {len(narrow)}")

def per_day(u): ds = active[u]; return sum(llm_user[u][d] for d in ds) / len(ds)
def ev_per_day(u, f): return sum(feat[u][d][f] for d in active[u]) / len(active[u])
print("\n1) ПОРТРЕТ: n | активных дней (мед.) | стаж | вызовов/день | отметка в % дней | активны в последние 7 дней")
for name, us in (("узкие", narrow), ("широкие", broad)):
    ad = [len(active[u]) for u in us]; span = [(max(active[u]) - min(active[u])).days + 1 for u in us]
    ci = sum(1 for u in us for d in active[u] if feat[u][d]["отметка"]) / sum(ad)
    alive = sum(1 for u in us if max(active[u]) >= last_date - timedelta(days=6))
    print(f"   {name:8} n={len(us):2} | {st.median(ad):4.0f} | {st.median(span):4.0f} | {st.mean([per_day(u) for u in us]):4.2f} | {100*ci:3.0f}% | {alive}/{len(us)}")

print("\n2) ЧЕГО «УЗКИЕ» НЕ ИСПОЛЬЗУЮТ: доля пользователей группы, которые ни разу не пользовались функцией (учитывая доступность)")
print(f"   {'функция':20} {'узкие, не пользовались':>24} {'широкие, не пользовались':>26}")
rows = []
for f in F.FEATURES:
    a_n = [u for u in narrow if f in avail(u)]; a_b = [u for u in broad if f in avail(u)]
    if not a_n: continue
    nn = sum(1 for u in a_n if f not in used(u)); nb = sum(1 for u in a_b if f not in used(u))
    rows.append((nn / len(a_n), f, nn, len(a_n), nb, len(a_b)))
for r, f, nn, an, nb, ab in sorted(rows, reverse=True):
    print(f"   {f:20} {100*r:10.0f}% ({nn}/{an}) {100*nb/max(ab,1):14.0f}% ({nb}/{ab})")

print("\n3) ЧЕМ «УЗКИЕ» ПОЛЬЗУЮТСЯ: события на активный день (узкие | широкие)")
for f in F.FEATURES:
    print(f"   {f:20} {st.mean([ev_per_day(u, f) for u in narrow]):5.2f} | {st.mean([ev_per_day(u, f) for u in broad]):5.2f}")
print("   «узкие», у которых пользование свелось к 1–3 функциям (ядро):",
      collections.Counter(tuple(sorted(used(u))) for u in narrow if len(used(u)) <= 3).most_common(3))

print("\n4) ДИНАМИКА: активность в первой и последней трети активных дней каждого (вызовы по инициативе / день; активных дней в неделю)")
def thirds(u):
    ds = sorted(active[u]); k = max(len(ds) // 3, 1); return ds[:k], ds[-k:]
cls = collections.defaultdict(collections.Counter)
for name, us in (("узкие", narrow), ("широкие", broad)):
    a_l, b_l = [], []
    for u in us:
        a, b = thirds(u); x = st.mean([llm_user[u][d] for d in a]); y = st.mean([llm_user[u][d] for d in b])
        a_l.append(x); b_l.append(y)
        cls[name]["растёт" if y > x * 1.3 and y - x >= 0.3 else "падает" if y < x * 0.7 and x - y >= 0.3 else "ровно"] += 1
    print(f"   {name:8} среднее: начало {st.mean(a_l):.2f} → конец {st.mean(b_l):.2f}; траектории: {dict(cls[name])}")
def act_per_week(u, win):
    ds = [d for d in active[u] if win[0] <= d <= win[1]]; return len(ds) / 2
w_recent = (last_date - timedelta(days=13), last_date); w_before = (last_date - timedelta(days=27), last_date - timedelta(days=14))
for name, us in (("узкие", narrow), ("широкие", broad)):
    print(f"   {name:8} активных дней в неделю: 28–15 дней назад {st.mean([act_per_week(u, w_before) for u in us]):.1f} → последние 14 дней {st.mean([act_per_week(u, w_recent) for u in us]):.1f}")

print("\n5) БРОШЕННЫЕ ФУНКЦИИ среди долгожителей: использовали, но ни разу за последние 14 дней (из тех, кто использовал функцию)")
rec = last_date - timedelta(days=13)
dr = []
for f in F.FEATURES:
    us_f = [u for u in long_ if f in used(u) and max(active[u]) >= rec]   # ещё живые
    if len(us_f) < 5: continue
    dropped = sum(1 for u in us_f if not any(d >= rec and f in feat[u][d] for d in feat[u]))
    dr.append((dropped / len(us_f), f, dropped, len(us_f)))
for r, f, dd, n in sorted(dr, reverse=True): print(f"   {f:20} {100*r:3.0f}% ({dd}/{n})")

print("\n6) СВЯЗЬ ПОКРЫТИЯ С УДЕРЖАНИЕМ: доля тех, кто активен в последние 7 дней, по покрытию (все с ≥14 активными днями)")
q = sorted(long_, key=lambda u: cov[u]); half = len(q) // 2
for name, us in (("нижняя половина по покрытию", q[:half]), ("верхняя половина", q[half:])):
    print(f"   {name:30} {sum(1 for u in us if max(active[u]) >= last_date - timedelta(days=6))}/{len(us)} активны; покрытие {min(cov[u] for u in us):.2f}–{max(cov[u] for u in us):.2f}")
