#!/usr/bin/env python3
"""Выдумки и советы отдельно для текстов, сгенерированных в тот же день, который они описывают («день ещё идёт»),
и для сгенерированных позже («день закончился»). Нужны data/audit_input.jsonl, audit_results.jsonl, audit_times.jsonl."""
import json, os, sys, collections
from datetime import datetime
from zoneinfo import ZoneInfo
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_waves as aw
HERE = os.path.dirname(os.path.abspath(__file__)); MSK = ZoneInfo("Europe/Moscow")
inp = [json.loads(l) for l in open(f"{HERE}/data/audit_input.jsonl", encoding="utf-8")]
res = [json.loads(l) for l in open(f"{HERE}/data/audit_results.jsonl", encoding="utf-8")]
times = {}
for l in open(f"{HERE}/data/audit_times.jsonl", encoding="utf-8"):
    r = json.loads(l); times[(r["kind"], r["date"][:10], r["text"])] = r["created_at"]
by = collections.defaultdict(dict)
for x in res:
    if not x.get("err"): by[x["id"]][x["judge"]] = x
G, S = "gpt-5.1", "claude-sonnet-4.6"
both = {i: v for i, v in by.items() if len(v) == 2}
groups = collections.defaultdict(list); miss = 0
for i, v in both.items():
    r = inp[i]; k = (r["kind"], r["date"][:10], r["text"])
    if k not in times: miss += 1; continue
    created = datetime.fromisoformat(times[k]).astimezone(MSK).date().isoformat()
    cat = aw.cat(r["kind"]); same = created == r["date"][:10]
    if cat == "morning": same = created <= r["date"][:10]   # утро про вчера: «в тот же день» нормально
    groups[(cat, "в тот же день" if same else "позже")].append(i)
def pct(n, d): return f"{100 * n / d:3.0f}%" if d else "  - "
print(f"сопоставлено текстов: {sum(len(v) for v in groups.values())} (без времени: {miss})")
print(f"{'категория':12} {'когда сгенерирован':18} {'n':>4} | совет оба | выдумка оба | выдумка любой")
for (cat, when), ids in sorted(groups.items()):
    n = len(ids)
    adv = sum(1 for i in ids if both[i][G]["advice"] == 1 and both[i][S]["advice"] == 1)
    hc = sum(1 for i in ids if both[i][G]["halluc"] == 2 and both[i][S]["halluc"] == 2)
    ha = sum(1 for i in ids if both[i][G]["halluc"] == 2 or both[i][S]["halluc"] == 2)
    print(f"{cat:12} {when:18} {n:>4} | {pct(adv,n):>9} | {pct(hc,n):>11} | {pct(ha,n):>13}")
