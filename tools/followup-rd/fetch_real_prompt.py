#!/usr/bin/env python3
"""Исправление: prod day_review_system опирается не только на сырые дневные цифры, но и на
ВЫЧИСЛЕННЫЙ почасовой сигнальный блок (longestGap/falseStart/nightWakings/... —
backend/internal/signals). Наш fmt_day в signal-rd его не показывает — значит judge мог
помечать как "выдумку" то, что реально видно в сигналах, которых мы модели не показали.

Вместо переноса signals.go на Python тянем РЕАЛЬНЫЙ отрендеренный промт прямо с сервера
через admin preview (call_llm=false — бесплатно, без траты бюджета): байт-в-байт то же, что
видит прод. admin.render() всегда строит для "вчера" относительно серверных часов, поэтому
берём только тех юзеров, у кого реальный day_review есть именно на эту дату.

Пишет data/real_prompts.json: [{user_id, date, insight(=review.text), prompt(=real rendered user)}]

Запуск: python3 tools/followup-rd/fetch_real_prompt.py
"""
import json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(f"{HERE}/data", exist_ok=True)

SIGNAL_RD_USERS = os.path.join(HERE, "..", "signal-rd", "data", "users.json")
user_ids = [u["user_id"] for u in json.load(open(SIGNAL_RD_USERS, encoding="utf-8"))]

# 1) узнаём серверное "вчера" и у кого из наших юзеров есть day_review именно на эту дату
with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
    ids_sql = ",".join(f"'{uid}'" for uid in user_ids)
    f.write(f"""
    SELECT json_agg(json_build_object('user_id', user_id, 'date', date, 'text', text))
    FROM reviews
    WHERE kind='day' AND date = (now() AT TIME ZONE 'Europe/Moscow')::date - 1
      AND user_id IN ({ids_sql});
    """)
    sql_path = f.name
remote_sql = "/tmp/followup_rd_yesterday.sql"
subprocess.run(["scp", "-q", "-o", "ConnectTimeout=20", sql_path, f"companion:{remote_sql}"], check=True, timeout=30)
out = subprocess.run(
    ["ssh", "-o", "ConnectTimeout=20", "companion",
     f'set -a; . /opt/companion/.env; set +a; psql "$DATABASE_URL" -t -A -f {remote_sql}'],
    capture_output=True, text=True, timeout=60,
)
os.unlink(sql_path)
if out.returncode != 0:
    sys.exit(f"psql failed: {out.stderr[:500]}")
rows = json.loads(out.stdout.strip())
if not rows:
    sys.exit("ни у кого из наших юзеров нет day_review на серверное 'вчера' — нечего тянуть")
print(f"{len(rows)} юзеров имеют day_review на вчера: {[r['user_id'] for r in rows]}")

# 2) для каждого — реальный промт через admin preview (call_llm=false, бесплатно)
remote_admin = (
    "set -a; . /opt/companion/.env; set +a; "
    "curl -sS -X POST \"http://${ADMIN_ADDR:-127.0.0.1:9090}/admin/prompts/day_review_system/preview\" "
    "-H \"Authorization: Bearer $ADMIN_TOKEN\" -H 'Content-Type: application/json' --data-binary @-"
)
results = []
for r in rows:
    body = json.dumps({"user_id": r["user_id"], "body": "", "call_llm": False})
    out = subprocess.run(["ssh", "-o", "ConnectTimeout=20", "companion", remote_admin],
                          input=body, capture_output=True, text=True, timeout=60)
    try:
        d = json.loads(out.stdout)
    except ValueError:
        print(f"  {r['user_id']}: ответ не JSON: {(out.stdout or out.stderr)[:200]}")
        continue
    if d.get("error") or not d.get("user"):
        print(f"  {r['user_id']}: {d.get('error', 'пустой user prompt')}")
        continue
    results.append({"user_id": r["user_id"], "date": r["date"], "insight": r["text"], "prompt": d["user"]})
    print(f"  {r['user_id']}: промт {len(d['user'])} симв., сигналов {len(d.get('signals', []))}")

path = f"{HERE}/data/real_prompts.json"
json.dump(results, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"\n{len(results)} реальных промтов -> {path}")
