#!/usr/bin/env python3
"""Забирает с сервера отрендеренные входы утреннего промта для тестовых персон (mock_*).
Пишет data/batch_<prompt>.json: [{user_id, title, user, signals, truth}]. LLM на сервере не вызывается.

Запуск: python3 tools/prompt-lab/fetch.py [prompt_name]   (по умолчанию morning_system; нужен ssh companion)
"""
import json, os, subprocess, sys

NAME = sys.argv[1] if len(sys.argv) > 1 else "morning_system"
HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(f"{HERE}/data", exist_ok=True)

body = json.dumps({"body": "", "call_llm": False, "with_prompt": True})
remote = (
    "set -a; . /opt/companion/.env; set +a; "
    f"curl -sS -X POST \"http://${{ADMIN_ADDR:-127.0.0.1:9090}}/admin/prompts/{NAME}/eval\" "
    "-H \"Authorization: Bearer $ADMIN_TOKEN\" -H 'Content-Type: application/json' --data-binary @-"
)
out = subprocess.run(["ssh", "-o", "ConnectTimeout=20", "root@companion", remote], input=body, capture_output=True, text=True, timeout=180)
try:
    d = json.loads(out.stdout)
except ValueError:
    sys.exit("ответ сервера не JSON:\n" + (out.stdout or out.stderr)[:400])
rows = [{"user_id": r["user_id"], "title": r.get("title", ""), "user": r.get("user", ""),
         "signals": [f"{s['title']}: {s['detail']}" for s in (r.get("signals") or [])], "truth": r.get("truth", "")}
        for r in d.get("results", []) if r.get("user")]
path = f"{HERE}/data/batch_{NAME}.json"
json.dump(rows, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"{len(rows)} входов -> {path}")
