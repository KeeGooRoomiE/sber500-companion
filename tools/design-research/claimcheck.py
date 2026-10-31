#!/usr/bin/env python3
"""
Проверка чисел в отчёте модели по страницам, на которые она сослалась.

Для каждого числа с процентом (или «п.п.») в разделе «Ответ» проверяется, стоит ли оно на странице-источнике:
  supported    число найдено на странице источника;
  unsupported  страница открылась, числа на ней нет (вероятная выдумка или приписка);
  unknown      ни одну подходящую страницу открыть не удалось (капча, сеть): не проверено;
  ours         число из нашего пакета фактов или из заметок прошлого раунда (проверяется не здесь);
  estimate     собственный прогноз, условный пример или оговорка модели («ожидаемый эффект», «не включаю как подтверждённое»), не цитата.

Подходящие страницы для строки: те, чей домен упомянут в этой строке; если таких нет, все URL отчёта.
Для pmc.ncbi.nlm.nih.gov (там капча) берётся абстракт через открытое API Europe PMC.
Страницы кэшируются в data/ux/pagecache/, чтобы проверка воспроизводилась.

  python3 claimcheck.py data/ux/gpt-5-1/r3.md
"""
import re, sys, json, html, hashlib, subprocess, urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / "data" / "ux" / "pagecache"
URL_RE = re.compile(r"https?://[^\s\)\]\>\"'`,;<]+")
NUM_RE = re.compile(r"(?<![\w.,])(\d{1,3}(?:[.,]\d+)?)\s?(%|п\.\s?п\.?|pp\b|percent)", re.I)
SKIP_HOST = ("media.", "cdn.", "images.")
ESTIMATE_RE = re.compile(r"ожидан|ожидаем|эксперимент|условн|если сейчас|гипотез|не включаю|не привожу|не открыв|как подтвержд|порядок величины", re.I)


def curl(url, tries=2, timeout=30):
    for _ in range(tries):
        try:
            p = subprocess.run(["curl", "-sS", "-L", "--max-time", str(timeout), "-A", "Mozilla/5.0", url], capture_output=True)
            if p.returncode == 0 and p.stdout:
                return p.stdout.decode("utf-8", "ignore")
        except Exception:
            pass
    return None


def to_text(raw):
    raw = re.sub(r"(?is)<(script|style|noscript)[^>]*>.*?</\1>", " ", raw)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", raw)))


def page_text(url):
    """Текст страницы или None. Для PMC берём абстракт из Europe PMC."""
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / (hashlib.md5(url.encode()).hexdigest() + ".txt")
    if f.exists():
        t = f.read_text(encoding="utf-8")
        return t or None
    t = None
    m = re.search(r"pmc\.ncbi\.nlm\.nih\.gov/articles/(PMC\d+)", url, re.I)
    if m:
        raw = curl("https://www.ebi.ac.uk/europepmc/webservices/rest/search?" + urllib.parse.urlencode(
            {"query": f"PMCID:{m.group(1).upper()}", "format": "json", "resultType": "core"}))
        if raw:
            try:
                r = (json.loads(raw).get("resultList", {}).get("result") or [{}])[0]
                t = to_text((r.get("title") or "") + " " + (r.get("abstractText") or "")) or None
            except Exception:
                t = None
    else:
        raw = curl(url)
        if raw and "reCAPTCHA" not in raw[:4000] and "Checking your browser" not in raw[:4000]:
            t = to_text(raw)
            if len(t) < 300:
                t = None
    f.write_text(t or "", encoding="utf-8")
    return t


def norm(n):
    return n.replace(",", ".")


def present(num, text):
    n = re.escape(norm(num)).replace(r"\.", r"[.,]")
    return bool(re.search(r"(?<![\d.,])" + n + r"\s?(%|п\.\s?п|pp\b|percent|per cent)", text, re.I))


def check_report(report_path, evidence_path=None, prev_path=None):
    body = Path(report_path).read_text(encoding="utf-8")
    answer = body.split("## Источники")[0]
    urls = [u.rstrip(".,;:!?") for u in URL_RE.findall(body) if "..." not in u and "…" not in u and not any(h in u for h in SKIP_HOST)]
    urls = list(dict.fromkeys(urls))
    ours_text = ""
    for p in (evidence_path, prev_path):
        if p and Path(p).exists():
            ours_text += Path(p).read_text(encoding="utf-8")
    ours = {norm(m.group(1)) for m in NUM_RE.finditer(ours_text)}
    pages = {}
    results, seen_nums = [], set()
    for line in answer.splitlines():
        nums = [(norm(m.group(1)), m.group(0)) for m in NUM_RE.finditer(line)]
        if not nums:
            continue
        low = line.lower()
        if ESTIMATE_RE.search(line):
            for n, raw in nums:
                results.append({"number": raw, "status": "estimate", "line": line.strip()[:140]})
            continue
        cand = [u for u in urls if re.sub(r"^www\.", "", urllib.parse.urlparse(u).netloc.lower()).split(".")[0] in low]
        cand = cand or urls
        for n, raw in nums:
            if (n, line[:60]) in seen_nums:
                continue
            seen_nums.add((n, line[:60]))
            if n in ours:
                results.append({"number": raw, "status": "ours", "line": line.strip()[:140]})
                continue
            status, where = "unknown", None
            reachable = False
            for u in cand:
                if u not in pages:
                    pages[u] = page_text(u)
                if pages[u]:
                    reachable = True
                    if present(n, pages[u]):
                        status, where = "supported", u
                        break
            if status != "supported":
                status = "unsupported" if reachable else "unknown"
            results.append({"number": raw, "status": status, "page": where, "line": line.strip()[:140]})
    summary = {k: sum(1 for r in results if r["status"] == k) for k in ("supported", "unsupported", "unknown", "ours", "estimate")}
    summary["total"] = len(results)
    return summary, results


if __name__ == "__main__":
    rp = sys.argv[1]
    ev = HERE / "briefs/ux_evidence.md"
    rn = re.search(r"r(\d+)\.md$", rp)
    prev = Path(rp).with_name(f"r{int(rn.group(1)) - 1}.md") if rn and int(rn.group(1)) > 1 else None
    summary, results = check_report(rp, ev, prev)
    Path(rp).with_suffix(".claimcheck.json").write_text(json.dumps({"summary": summary, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(rp, summary)
    for r in results:
        if r["status"] in ("unsupported", "unknown"):
            print(f"  [{r['status']}] {r['number']} :: {r['line']}")
