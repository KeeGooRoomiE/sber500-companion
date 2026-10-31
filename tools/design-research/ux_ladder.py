#!/usr/bin/env python3
"""
Лестница UX-исследования: один раунд одной модели.
Раунд 2 и 3 получают заметки этой же модели с предыдущего раунда плюс новый вектор от руководителя.

  python3 ux_ladder.py --model claude-opus-4.8 --round 1 --task "..." [--steps 10]
  bash tools/design-research/run.sh ux --model gpt-4.1-nano --round 2 --task "..."

Печатает отчёт и метрики прогона: время, шаги, инструменты, токены, и главное
«честность ссылок»: какая доля URL из отчёта действительно встречалась модели в результатах инструментов.
Результаты: data/ux/<модель>/r<N>.md и r<N>.metrics.json.
"""
import argparse, sys, time, json, re
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import research as R
from ask import TAIL

URL_RE = re.compile(r"https?://[^\s\)\]\>\"'`,;<]+")
SEEN = set()


def norm(u):
    return u.rstrip(".,;:!?)]»\"'").split("#")[0].rstrip("/").lower()


def wrap_tools():
    """Записываем URL, которые агент реально получил: из результатов инструментов и из аргументов успешных вызовов.
    Ссылка, которую модель придумала и безуспешно пыталась открыть, больше не считается «увиденной»."""
    for name, (impl, argname) in list(R.TOOL_IMPL.items()):
        def make(impl):
            def f(arg, ctx):
                text, img = impl(arg, ctx)
                for u in URL_RE.findall(text or ""):
                    SEEN.add(norm(u))
                if not (text or "").startswith(("Не удалось", "Ошибка", "Поиск не удался")):
                    for u in URL_RE.findall(arg or ""):
                        SEEN.add(norm(u))
                return text, img
            return f
        R.TOOL_IMPL[name] = (make(impl), argname)


def verified(url):
    n = norm(url)
    return any(n == s or n.startswith(s) or s.startswith(n) for s in SEEN)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--round", type=int, required=True)
    ap.add_argument("--task", required=True)
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--topic", default="", help="подпапка в data/ux/, чтобы разные лестницы (разные темы) не делили (модель, раунд)")
    ap.add_argument("--evidence", default="briefs/ux_evidence.md", help="какой файл фактов продукта подмешать в бриф")
    a = ap.parse_args()

    slug = a.model.replace(".", "-")
    out = R.DATA / "ux"
    mdir = (out / a.topic / slug) if a.topic else (out / slug)
    mdir.mkdir(parents=True, exist_ok=True)
    R.init_spend(out)
    rep_path = mdir / f"r{a.round}.md"
    met_path = mdir / f"r{a.round}.metrics.json"
    if rep_path.exists():
        print(f"[ux] раунд {a.round} для {a.model} уже есть")
        print(rep_path.read_text(encoding="utf-8"))
        return

    evidence = (R.HERE / a.evidence).read_text(encoding="utf-8")
    prev = ""
    if a.round > 1:
        pf = mdir / f"r{a.round - 1}.md"
        if not pf.exists():
            sys.exit(f"нет заметок раунда {a.round - 1} для {a.model}: сначала пройдите его")
        prev = "\n## Твои заметки с прошлого раунда (твой собственный вывод)\n" + re.sub(r"<!--.*?-->", "", pf.read_text(encoding="utf-8"), flags=re.S).strip()[:6000] + "\n"
    brief = evidence + prev + f"\n## Задание раунда {a.round}\n" + a.task + TAIL
    imgs = [R.data_url(R.ROOT / p, 500, quality=65) for p in
            ["web/screens/home-screen.png", "internal-docs/media/03-home-checkin-light.png", "internal-docs/media/05-onboarding-1.png"] if (R.ROOT / p).exists()]
    if imgs:
        brief += "\n(К сообщению приложены наши экраны: главный, чек-ин, первый шаг онбординга.)\n"

    wrap_tools()
    name = f"ux_{a.topic + '_' if a.topic else ''}{slug}_r{a.round}"
    t0 = time.time()
    rep = None
    for rnd in range(3):
        rep = R.run_agent(name, a.model, brief, imgs, out, "ux", a.steps)
        if rep:
            break
        print(f"[ux] повтор {rnd + 1} через 60с", file=sys.stderr, flush=True)
        time.sleep(60)
    wall = time.time() - t0
    if not rep:
        sys.exit("не удалось получить ответ")

    body = re.sub(r"<!--.*?-->", "", rep, flags=re.S).strip()
    cited = sorted({norm(u) for u in URL_RE.findall(body) if "..." not in u and "…" not in u})
    ok = [u for u in cited if verified(u)]
    stats = {}
    mm = re.search(r"статистика инструментов: (\{[^}]*\}), prompt-токенов (\d+)", (R.DATA / "ux" / f"agent_{name}.md").read_text(encoding="utf-8"))
    if mm:
        stats = json.loads(mm.group(1))
        stats["prompt_tokens"] = int(mm.group(2))
    steps = json.loads((out / f"agent_{name}.state.json").read_text(encoding="utf-8"))["step"] if (out / f"agent_{name}.state.json").exists() else None
    metrics = {"model": a.model, "round": a.round, "wall_s": round(wall), "steps": steps, **stats, "report_chars": len(body),
               "urls_cited": len(cited), "urls_seen_in_tools": len(ok),
               "citation_honesty": round(len(ok) / len(cited), 2) if cited else None,
               "unseen_urls": [u for u in cited if not verified(u)][:8]}
    rep_path.write_text(body + "\n", encoding="utf-8")
    try:
        import claimcheck
        prevp = mdir / f"r{a.round - 1}.md" if a.round > 1 else None
        cs, _ = claimcheck.check_report(rep_path, R.HERE / "briefs/ux_evidence.md", prevp)
        metrics["claims"] = cs
    except Exception as e:
        metrics["claims"] = f"ошибка проверки: {e}"
    met_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"===== {a.model}, раунд {a.round} =====")
    print(body)
    print("\n----- МЕТРИКИ -----")
    print(json.dumps(metrics, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    if not R.KEY:
        sys.exit("нет LLM_API_KEY (запускай через run.sh ux)")
    main()
