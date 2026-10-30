-- Время генерации каждого выхода (для отделения «день уже закончился» от «день ещё идёт»). user_id не выгружаем.
\pset tuples_only on
\pset format unaligned
\o /tmp/audit_times.jsonl
SELECT json_build_object('kind',kind,'date',date,'text',text,'created_at',created_at) FROM (
  SELECT 'morning' AS kind, date, message AS text, created_at FROM morning_messages WHERE message<>''
  UNION ALL SELECT kind, date, text, created_at FROM reviews
  UNION ALL SELECT 'explore:'||question_id, date, text, created_at FROM explore_answers) t;
\o
