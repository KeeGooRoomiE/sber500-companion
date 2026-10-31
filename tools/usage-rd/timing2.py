#!/usr/bin/env python3
"""Пункт 3 (уточнение): оценка часового пояса пользователя и пересчёт часов по МЕСТНОМУ времени.
Сервер пояс не хранит (всё по APP_TZ=МСК). Оценка: первое открытие приложения за день (серверное время, МСК) минус
локальное время первой разблокировки (daily_data.first, время телефона). У человека в МСК разница ≥ 0 и мала;
у человека восточнее разница отрицательна на размер сдвига. Берём нижний квартиль разницы по дням (задержка не меньше 0).
Точность ±1 час. Пользователи без ≥5 пар дней считаются неизвестными и в местные часы не входят."""
import json, collections, statistics as st
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
HERE = __file__.rsplit("/", 1)[0]; MSK = ZoneInfo("Europe/Moscow")
L = lambda n: [json.loads(l) for l in open(f"{HERE}/data/{n}.jsonl", encoding="utf-8")]
calls, morning, checkins, daily = L("calls"), L("morning"), L("checkins"), L("daily")
def t(s): return datetime.fromisoformat(s).astimezone(MSK)
def hr(dt): return dt.hour + dt.minute / 60 + dt.second / 3600
def hh(s):
    p = s.split(":"); return int(p[0]) + int(p[1]) / 60
def wrap(x):
    while x > 12: x -= 24
    while x <= -12: x += 24
    return x
def pctl(xs, q): xs = sorted(xs); return xs[int(q * (len(xs) - 1))] if xs else float("nan")
def hhmm(h): h %= 24; return f"{int(h):02d}:{int((h % 1) * 60):02d}"

# Оценка пояса: сдвиг o (часы от МСК) подходит, если действия человека (серверное время + o) попадают в часы местных суток,
# когда телефон разблокировался (daily_data.hu — разблокировки по местным часам). Ночью их нет, поэтому неверный сдвиг
# даёт много попаданий в «пустые» часы. Берём o с лучшим совпадением, если отрыв от второго места >= 0.08.
hu = {(d["u"], d["date"][:10]): d["hu"] for d in daily if d.get("hu") and len(d["hu"]) == 24}
HUMAN = {"morning_api", "checkin", "llm_insight", "llm_day_review", "llm_followup", "llm_explore", "llm_weekly"}
events = collections.defaultdict(list)
for c in calls:
    if c["comp"] in HUMAN and c["trig"] in ("user_action", "followup"): events[c["u"]].append(t(c["ts"]))
OFFS = list(range(-3, 10)); est = {}; detail = {}
first_open = {}
for c in calls:
    if c["comp"] == "morning_api" and c["trig"] == "user_action":
        d = t(c["ts"]); k = (c["u"], d.date().isoformat())
        if k not in first_open or d < first_open[k]: first_open[k] = d
for u, evs in events.items():
    scores = []
    for o in OFFS:
        n = ok = 0
        for d in evs:
            ld = d + timedelta(hours=o); row = hu.get((u, ld.date().isoformat()))
            if row is None: continue
            n += 1; ok += row[ld.hour] > 0
        scores.append((ok / n if n else 0, n, o))
    ranked = sorted(scores, reverse=True)
    if ranked[0][1] >= 20 and ranked[0][0] - ranked[1][0] >= 0.08:
        est[u] = ranked[0][2]; detail[u] = ranked[0]
allu = {c["u"] for c in calls}
print(f"пользователей: {len(allu)}; пояс определён уверенно: {len(est)}; не определён (мало данных или нет отрыва): {len(allu) - len(est)}")
dist = collections.Counter(est.values())
print("сдвиг от МСК (часы), число пользователей:", dict(sorted(dist.items())))
msk = sum(v for k, v in dist.items() if k == 0)
print(f"  МСК (0): {msk} ({100*msk/len(est):.0f}%); МСК±1: {sum(v for k,v in dist.items() if abs(k)<=1)}; восточнее на 2+ часа: {sum(v for k,v in dist.items() if k>=2)} ({100*sum(v for k,v in dist.items() if k>=2)/len(est):.0f}%); западнее: {sum(v for k,v in dist.items() if k<0)}")
print("  качество: доля действий в часах с разблокировкой при выбранном сдвиге, медиана по пользователям:", f"{st.median([d[0] for d in detail.values()]):.2f}")

