#!/usr/bin/env python3
"""
Перепись иконок: все иконки, которые агенты реально открыли (refs/), размечаются моделью с видением
(тон фона, оттенок, тип объекта), затем считаются доли. Выборка смещена: это то, что нашли поиском
по теме wellness/сон/настроение/экранное время, а не весь рынок.

  python3 census.py     (через run.sh не нужен, но ключ берётся из env как у остальных скриптов)
Результат: data/census.json и сводка в stdout.
"""
import sys, json, hashlib, collections
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parent))
import research as R

MODEL = "gpt-5.1"
BATCH = 10
PROMPT = """Перед тобой {n} изображений по порядку (номера 1..{n}). Это иконки мобильных приложений (часть файлов может оказаться не иконкой: баннер, скриншот, обрезок).
Для каждого изображения верни разметку. Верни ТОЛЬКО JSON: {"items": [{"n": 1, "is_app_icon": true, "bg": "dark|mid|light", "hue": "purple|blue|teal|green|yellow|orange|red|pink|neutral|multi", "subject": "orb_circle|ring|mascot_character|text_logotype|glyph_symbol|abstract_shape|illustration_scene|other", "has_text": false}]}
Определения: bg это преобладающая яркость фона иконки (dark тёмный, mid насыщенный средний, light светлый или белый). hue это доминирующий цветовой оттенок иконки в целом. subject: orb_circle заполненный круг или шар, ring кольцо, mascot_character персонаж с лицом, text_logotype надпись или буква как главный элемент, glyph_symbol узнаваемый символ или пиктограмма, abstract_shape абстрактная форма, illustration_scene сцена или иллюстрация."""


def main():
    seen, files = set(), []
    for d in [R.DATA / "icon" / "refs", R.DATA / "ask" / "refs", R.DATA / "_failed_run_1" / "icon" / "refs"]:
        for p in sorted(d.glob("*.png")):
            h = hashlib.md5(p.read_bytes()).hexdigest()
            if h not in seen:
                seen.add(h)
                files.append(p)
    print(f"уникальных изображений: {len(files)}", flush=True)
    batches = [files[i:i + BATCH] for i in range(0, len(files), BATCH)]

    def one(b):
        imgs = [R.img_part(R.data_url(p, 192, quality=70)) for p in b]
        resp = R.llm_patient(MODEL, [{"role": "user", "content": [{"type": "text", "text": PROMPT.replace("{n}", str(len(b)))}] + imgs}], max_tokens=3000)
        d = R.extract_json(resp["choices"][0]["message"].get("content") or "") or {}
        res = []
        for it in d.get("items", []):
            n = it.get("n")
            if isinstance(n, int) and 1 <= n <= len(b):
                res.append({**it, "file": str(b[n - 1].relative_to(R.DATA))})
        return res

    items = []
    with ThreadPoolExecutor(3) as ex:
        for r in ex.map(one, batches):
            items += r
    (R.DATA / "census.json").write_text(json.dumps(items, ensure_ascii=False, indent=1))
    icons = [i for i in items if i.get("is_app_icon")]
    n = len(icons)
    print(f"размечено {len(items)}, из них иконок {n}\n")

    def share(key, title):
        c = collections.Counter(i.get(key) for i in icons)
        print(title + ": " + ", ".join(f"{k} {v} ({100 * v / n:.0f}%)" for k, v in c.most_common()))

    share("bg", "тон фона")
    share("hue", "оттенок")
    share("subject", "тип объекта")
    pur = [i for i in icons if i.get("hue") == "purple"]
    print(f"\nфиолетовых иконок: {len(pur)} ({100 * len(pur) / max(n, 1):.0f}%); по фону: " + str(dict(collections.Counter(i.get('bg') for i in pur))))
    print("фиолетовые по типу: " + str(dict(collections.Counter(i.get('subject') for i in pur))))
    rc = [i for i in icons if i.get("subject") in ("orb_circle", "ring")]
    print(f"круг/шар/кольцо: {len(rc)} ({100 * len(rc) / max(n, 1):.0f}%); из них по фону: " + str(dict(collections.Counter(i.get('bg') for i in rc))))
    print("круг/шар/кольцо по оттенку: " + str(dict(collections.Counter(i.get('hue') for i in rc))))
    po = [i for i in rc if i.get("hue") == "purple"]
    print(f"фиолетовый круг/шар/кольцо (наша зона): {len(po)} -> " + ", ".join(i["file"] for i in po[:8]))


if __name__ == "__main__":
    if not R.KEY:
        sys.exit("нет LLM_API_KEY")
    main()
