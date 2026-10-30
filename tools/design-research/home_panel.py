#!/usr/bin/env python3
"""
Панель оценки вариантов главного экрана: три модели с видением смотрят настоящие скриншоты вариантов
(из home_variants.py), сравнивают с текущим экраном и проходят «тест пяти секунд».
Результат: data/home/panel.jsonl и сводка в stdout.  Нужен предварительно home_variants.py.
"""
import sys, json, collections
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parent))
import research as R
import home_variants as HV

OUT = R.DATA / "home"
CRIT = {"five_sec": 0.25, "flow": 0.2, "companion": 0.2, "calm": 0.15, "history": 0.1, "tone": 0.1}
SYS = ("Ты строгий продуктовый дизайнер мобильных приложений. Оценивай по принципам дизайна и по тому, что реально видно на скриншоте; "
       "у тебя нет данных о поведении пользователей, не делай вид, что они есть. Будь критичен и конкретен. Ответь ТОЛЬКО JSON, первый символ {.")
PROMPT = """Приложение Companion: спокойный компаньон, который по данным телефона объясняет, почему вчера был тяжёлый день. Тон наблюдателя, без давления, стриков и оценок.
На главном экране нужно естественно уместить пять вещей: (1) анимированный компаньон-орб, (2) карточку с прогнозом дня, (3) метрики, (4) историю прошлых прогнозов, (5) вечерний чек-ин «Как прошёл день?» в одно касание.
Поведение пользователей: приходят читать прогноз, редко идут глубже, чек-ин делают редко.

Изображение 1: ТЕКУЩИЙ экран. Изображение 2: ВАРИАНТ «{name}».
Описание варианта (что видно сразу, что спрятано): {desc}

Сначала пройди тест пяти секунд: опиши в 2-3 предложениях, что ты понял с варианта за пять секунд (главное сообщение, где компаньон, что можно сделать, где история). Затем оцени ВАРИАНТ по шкале 1-10:
five_sec: за 5 секунд понятно главное сообщение дня и что делать дальше;
flow: естественность пользовательского потока (утро прогноз, днём как идёт, вечером чек-ин, затем история), понятно ли, куда идти дальше;
companion: компаньон присутствует естественно (не декорация в углу и не мешает тексту), связан с прогнозом;
calm: спокойствие и плотность, нет перегруза;
history: история находится и понятна;
tone: соответствие тону наблюдателя (без давления, геймификации).
vs_current: "better" | "same" | "worse" относительно текущего экрана. critique: главное, что не так (1-2 предложения).
JSON: {"first_noticed": "", "five_sec": 0, "flow": 0, "companion": 0, "calm": 0, "history": 0, "tone": 0, "vs_current": "same", "critique": ""}"""


def main():
    pf = OUT / "panel.jsonl"
    done = set()
    if pf.exists():
        for l in open(pf, encoding="utf-8"):
            try:
                r = json.loads(l)
                done.add((r["vid"], r["judge"]))
            except Exception:
                pass
    cur = R.img_part(R.data_url(OUT / "variants/v0_current.png", 900, quality=60))

    def one(t):
        vid, name, desc, judge = t
        if (vid, judge) in done:
            return
        img = R.img_part(R.data_url(OUT / f"variants/{vid}.png", 900, quality=60))
        try:
            resp = R.llm_patient(judge, [{"role": "system", "content": SYS}, {"role": "user", "content": [{"type": "text", "text": PROMPT.replace("{name}", name).replace("{desc}", desc)}, cur, img]}], max_tokens=1800)
            sc = R.extract_json(resp["choices"][0]["message"].get("content") or "")
            if not sc or not all(k in sc for k in CRIT):
                raise ValueError("нет оценок")
            with open(pf, "a", encoding="utf-8") as f:
                f.write(json.dumps({"vid": vid, "judge": judge, **sc}, ensure_ascii=False) + "\n")
            print("ok", vid, judge, flush=True)
        except Exception as e:
            print("сбой", vid, judge, str(e)[:100], flush=True)

    only = set(sys.argv[1:])
    tasks = [(v[0], v[1], v[2], j) for v in HV.VARIANTS for j in R.JUDGES if not only or v[0] in only]
    with ThreadPoolExecutor(3) as ex:
        list(ex.map(one, tasks))

    by = collections.defaultdict(list)
    for l in open(pf, encoding="utf-8"):
        r = json.loads(l)
        by[r["vid"]].append(r)
    names = {v[0]: v[1] for v in HV.VARIANTS}
    print("\nРЕЙТИНГ (оценка / лучше текущего / 5с / поток / компаньон / спокойствие / история / тон):")
    rows = []
    for vid, v in by.items():
        tot = sum(sum(float(x[k]) * w for k, w in CRIT.items()) for x in v) / len(v)
        avg = {k: sum(float(x[k]) for x in v) / len(v) for k in CRIT}
        rows.append((tot, vid, len(v), sum(1 for x in v if x["vs_current"] == "better"), avg))
    for tot, vid, n, b, a in sorted(rows, reverse=True):
        print(f"  {names[vid][:30]:30} {tot:.2f} | {b}/{n} | " + " / ".join(f"{a[k]:.1f}" for k in CRIT))
    print("\nТЕСТ ПЯТИ СЕКУНД И КРИТИКА:")
    for tot, vid, n, b, a in sorted(rows, reverse=True):
        print(f"\n## {names[vid]}")
        for x in by[vid]:
            print(f"  [{x['judge'][:10]}] увидел: {x['first_noticed'][:230]}\n      критика: {x['critique'][:230]}")


if __name__ == "__main__":
    if not R.KEY:
        sys.exit("нет LLM_API_KEY")
    main()
