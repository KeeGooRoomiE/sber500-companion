#!/usr/bin/env python3
"""Пункт 3: когда люди открывают утро, отмечают день и открывают уведомления (время МСК).
Данные: data/{morning,calls,checkins,activity}.jsonl (id хэшированы)."""
import json, collections, statistics as st
from datetime import datetime, date, timedelta
from zoneinfo import ZoneInfo
HERE = __file__.rsplit("/", 1)[0]; MSK = ZoneInfo("Europe/Moscow")
L = lambda n: [json.loads(l) for l in open(f"{HERE}/data/{n}.jsonl", encoding="utf-8")]
def t(s): return datetime.fromisoformat(s).astimezone(MSK)
def hr(dt): return dt.hour + dt.minute / 60
def bar(n, tot, w=28): return "█" * int(w * n / max(tot, 1))
def pctl(xs, q): xs = sorted(xs); return xs[int(q * (len(xs) - 1))] if xs else float("nan")
def hhmm(h): return f"{int(h):02d}:{int((h % 1) * 60):02d}"

morning, calls, checkins, activity = L("morning"), L("calls"), L("checkins"), L("activity")
EVENING = 20.5   # напоминание по умолчанию 20:30 (ProfileQuestions.DefaultEveningTime)

print("A) УТРО: когда сообщение впервые забрали с сервера (sent_at, МСК). opened_at в БД не заполняется нигде (мёртвая колонка).")
sent = [t(m["sent"]) for m in morning if m["sent"]]
h = [hr(x) for x in sent]
print(f"   n={len(h)} | p10 {hhmm(pctl(h,.1))} | медиана {hhmm(pctl(h,.5))} | p90 {hhmm(pctl(h,.9))}")
wk = [hr(x) for x in sent if x.weekday() < 5]; we = [hr(x) for x in sent if x.weekday() >= 5]
print(f"   будни: медиана {hhmm(pctl(wk,.5))} (n={len(wk)}), выходные: медиана {hhmm(pctl(we,.5))} (n={len(we)})")
bk = collections.Counter(("до 07" if x < 7 else "07–09" if x < 9 else "09–12" if x < 12 else "12–18" if x < 18 else "18+") for x in h)
print("   корзины:", {k: f"{100*bk[k]/len(h):.0f}%" for k in ("до 07", "07–09", "09–12", "12–18", "18+")})
cm = collections.Counter(x.hour for x in sent)
print("   по часам:", " ".join(f"{hh:02d}:{cm[hh]}" for hh in range(5, 24) if cm[hh]))

print("\nB) ОТКРЫТИЯ ПРИЛОЖЕНИЯ (запросы утра с телефона, user_action)")
op = [t(c["ts"]) for c in calls if c["comp"] == "morning_api" and c["trig"] == "user_action"]
co = collections.Counter(x.hour for x in op); tot = len(op)
for hh in range(0, 24):
    if co[hh]: print(f"   {hh:02d}  {co[hh]:4d} {bar(co[hh], max(co.values()), 26)}")
first = {}
for c in calls:
    if c["comp"] == "morning_api" and c["trig"] == "user_action":
        d = t(c["ts"]); k = (c["u"], d.date())
        if k not in first or d < first[k]: first[k] = d
fh = [hr(x) for x in first.values()]
print(f"   первых открытий за день {len(fh)}: медиана {hhmm(pctl(fh,.5))}, p25 {hhmm(pctl(fh,.25))}, p75 {hhmm(pctl(fh,.75))}")
per_day = collections.Counter((c["u"], t(c["ts"]).date()) for c in calls if c["comp"] == "morning_api" and c["trig"] == "user_action")
print(f"   открытий за активный день: медиана {st.median(per_day.values()):.0f}, p90 {pctl(list(per_day.values()),.9):.0f}")

