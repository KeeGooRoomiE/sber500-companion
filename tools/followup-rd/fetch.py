#!/usr/bin/env python3
"""Тянет самый свежий реальный day_review (текст, уже показанный юзеру) для тех же 5
"богатых" пользователей, что и tools/signal-rd — переиспользуем их data/users.json как
дневные данные, здесь добираем только сам инсайт.

Пишет data/reviews.json: [{user_id, date, text}]

Запуск: python3 tools/followup-rd/fetch.py   (нужен ssh companion; users.json должен
быть собран заранее: python3 tools/signal-rd/fetch.py 5)
"""
import json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SIGNAL_RD_USERS = os.path.join(HERE, "..", "signal-rd", "data", "users.json")
os.makedirs(f"{HERE}/data", exist_ok=True)

if not os.path.exists(SIGNAL_RD_USERS):
    sys.exit(f"нет {SIGNAL_RD_USERS} — сначала: python3 tools/signal-rd/fetch.py 5")

user_ids = [u["user_id"] for u in json.load(open(SIGNAL_RD_USERS, encoding="utf-8"))]
ids_sql = ",".join(f"'{uid}'" for uid in user_ids)

SQL = f"""
SELECT json_agg(r) FROM (
  SELECT DISTINCT ON (user_id) user_id, date, text
  FROM reviews
  WHERE kind = 'day' AND user_id IN ({ids_sql})
  ORDER BY user_id, date DESC
) r;
"""

with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
    f.write(SQL)
    sql_path = f.name

remote_sql = "/tmp/followup_rd_fetch.sql"
subprocess.run(["scp", "-q", "-o", "ConnectTimeout=20", sql_path, f"companion:{remote_sql}"],
                check=True, timeout=30)
out = subprocess.run(
    ["ssh", "-o", "ConnectTimeout=20", "companion",
     f'set -a; . /opt/companion/.env; set +a; psql "$DATABASE_URL" -t -A -f {remote_sql}'],
    capture_output=True, text=True, timeout=60,
)
os.unlink(sql_path)
if out.returncode != 0:
    sys.exit(f"psql failed: {out.stderr[:500]}")

text = out.stdout.strip()
if not text:
    sys.exit("пустой ответ от БД")
rows = json.loads(text)
path = f"{HERE}/data/reviews.json"
json.dump(rows, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
print(f"{len(rows)} реальных day_review -> {path}")
for r in rows:
    print(f"  {r['user_id']} {r['date']}: {r['text'][:80]}...")
