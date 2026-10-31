#!/usr/bin/env python3
"""Сводка повторного аудита (v2, полный контекст для judge) и сравнение со старым аудитом (v1) на тех же текстах.
Читает data/audit_input_v2.jsonl + data/audit_results_v2.jsonl и, для сравнения, data/audit_input.jsonl + audit_results.jsonl."""
import os, sys, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_waves as aw

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
def load(p): return [json.loads(l) for l in open(os.path.join(D, p), encoding="utf-8")] if os.path.exists(os.path.join(D, p)) else []
inp2, res2 = load("audit_input_v2.jsonl"), load("audit_results_v2.jsonl")
inp1, res1 = load("audit_input.jsonl"), load("audit_results.jsonl")

def by_judge(res):
    d = collections.defaultdict(dict)
    for x in res:
        if not x.get("err"): d[x["id"]][x["judge"]] = x
    return d
b2, b1 = by_judge(res2), by_judge(res1)
key = lambda r: (r["kind"], str(r["date"])[:10], r["text"])
v1 = {key(r): b1.get(i, {}) for i, r in enumerate(inp1)}
J = sorted({j for v in b2.values() for j in v})
full = [i for i, v in b2.items() if len(v) == len(J)]
pct = lambda n, d: f"{100 * n / d:3.0f}%" if d else "  - "
print(f"оценено всеми судьями {J}: {len(full)} текстов (v2)\n")
print("ПО СУДЬЯМ, v2 (полный контекст): совет | выдумка(2) | неточность(>=1)")
for j in J:
    print(f"  {j:20} {pct(sum(b2[i][j]['advice'] == 1 for i in full), len(full))} | {pct(sum(b2[i][j]['halluc'] == 2 for i in full), len(full))} | {pct(sum((b2[i][j]['halluc'] or 0) >= 1 for i in full), len(full))}")
adv = lambda i: [b2[i][j]["advice"] == 1 for j in J]
hal = lambda i: [b2[i][j]["halluc"] == 2 for j in J]
print(f"  все судьи согласны: совет {pct(sum(all(adv(i)) for i in full), len(full))} | выдумка {pct(sum(all(hal(i)) for i in full), len(full))}")
print(f"  большинство (>=2 из {len(J)}): совет {pct(sum(sum(adv(i)) * 2 > len(J) for i in full), len(full))} | выдумка {pct(sum(sum(hal(i)) * 2 > len(J) for i in full), len(full))}")
print("\nЭФФЕКТ ПОЛНОГО КОНТЕКСТА: те же тексты, старый аудит (v1) против нового (v2); выдумка(2) | совет")
for j in ("gpt-5.1", "claude-sonnet-4.6"):
    ids = [i for i in full if j in v1.get(key(inp2[i]), {})]
    if ids:
        o = lambda f: sum(f(v1[key(inp2[i])][j]) for i in ids)
        n = lambda f: sum(f(b2[i][j]) for i in ids)
        print(f"  {j:20} n={len(ids):4} | выдумка v1 {pct(o(lambda x: x['halluc'] == 2), len(ids))} -> v2 {pct(n(lambda x: x['halluc'] == 2), len(ids))} | совет v1 {pct(o(lambda x: x['advice'] == 1), len(ids))} -> v2 {pct(n(lambda x: x['advice'] == 1), len(ids))}")
print("\nПО КАТЕГОРИЯМ (большинство судей): n | совет | выдумка")
cats = collections.defaultdict(list)
for i in full: cats[aw.cat(inp2[i]["kind"])].append(i)
for c, ids in sorted(cats.items(), key=lambda t: -len(t[1])):
    print(f"  {c:12} n={len(ids):3} | {pct(sum(sum(adv(i)) * 2 > len(J) for i in ids), len(ids))} | {pct(sum(sum(hal(i)) * 2 > len(J) for i in ids), len(ids))}")
