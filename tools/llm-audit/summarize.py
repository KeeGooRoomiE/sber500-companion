#!/usr/bin/env python3
"""Сводка аудита: по категориям, по неделям, согласие judge. Только прошедшие оба judge."""
import os, sys, json, collections
from datetime import date
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_waves as aw

SCR = os.path.dirname(os.path.abspath(__file__))
inp = [json.loads(l) for l in open(f"{SCR}/data/audit_input.jsonl", encoding="utf-8")]
res = [json.loads(l) for l in open(f"{SCR}/data/audit_results.jsonl", encoding="utf-8")]
by = collections.defaultdict(dict)
for x in res:
    if not x.get("err"): by[x["id"]][x["judge"]] = x
both = {i: v for i, v in by.items() if len(v) == 2}
G, S = "gpt-5.1", "claude-sonnet-4.6"
print(f"текстов с двумя оценками: {len(both)} (из {len(inp)}); с одной: {len(by) - len(both)}\n")

def pct(n, d): return f"{100 * n / d:3.0f}%" if d else "  - "

def row(ids):
    n = len(ids)
    adv_c = sum(1 for i in ids if both[i][G]["advice"] == 1 and both[i][S]["advice"] == 1)
    adv_a = sum(1 for i in ids if both[i][G]["advice"] == 1 or both[i][S]["advice"] == 1)
    hal_c = sum(1 for i in ids if both[i][G]["halluc"] == 2 and both[i][S]["halluc"] == 2)
    hal_a = sum(1 for i in ids if both[i][G]["halluc"] == 2 or both[i][S]["halluc"] == 2)
    gen_a = sum(1 for i in ids if (both[i][G]["generic"] == 2) or (both[i][S]["generic"] == 2))
    return n, adv_c, adv_a, hal_c, hal_a, gen_a

print("ПО КАТЕГОРИЯМ: n | совет: оба / любой | выдумка: оба / любой | шаблон(любой)")
cats = collections.defaultdict(list)
for i in both: cats[aw.cat(inp[i]["kind"])].append(i)
for c, ids in sorted(cats.items(), key=lambda t: -len(t[1])):
    n, ac, aa, hc, ha, ga = row(ids)
    print(f"  {c:12} n={n:3} | {pct(ac,n)} / {pct(aa,n)} | {pct(hc,n)} / {pct(ha,n)} | {pct(ga,n)}")
n, ac, aa, hc, ha, ga = row(list(both))
print(f"  {'ВСЕ':12} n={n:3} | {pct(ac,n)} / {pct(aa,n)} | {pct(hc,n)} / {pct(ha,n)} | {pct(ga,n)}")

print("\nДИНАМИКА ПО НЕДЕЛЯМ (дата текста, ISO-неделя): n | совет(любой) | выдумка(любой)")
wk = collections.defaultdict(list)
for i in both:
    d = date.fromisoformat(inp[i]["date"][:10]); y, w, _ = d.isocalendar()
    wk[f"{y}-W{w:02d}"].append(i)
for k in sorted(wk):
    n, ac, aa, hc, ha, ga = row(wk[k])
    print(f"  {k}  n={n:3} | {pct(aa,n)} | {pct(ha,n)}")

mc = [i for i in both if inp[i]["kind"] == "morning"]
if len(mc) >= 20:
    print("\nМОРНИНГ по неделям (самая показательная серия): n | совет(любой) | выдумка(любой) | консенсус-выдумка")
    wk2 = collections.defaultdict(list)
    for i in mc:
        d = date.fromisoformat(inp[i]["date"][:10]); y, w, _ = d.isocalendar(); wk2[f"{y}-W{w:02d}"].append(i)
    for k in sorted(wk2):
        n, ac, aa, hc, ha, ga = row(wk2[k]); print(f"  {k}  n={n:3} | {pct(aa,n)} | {pct(ha,n)} | {pct(hc,n)}")

dis = sum(1 for i in both if (both[i][G]["halluc"] == 2) != (both[i][S]["halluc"] == 2))
print(f"\nвыдумку «2» ставит только один из двух judge: {dis}/{len(both)} ({pct(dis,len(both))}) — кандидаты на ручную калибровку")
print("(показатель «выдумка: любой» шумный — ≥1 почти везде из-за непроверяемого «после таких дней обычно…»; ориентир — консенсус «оба»)")