print("\nC) ЧЕК-ИН: во сколько человек отмечает день (created_at первой отметки, МСК); напоминание по умолчанию 20:30")
ci = [t(c["created"]) for c in checkins]; ch = [hr(x) for x in ci]
bins = [("до 12:00", 0, 12), ("12–18", 12, 18), ("18–20:30", 18, 20.5), ("20:30–21:30", 20.5, 21.5), ("21:30–23", 21.5, 23), ("23–24", 23, 24)]
print(f"   n={len(ch)} | медиана {hhmm(pctl(ch,.5))} | p25 {hhmm(pctl(ch,.25))} | p75 {hhmm(pctl(ch,.75))}")
for name, a, b in bins:
    n = sum(1 for x in ch if a <= x < b); print(f"   {name:12} {n:4d} ({100*n/len(ch):2.0f}%) {bar(n, len(ch))}")
print(f"   в течение часа после 20:30: {100*sum(1 for x in ch if 20.5 <= x < 21.5)/len(ch):.0f}% | после 23:00: {100*sum(1 for x in ch if x >= 23)/len(ch):.0f}% | до 18:00: {100*sum(1 for x in ch if x < 18)/len(ch):.0f}%")
users_act = collections.defaultdict(set); users_ci = collections.defaultdict(set)
for a in activity: users_act[a["u"]].add(a["date"][:10])
for c in checkins: users_ci[c["u"]].add(c["date"][:10])
rates = [len(users_ci[u] & users_act[u]) / len(users_act[u]) for u in users_act if len(users_act[u]) >= 5]
print(f"   пользователей с ≥5 активными днями: {len(rates)}; хоть раз отмечали день: {sum(1 for u in users_act if users_ci.get(u) and len(users_act[u])>=5)}; "
      f"доля активных дней с отметкой: медиана {st.median(rates):.2f}, p75 {pctl(rates,.75):.2f}")
tot_act = sum(len(v) for v in users_act.values()); tot_ci = sum(len(users_ci[u] & users_act[u]) for u in users_act)
print(f"   общая доля активных дней с отметкой: {100*tot_ci/tot_act:.0f}% ({tot_ci} из {tot_act})")
dow = collections.Counter(x.weekday() for x in ci); print("   по дням недели:", {["пн","вт","ср","чт","пт","сб","вс"][d]: dow[d] for d in range(7)})
wkc = collections.defaultdict(lambda: [0, 0])
for u, ds in users_act.items():
    for d in ds:
        y, w, _ = date.fromisoformat(d).isocalendar(); wkc[f"{y}-W{w:02d}"][0] += 1; wkc[f"{y}-W{w:02d}"][1] += d in users_ci.get(u, set())
print("   доля активных дней с отметкой по неделям:", {k: f"{100*v[1]/v[0]:.0f}%" for k, v in sorted(wkc.items())})

print("\nD) ОТКРЫТИЯ УВЕДОМЛЕНИЙ (notification_opened; кнопки в шторке без открытия приложения сюда не попадают)")
for typ in ("morning", "checkin", "review"):
    xs = [t(c["ts"]) for c in calls if c["comp"] == "notification_opened" and c["res"] == f"notification_opened:{typ}"]
    hs = [hr(x) for x in xs]
    if xs: print(f"   {typ:8} n={len(xs):3} | медиана {hhmm(pctl(hs,.5))}, p10 {hhmm(pctl(hs,.1))}, p90 {hhmm(pctl(hs,.9))} | период {min(xs):%d.%m}–{max(xs):%d.%m}")

print("\nE) ПЕТЛЯ «ОТМЕТКА → РАЗБОР ДНЯ» (разбор в течение 10 минут после отметки того же пользователя)")
rv = collections.defaultdict(list)
for c in calls:
    if c["comp"] == "llm_day_review": rv[c["u"]].append(t(c["ts"]))
n_after = 0; n_ci = 0
for c in checkins:
    d = t(c["created"]); n_ci += 1
    if any(timedelta(0) <= x - d <= timedelta(minutes=10) for x in rv.get(c["u"], [])): n_after += 1
print(f"   после {n_after} из {n_ci} отметок ({100*n_after/n_ci:.0f}%) в течение 10 минут появился разбор дня")
nr = sum(1 for c in calls if c["comp"] == "notification_opened" and c["res"] == "notification_opened:review")
print(f"   открытий уведомления разбора: {nr}; относительно разборов после отметки: {100*nr/max(n_after,1):.0f}% (грубая оценка, разбор мог быть открыт и из приложения)")
