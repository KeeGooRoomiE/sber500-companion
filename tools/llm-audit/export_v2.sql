-- Полный контекст для judge: теги чек-инов, структурный профиль (без свободных ответов и имени), нормы по всей истории, дни недели считаются в audit.py.
\pset tuples_only on
\pset format unaligned
\o /tmp/audit_export_v2.jsonl
WITH outs AS (
  SELECT 'morning' src, 'morning' kind, user_id, date, message AS text FROM morning_messages WHERE message<>''
  UNION ALL SELECT 'review', kind, user_id, date, text FROM reviews
  UNION ALL SELECT 'explore', 'explore:'||question_id, user_id, date, text FROM explore_answers
)
SELECT json_build_object('src',o.src,'kind',o.kind,'date',o.date,'text',o.text,
  'days',(SELECT json_agg(json_build_object('d',d.date,'screen',d.screen_min,'sleep',d.sleep_min,'unlocks',d.unlocks,
            'steps',d.steps,'wake',d.wakeup,'first_unlock',d.first_unlock,'last_unlock',d.last_unlock,'apps',d.top_apps,
            'feel',(SELECT c.day_feel FROM checkins c WHERE c.user_id=o.user_id AND c.date=d.date),
            'tags',(SELECT c.tags FROM checkins c WHERE c.user_id=o.user_id AND c.date=d.date)) ORDER BY d.date)
          FROM daily_data d WHERE d.user_id=o.user_id AND d.date BETWEEN o.date-7 AND o.date),
  'profile',(SELECT json_build_object('goal',u.profile->>'goal','tone',u.profile->>'tone','wake',u.profile->>'wake','bedtime',u.profile->>'bedtime',
                'triggers',u.profile->>'triggers','wearable',u.profile->>'wearable','work_apps',u.profile->>'work_apps')
             FROM users u WHERE u.id=o.user_id),
  'norms',(SELECT json_build_object('days',count(*),'screen',round(avg(d2.screen_min)),'sleep',round(avg(d2.sleep_min)),
                'unlocks',round(avg(d2.unlocks)),'steps',round(avg(d2.steps)))
             FROM daily_data d2 WHERE d2.user_id=o.user_id AND d2.date<=o.date))
FROM outs o;
\o
