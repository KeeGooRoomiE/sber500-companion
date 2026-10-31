\echo === 1. Колонки call_log
SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name='call_log' ORDER BY ordinal_position;
\echo === 2. Объём и период
SELECT count(*) total, count(*) FILTER (WHERE call_type='llm') llm, count(*) FILTER (WHERE call_type='tool') tool, min(ts) first_ts, max(ts) last_ts FROM call_log;
\echo === 3. Пустые обязательные поля (NULL или пустая строка)
SELECT count(*) FILTER (WHERE user_id IS NULL OR user_id='') user_id, count(*) FILTER (WHERE ts IS NULL) ts,
       count(*) FILTER (WHERE call_type IS NULL OR call_type='') call_type, count(*) FILTER (WHERE component IS NULL OR component='') component,
       count(*) FILTER (WHERE trigger IS NULL OR trigger='') trigger, count(*) FILTER (WHERE user_visible IS NULL) user_visible,
       count(*) FILTER (WHERE result IS NULL OR result='') result, count(*) FILTER (WHERE latency_ms IS NULL) latency_null,
       count(*) FILTER (WHERE call_type='llm' AND (latency_ms IS NULL OR latency_ms=0)) llm_latency_zero_or_null FROM call_log;
\echo === 4. Чьи это строки (служебные и тестовые отделяются от обычных)
SELECT k AS класс, call_type, count(*) AS строк FROM (
  SELECT c.call_type, CASE WHEN c.user_id='admin' THEN 'admin' WHEN u.id IS NULL THEN 'нет записи в users' WHEN u.is_dev THEN 'dev' WHEN u.id LIKE 'mock%' THEN 'mock' ELSE 'обычный' END k
  FROM call_log c LEFT JOIN users u ON u.id=c.user_id) t GROUP BY 1,2 ORDER BY 1,2;
\echo === 5. user_visible по типу и триггеру
SELECT call_type, trigger, user_visible, count(*) FROM call_log GROUP BY 1,2,3 ORDER BY 1,2,3;
\echo === 6. Результаты по типам
SELECT call_type, result, count(*) FROM call_log GROUP BY 1,2 ORDER BY 1,3 DESC LIMIT 14;
\echo === 7. Аномалии времени и дубли
SELECT count(*) FILTER (WHERE ts > now()) future_ts,
       (SELECT count(*) FROM (SELECT user_id, component, ts FROM call_log GROUP BY 1,2,3 HAVING count(*)>1) d) duplicate_keys FROM call_log;
\echo === 8. Строки с call_type=llm без соответствующего «обычного» пользователя (если это допустимо, 0)
SELECT count(*) FROM call_log c JOIN users u ON u.id=c.user_id WHERE c.call_type='llm' AND (u.is_dev OR u.id LIKE 'mock%');
\echo === 9. Сколько человек получили хотя бы 1 вызов, и сколько из них не имеют ни одного дня активности (user_activity)
SELECT count(DISTINCT c.user_id) users_in_log, count(DISTINCT c.user_id) FILTER (WHERE NOT EXISTS (SELECT 1 FROM user_activity a WHERE a.user_id=c.user_id)) never_active
FROM call_log c JOIN users u ON u.id=c.user_id WHERE NOT u.is_dev AND u.id NOT LIKE 'mock%';
