-- Выгрузка для usage-rd: user_id хэшируется на сервере (8 символов md5), dev/mock исключены.
\pset tuples_only on
\pset format unaligned
CREATE TEMP VIEW ok_users AS SELECT id, left(md5('usage-rd:'||id),8) AS h, created_at FROM users WHERE NOT is_dev AND id NOT LIKE 'mock%';
\o /tmp/ur_users.jsonl
SELECT json_build_object('u',h,'created',created_at) FROM ok_users;
\o /tmp/ur_morning.jsonl
SELECT json_build_object('u',o.h,'date',m.date,'text',m.message,'sent',m.sent_at,'opened',m.opened_at,'created',m.created_at,'pv',m.prompt_version) FROM morning_messages m JOIN ok_users o ON o.id=m.user_id WHERE m.message<>'';
\o /tmp/ur_calls.jsonl
SELECT json_build_object('u',o.h,'ts',c.ts,'type',c.call_type,'comp',c.component,'trig',c.trigger,'vis',c.user_visible,'res',c.result,'lat',c.latency_ms) FROM call_log c JOIN ok_users o ON o.id=c.user_id;
\o /tmp/ur_reviews.jsonl
SELECT json_build_object('u',o.h,'kind',r.kind,'date',r.date,'created',r.created_at) FROM reviews r JOIN ok_users o ON o.id=r.user_id;
\o /tmp/ur_explore.jsonl
SELECT json_build_object('u',o.h,'q',e.question_id,'created',e.created_at) FROM explore_answers e JOIN ok_users o ON o.id=e.user_id;
\o /tmp/ur_checkins.jsonl
SELECT json_build_object('u',o.h,'date',c.date,'feel',c.day_feel,'created',c.created_at) FROM checkins c JOIN ok_users o ON o.id=c.user_id;
\o /tmp/ur_activity.jsonl
SELECT json_build_object('u',o.h,'date',a.date) FROM user_activity a JOIN ok_users o ON o.id=a.user_id;
\o /tmp/ur_daily.jsonl
SELECT json_build_object('u',o.h,'date',d.date,'first',d.first_unlock,'last',d.last_unlock,'wake',d.wakeup,'bed',d.bedtime,'screen',d.screen_min,'unlocks',d.unlocks,'steps',d.steps,'sleep',d.sleep_min,'hu',d.hourly_unlocks) FROM daily_data d JOIN ok_users o ON o.id=d.user_id;
\o
