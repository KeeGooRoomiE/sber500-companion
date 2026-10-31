#!/usr/bin/env python3
"""Пункт 2: шаблонность утренних текстов ВНУТРИ пользователя между соседними днями.
Сравнивает: (а) подряд идущие тексты одного пользователя, (б) случайные пары разных пользователей в ту же неделю.
Если (а) заметно выше (б) — человек видит повторы; если ≈ — повторы глобальные (весь продукт звучит одинаково).
Данные: data/morning.jsonl (хэшированные id). Печатает таблицы, ничего не пишет в репозиторий."""
import json, re, random, collections, difflib, statistics as st
from datetime import date, datetime
HERE = __file__.rsplit("/", 1)[0]
rows = [json.loads(l) for l in open(f"{HERE}/data/morning.jsonl", encoding="utf-8")]
for r in rows: r["d"] = date.fromisoformat(r["date"][:10])

def norm(t):
    t = t.lower().replace("ё", "е")
    t = re.sub(r"\d+([.,:]\d+)?", "N", t)
    return re.sub(r"[^а-яa-z N]", " ", t)
def words(t): return norm(t).split()
def tri(t):
    w = words(t); return {tuple(w[i:i + 3]) for i in range(len(w) - 2)}
def jacc(a, b): return len(a & b) / len(a | b) if a | b else 0.0
def ratio(a, b): return difflib.SequenceMatcher(None, norm(a), norm(b)).ratio()
def sents(t): return [s.strip() for s in re.split(r"(?<=[.!?])\s+", t.strip()) if s.strip()]
def pct(xs, q): xs = sorted(xs); return xs[int(q * (len(xs) - 1))] if xs else 0

by = collections.defaultdict(list)
for r in rows: by[r["u"]].append(r)
for u in by: by[u].sort(key=lambda r: r["d"])

def period(d):
    return "до 02.10" if d < date(2026, 10, 2) else ("02–08.10" if d < date(2026, 10, 8) else "с 08.10")

pairs = []   # соседние тексты одного пользователя (разрыв ≤ 2 дней)
for u, rs in by.items():
    for a, b in zip(rs, rs[1:]):
        if 0 < (b["d"] - a["d"]).days <= 2:
            pairs.append((u, a, b))
rnd = random.Random(7)
rand = []    # случайные пары разных пользователей с разницей дат ≤ 3 дней
for _ in range(len(pairs) * 3):
    a, b = rnd.sample(rows, 2)
    if a["u"] != b["u"] and abs((a["d"] - b["d"]).days) <= 3: rand.append((a, b))

def stats(label, sims):
    j, r = [x[0] for x in sims], [x[1] for x in sims]
    print(f"  {label:34} n={len(sims):4} | трёхсловия J: медиана {st.median(j):.2f} p90 {pct(j,.9):.2f} | текст R: медиана {st.median(r):.2f} p90 {pct(r,.9):.2f} | R≥0.8: {100*sum(x>=.8 for x in r)/len(r):.0f}%")

print("1) СХОДСТВО ТЕКСТОВ (J = доля общих трёхсловий, R = посимвольное сходство, цифры заменены на N)")
own = [(jacc(tri(a["text"]), tri(b["text"])), ratio(a["text"], b["text"])) for _, a, b in pairs]
oth = [(jacc(tri(a["text"]), tri(b["text"])), ratio(a["text"], b["text"])) for a, b in rand]
stats("соседние дни ОДНОГО пользователя", own); stats("разные пользователи, та же неделя", oth)
print("\n2) ДИНАМИКА ПО ПЕРИОДАМ ПРОМТА (соседние дни одного пользователя)")
for p in ("до 02.10", "02–08.10", "с 08.10"):
    sims = [(jacc(tri(a["text"]), tri(b["text"])), ratio(a["text"], b["text"])) for _, a, b in pairs if period(b["d"]) == p]
    if sims: stats(p, sims)
print("\n3) ПОЛЬЗОВАТЕЛИ: у кого самые «одинаковые» подряд идущие тексты (≥5 пар)")
per = collections.defaultdict(list)
for (u, a, b), (j, r) in zip(pairs, own): per[u].append(r)
rank = sorted(((st.mean(v), len(v), u) for u, v in per.items() if len(v) >= 5), reverse=True)
print(f"  пользователей с ≥5 парами: {len(rank)}; средний R на пользователя: медиана {st.median([x[0] for x in rank]):.2f}")
print("  топ-5 по повторяемости:", [(f"{m:.2f}", n) for m, n, _ in rank[:5]])
print("  доля пользователей, у которых средний R ≥ 0.5:", f"{100*sum(m >= .5 for m, _, _ in rank)/len(rank):.0f}%")

print("\n4) ЗАСТЫВШИЕ ФРАЗЫ (по всем текстам; цифры → N)")
first3 = collections.Counter(" ".join(words(sents(r['text'])[0])[:3]) for r in rows if sents(r["text"]))
print("  начало 1-го предложения, топ-5:", [(k, f"{100*v/len(rows):.0f}%") for k, v in first3.most_common(5)])
sec = collections.Counter()
for r in rows:
    s = sents(r["text"])
    if len(s) >= 2: sec[" ".join(words(s[1])[:4])] += 1
tot2 = sum(sec.values())
print("  начало 2-го предложения, топ-6:", [(k, f"{100*v/tot2:.0f}%") for k, v in sec.most_common(6)])
sk = collections.Counter(" ".join(words(sents(r["text"])[1])) for r in rows if len(sents(r["text"])) >= 2)
dup = sum(v for v in sk.values() if v > 1)
print(f"  вторые предложения целиком совпадают у {100*dup/tot2:.0f}% текстов (уникальных {len(sk)} из {tot2})")
print("  самые частые вторые предложения:", [(k[:70], v) for k, v in sk.most_common(3)])
print(f"\n5) ВСЕГО: текстов {len(rows)}, пользователей {len(by)}, соседних пар {len(pairs)}")