def local(dt, u): return (hr(dt) + est[u]) % 24 if u in est else None

print("\nМЕСТНОЕ время (только пользователи с оценённым поясом)")
fo = [local(d, k[0]) for k, d in first_open.items() if k[0] in est]
print(f"A) первое открытие за день: n={len(fo)} | p25 {hhmm(pctl(fo,.25))} | медиана {hhmm(pctl(fo,.5))} | p75 {hhmm(pctl(fo,.75))}")
cb = collections.Counter(int(x) for x in fo)
print("   по часам:", " ".join(f"{h:02d}:{cb[h]}" for h in range(24) if cb[h]))
sent = [(m["u"], t(m["sent"])) for m in morning if m["sent"] and m["u"] in est]
sh = [local(d, u) for u, d in sent]
print(f"   утро забрано с сервера впервые: n={len(sh)} | медиана {hhmm(pctl(sh,.5))} | p10 {hhmm(pctl(sh,.1))} | p90 {hhmm(pctl(sh,.9))} | до 06:00: {100*sum(1 for x in sh if x<6)/len(sh):.0f}%")
ch = [(c["u"], t(c["created"])) for c in checkins if c["u"] in est]
cl = [local(d, u) for u, d in ch]
print(f"B) отметка дня: n={len(cl)} | медиана {hhmm(pctl(cl,.5))} | p25 {hhmm(pctl(cl,.25))} | p75 {hhmm(pctl(cl,.75))}")
bins = [("до 12:00", 0, 12), ("12–18", 12, 18), ("18–20:30", 18, 20.5), ("20:30–21:30", 20.5, 21.5), ("21:30–23", 21.5, 23), ("23–24", 23, 24)]
for name, a, b in bins:
    n = sum(1 for x in cl if a <= x < b); print(f"   {name:12} {n:4d} ({100*n/len(cl):2.0f}%)")
print(f"   в течение часа после напоминания 20:30 (местное): {100*sum(1 for x in cl if 20.5<=x<21.5)/len(cl):.0f}% | вечером (18:00–24:00): {100*sum(1 for x in cl if x>=18)/len(cl):.0f}%")
for typ in ("morning", "checkin", "review"):
    xs = [local(t(c["ts"]), c["u"]) for c in calls if c["comp"] == "notification_opened" and c["res"] == f"notification_opened:{typ}" and c["u"] in est]
    if xs: print(f"C) уведомление «{typ}» открыто: n={len(xs)} | медиана {hhmm(pctl(xs,.5))} | p10 {hhmm(pctl(xs,.1))} | p90 {hhmm(pctl(xs,.9))}")

print("\nДОПОЛНЕНИЯ")
res = collections.Counter(c["res"] for c in calls if c["comp"] == "morning_api" and c["trig"] == "user_action")
tot = sum(res.values()); print(f"D) открытия приложения (запрос утра): результат {dict(res)}; не «ok»: {100*(tot-res['ok'])/tot:.0f}%")
by_h = collections.defaultdict(lambda: [0, 0])
for c in calls:
    if c["comp"] == "morning_api" and c["trig"] == "user_action" and c["u"] in est:
        h = int(local(t(c["ts"]), c["u"])); by_h[h][0] += 1; by_h[h][1] += c["res"] != "ok"
print("   доля «не ok» по местному часу:", " ".join(f"{h:02d}:{100*v[1]/v[0]:.0f}%({v[0]})" for h, v in sorted(by_h.items()) if v[0] >= 15))
retro = []
for c in checkins:
    if c["u"] in est:
        ld = (t(c["created"]) + timedelta(hours=est[c["u"]])).date()
        retro.append((ld - datetime.fromisoformat(c["date"][:10]).date()).days)
rc = collections.Counter(retro)
print(f"E) отметка за день, поставленная позже этого дня (сдвиг в днях): {dict(sorted(rc.items()))}; не в тот же день: {100*(len(retro)-rc[0])/len(retro):.0f}%")
print("\nОГРАНИЧЕНИЕ: пояс оценён у 23 из 60 пользователей (там, где хватило данных); это более активная часть выборки. Точность ±1 час.")
