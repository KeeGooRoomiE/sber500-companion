#!/usr/bin/env python3
"""Тянет реальные дневные агрегаты (экран/сон/шаги/разблокировки/топ-приложения) + чек-ины
для N самых "богатых" по истории реальных пользователей (не dev/mock, не admin).

Только числа и даты — никаких имён/контактов/содержимого сообщений.

Пишет data/users.json:
  [{user_id, days: [{date, sleep_min, steps, screen_min, unlocks,
                      first_unlock, last_unlock, top_apps, feel, tags}]}]

Запуск: python3 tools/signal-rd/fetch.py [N=5]   (нужен ssh companion)
"""
import json, os, subprocess, sys, tempfile

N = int(sys.argv[1]) if len(sys.argv) > 1 else 5
HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(f"{HERE}/data", exist_ok=True)

SQL = f"""
WITH ex AS (SELECT id FROM users WHERE is_dev OR id LIKE 'mock%'),
rich AS (
  SELECT d.user_id, count(DISTINCT d.date) days
  FROM daily_data d WHERE d.user_id NOT IN (SELECT id FROM ex)
  GROUP BY d.user_id ORDER BY days DESC LIMIT {N}
)
SELECT json_agg(u) FROM (
  SELECT d.user_id,
    json_agg(json_build_object(
      'date', d.date, 'sleep_min', d.sleep_min, 'steps', d.steps,
      'screen_min', d.screen_min, 'unlocks', d.unlocks,
      'wakeup', d.wakeup, 'bedtime', d.bedtime,
      'first_unlock', d.first_unlock, 'last_unlock', d.last_unlock,
      'top_apps', d.top_apps, 'feel', c.day_feel, 'tags', c.tags
    ) ORDER BY d.date) AS days
  FROM daily_data d
  JOIN rich r ON r.user_id = d.user_id
  LEFT JOIN checkins c ON c.user_id = d.user_id AND c.date = d.date
  GROUP BY d.user_id
) u;
"""

with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
    f.write(SQL)
    sql_path = f.name

remote_sql = "/tmp/signal_rd_fetch.sql"
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
if not text or text == "":
    sys.exit("пустой ответ от БД")
rows = json.loads(text)
path = f"{HERE}/data/users.json"
json.dump(rows, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1, default=str)
total_days = sum(len(u["days"]) for u in rows)
print(f"{len(rows)} пользователей, {total_days} дней -> {path}")
