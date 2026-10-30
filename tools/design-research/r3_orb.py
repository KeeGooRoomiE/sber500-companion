#!/usr/bin/env python3
"""
Раунд 3 по иконке, заданный руководителем исследования: автоматическая доработка ушла в полумесяцы
(6 из 12 финалистов), а судьи отметили, что мягкое свечение размывает орб в 48 px.
Здесь орб остаётся, контур чёткий, свечение минимальное, луны нет. Оценка той же панелью.
Результат: data/icon/r3.json, gen/r3_*.png, gallery_r3.html.
"""
import sys, json
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import research as R

COMMON = ("flat saturated violet background (#6B5CE7) with a very subtle gradient, no vignette, no halo and no blur around the orb, "
          "sharp crisp circular edge, strong contrast between orb and background, simple geometry, no face, no text, no crescent, no moon, no rings")
PROMPTS = {
    "a": "A single solid luminous orb: a smooth sphere with a subtle white-to-lavender gradient for volume, centered, on a " + COMMON,
    "b": "A single bright orb in lavender-white with a thin crisp lighter rim and soft inner shading toward the lower right, slightly above center, on a " + COMMON,
    "c": "A single pearl-like orb, almost flat, white with a very gentle lavender shade at the bottom, filling about 45 percent of the canvas width, centered, on a " + COMMON,
}


def main():
    out = R.DATA / "icon"
    R.init_spend(out)
    items = []
    for k, p in PROMPTS.items():
        for j, mdl in enumerate([R.IMG_MODELS[0], R.IMG_MODELS[0], R.IMG_MODELS[1]]):
            items.append({"id": f"{k}{j}", "prompt": R.ICON_WRAP.replace("{p}", p), "model": mdl, "direction": "d8", "name": f"Чёткий орб, вариант {k}"})
    c3 = R.stage_generate(out, "r3", items)
    cur = R.data_url(R.ROOT / "internal-docs/dev/rustore/icon-512.png", 384)
    refs = [R.img_part(R.data_url(p, 128)) for p in sorted((out / "refs").glob("*.png"))[:6]]
    rank = R.stage_panel(out, "r3", c3, R.ICON_PROMPT, R.ICON_CRITERIA, [R.img_part(cur)], refs)
    best = sorted(rank, key=lambda c: -rank[c]["mean"])
    (out / "r3.json").write_text(json.dumps({c: {k: v for k, v in r.items() if k != "votes"} for c, r in rank.items()}, ensure_ascii=False, indent=1))
    print("\nРАУНД 3 (ориентир: луна из финала r1/r2 набрала 6.58, лучший орб раунда 1 набрал 6.15):")
    for c in best:
        r = rank[c]
        print(f"  {c:8} оценка {r['mean']:.2f} | лучше текущей {r['wins']}/{r['n']} | читаемость48 {r['legibility_48']:.1f} | отличие {r['distinct']:.1f} | бренд {r['brand_fit']:.1f}")
    for c in best[:3]:
        for v in rank[c]["votes"]:
            print(f"    {c} {v['judge'][:12]}: {v['critique'][:150]}")
    R.write_gallery(out, "Иконка: раунд 3 (чёткий орб)", [("Раунд 3", [R.card_for(out, c, rank, c3[c]) for c in best])])
    (out / "gallery.html").replace(out / "gallery_r3.html")


if __name__ == "__main__":
    if not R.KEY:
        sys.exit("нет LLM_API_KEY")
    main()
