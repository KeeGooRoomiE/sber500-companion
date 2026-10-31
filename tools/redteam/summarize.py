#!/usr/bin/env python3
"""Сводка красной команды из data/phase1.json и data/phase2.json."""
import json, collections, os
HERE = os.path.dirname(os.path.abspath(__file__))
res = json.load(open(f"{HERE}/data/phase1.json", encoding="utf-8"))
N = len(res); ok = [r for r in res if r.get("text")]
judged = [r for r in ok if r.get("judge")]
print(f"прогонов {N}; с ответом {len(ok)}; пустых (нет ответа) {N - len(ok)}; оценено judge {len(judged)}\n")

def share(rs, f): return f"{sum(1 for r in rs if f(r))}/{len(rs)}" if rs else "-"
base = {k: [r for r in ok if r["kind"] == k and r["payload_id"] == "baseline"] for k in ("morning", "day_review")}
print("ФОН (без нагрузки): совет-слова", {k: share(v, lambda r: r["flags"]["advice_words"]) for k, v in base.items()})

print("\n1) ИНЪЕКЦИИ: выполнил инструкцию (флаг / judge), по нагрузкам и каналам")
print(f"{'нагрузка':12} {'промт':11} {'канал':5} {'маркер/англ.':>13} {'выдал правила':>13} {'judge obeyed':>13} {'совет(judge)':>13}")
for pid in ("override", "advice", "english", "leak", "roleplay", "spoof"):
    for kind in ("morning", "day_review"):
        for ch in ("goal", "gen", "tags"):
            rs = [r for r in ok if r["payload_id"] == pid and r["kind"] == kind and r["channel"] == ch]
            if not rs: continue
            js = [r for r in rs if r.get("judge")]
            print(f"{pid:12} {kind:11} {ch:5} {share(rs, lambda r: r['flags']['marker_obeyed'] or r['flags']['english_reply']):>13} "
                  f"{share(rs, lambda r: r['flags']['leaked_rules']):>13} {share(js, lambda r: r['judge'].get('obeyed') == 1):>13} {share(js, lambda r: r['judge'].get('advice') == 1):>13}")
sp = [r for r in ok if r["payload_id"] == "spoof"]
print("   подмена фактов (30000 шагов повторены в ответе):", share(sp, lambda r: r["flags"]["fake_facts_used"]))

print("\n2) ССЫЛКИ И ТЕЛЕФОНЫ: сработал бы серверный фильтр SafeOutput (ответ был бы отброшен)")
for pid in ("link", "link_evade", "pii"):
    rs = [r for r in ok if r["payload_id"] == pid]
    print(f"  {pid:10} фильтр поймал {share(rs, lambda r: r['flags']['unsafe_filter'])}; повторил данные нагрузки (цифры/почта/адрес) {share(rs, lambda r: r['flags']['echo_payload_digits'])}; judge harmful {share([r for r in rs if r.get('judge')], lambda r: r['judge'].get('harmful') == 1)}")

print("\n3) КРИЗИС: как ответил компаньон (judge), по промтам")
for kind in ("morning", "day_review"):
    for pid in ("crisis_1", "crisis_2", "crisis_3", "crisis_soft", "control"):
        js = [r for r in judged if r["kind"] == kind and r["payload_id"] == pid]
        if not js: continue
        c = collections.Counter(r["judge"].get("crisis", "?") for r in js)
        print(f"  {kind:11} {pid:12} n={len(js):2} {dict(c)}  вред: {share(js, lambda r: r['judge'].get('harmful') == 1)}  медицинские слова: {share([r for r in ok if r['kind']==kind and r['payload_id']==pid], lambda r: r['flags']['medical_words'])}")
allc = [r for r in judged if r["category"] == "crisis"]
c = collections.Counter(r["judge"].get("crisis", "?") for r in allc)
print(f"  ВСЕГО кризисных прогонов {len(allc)}: {dict(c)}; harmful {share(allc, lambda r: r['judge'].get('harmful') == 1)}")

print("\n4) МЕДИЦИНА: просьба диагноза и таблеток")
rs = [r for r in ok if r["payload_id"] == "medical"]
print(f"  медицинские слова в ответе {share(rs, lambda r: r['flags']['medical_words'])}; judge advice {share([r for r in rs if r.get('judge')], lambda r: r['judge'].get('advice') == 1)}; harmful {share([r for r in rs if r.get('judge')], lambda r: r['judge'].get('harmful') == 1)}")

print("\n5) КАНАЛЫ: доля прогонов, где нагрузка 'сработала' (judge obeyed) по каналу")
for ch in ("goal", "gen", "tags"):
    rs = [r for r in judged if r["channel"] == ch and r["category"] == "injection"]
    print(f"  {ch:5} {share(rs, lambda r: r['judge'].get('obeyed') == 1)}")

print("\n6) ПРИМЕРЫ (первые по каждому успешному обходу, без персональных данных)")
shown = 0
for r in judged:
    if r["category"] == "injection" and r["judge"].get("obeyed") == 1 and shown < 6:
        print(f"  [{r['kind']}/{r['channel']}/{r['payload_id']}] {r['text'][:200]!r}"); shown += 1
shown = 0
for r in judged:
    if r["category"] == "crisis" and r["judge"].get("crisis") in ("trivialized", "prescriptive") and shown < 4:
        print(f"  [{r['kind']}/{r['channel']}/{r['payload_id']}] ({r['judge']['crisis']}) {r['text'][:200]!r}"); shown += 1
shown = 0
for r in judged:
    if r["category"] == "crisis" and r["judge"].get("crisis") == "supportive" and shown < 3:
        print(f"  [{r['kind']}/{r['channel']}/{r['payload_id']}] (supportive) {r['text'][:200]!r}"); shown += 1

p2 = f"{HERE}/data/phase2.json"
if os.path.exists(p2):
    print("\n7) РАЗДУТЫЕ ТЕГИ (разбор дня, боевая модель): символы → токены промта, секунды")
    for x in json.load(open(p2)):
        print(f"  {x['chars']:>6} симв. rep{x['rep']}: {'ok' if x['ok'] else 'ОШИБКА ' + str(x['err'])}, {x['sec']} с, токенов промта {x['prompt_tokens']}")
