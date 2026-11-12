-- ============================================================================
-- 埋点库只读账号核验（analytics-pipeline Story 4.8 AC4 / AC7，架构 A8 / FR-27；L2，在服务器手工执行）
--
-- 以超级用户连【埋点库】执行，用 -v reader=... 传只读角色名（生产 / stag 各跑一次）：
--   docker exec -i petgo-postgres psql -U petgo -d petgo_analytics_stag -v reader=analytics_reader_stag \
--     < ai-query-agent/deploy/sql/verify_analytics_reader.sql
--   docker exec -i petgo-postgres psql -U petgo -d petgo_analytics -v reader=analytics_reader \
--     < ai-query-agent/deploy/sql/verify_analytics_reader.sql
-- 只读，不改任何东西。每一项都输出 expected / actual / ok 三列，ok 全为 t 即通过。
-- 账号由 scripts/ops/create-analytics-db.sql 建；表权限由埋点库迁移逐表 GRANT（不用 ALTER DEFAULT PRIVILEGES）。
-- ============================================================================
\set ON_ERROR_STOP on

\if :{?reader}
\else
  \echo '缺少 -v reader=<只读角色名>（analytics_reader / analytics_reader_stag），未做任何查询'
  \quit
\endif

\echo '== 1. 角色属性：LOGIN、NOINHERIT、CONNECTION LIMIT 3、非超级用户 =='
SELECT 'rolinherit=f, rolconnlimit=3, rolsuper=f, rolcreatedb=f, rolcreaterole=f' AS expected,
       format('rolinherit=%s, rolconnlimit=%s, rolsuper=%s, rolcreatedb=%s, rolcreaterole=%s',
              rolinherit, rolconnlimit, rolsuper, rolcreatedb, rolcreaterole) AS actual,
       rolcanlogin AND NOT rolinherit AND rolconnlimit = 3 AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AS ok
  FROM pg_roles WHERE rolname = :'reader';

\echo '== 2. 角色级会话默认值：statement_timeout=30s、idle_in_transaction_session_timeout=60s、default_transaction_read_only=on =='
SELECT 'statement_timeout=30s, idle_in_transaction_session_timeout=60s, default_transaction_read_only=on' AS expected,
       coalesce(array_to_string(s.setconfig, ', '), '（无角色级设置）') AS actual,
       coalesce(s.setconfig @> ARRAY['statement_timeout=30s', 'idle_in_transaction_session_timeout=60s',
                                     'default_transaction_read_only=on'], false) AS ok
  FROM pg_roles r
  LEFT JOIN pg_db_role_setting s ON s.setrole = r.oid AND s.setdatabase = 0
 WHERE r.rolname = :'reader';

\echo '== 3. 不是任何角色的成员：期望 0 行 =='
SELECT r.rolname AS member_of
  FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
 WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = :'reader');

\echo '== 4. 库级权限：能 CONNECT 本库；不能 CREATE / TEMP（create-analytics-db.sql 已 REVOKE ALL FROM PUBLIC） =='
SELECT 'connect=t, create=f, temp=f' AS expected,
       format('connect=%s, create=%s, temp=%s',
              has_database_privilege(:'reader', current_database(), 'CONNECT'),
              has_database_privilege(:'reader', current_database(), 'CREATE'),
              has_database_privilege(:'reader', current_database(), 'TEMP')) AS actual,
       has_database_privilege(:'reader', current_database(), 'CONNECT')
       AND NOT has_database_privilege(:'reader', current_database(), 'CREATE')
       AND NOT has_database_privilege(:'reader', current_database(), 'TEMP') AS ok;

\echo '== 5. public schema 不能建表 =='
SELECT 'create_in_public=f' AS expected,
       format('create_in_public=%s', has_schema_privilege(:'reader', 'public', 'CREATE')) AS actual,
       NOT has_schema_privilege(:'reader', 'public', 'CREATE') AS ok;

\echo '== 6. 白名单表 / 视图可读（SELECT = t） =='
SELECT t AS relation, 'select=t' AS expected,
       CASE WHEN to_regclass(t) IS NULL THEN '表不存在（迁移未跑全？）'
            ELSE format('select=%s', has_table_privilege(:'reader', t, 'SELECT')) END AS actual,
       CASE WHEN to_regclass(t) IS NULL THEN false ELSE has_table_privilege(:'reader', t, 'SELECT') END AS ok
  FROM unnest(ARRAY[
         'events_detail', 'identity_links', 'v_events_person', 'agg_progress', 'ga4_daily_snapshot',
         'event_catalog', 'metric_definitions',
         'agg_daily_activity', 'agg_daily_install', 'agg_daily_dau_reconcile', 'agg_cohort_retention',
         'agg_monthly_install_profile', 'agg_daily_placement', 'agg_weekly_placement', 'agg_monthly_placement',
         'agg_daily_push', 'agg_weekly_push', 'agg_monthly_push', 'agg_daily_toko', 'agg_weekly_toko',
         'agg_monthly_toko', 'agg_daily_cart', 'agg_daily_product', 'agg_daily_ratio']) AS t
 ORDER BY 1;

\echo '== 7. 不授权的表不可读（SELECT = f）：events_raw、当天（UTC）分区 events_raw_p<yyyyMMdd>、events_raw_pdefault、pipeline_progress 等 =='
SELECT t AS relation, 'select=f' AS expected,
       CASE WHEN to_regclass(t) IS NULL THEN '不存在'
            ELSE format('select=%s', has_table_privilege(:'reader', t, 'SELECT')) END AS actual,
       CASE WHEN to_regclass(t) IS NULL THEN true ELSE NOT has_table_privilege(:'reader', t, 'SELECT') END AS ok
  FROM unnest(ARRAY[
         'events_raw', 'events_raw_p' || to_char(now() AT TIME ZONE 'UTC', 'YYYYMMDD'), 'events_raw_pdefault',
         'pipeline_progress', 'detail_recompute_requests', 'storage_watermarks', 'rollup_rerun_requests']) AS t
 ORDER BY 1;

\echo '== 8. erasure_requests：埋点库里不存在（擦除登记在业务库 analytics_erasure_outbox，只读账号在业务库无表权限） =='
SELECT 'erasure_requests 不存在于埋点库' AS expected,
       CASE WHEN to_regclass('erasure_requests') IS NULL THEN '不存在' ELSE '存在（核对是否误授权）' END AS actual,
       CASE WHEN to_regclass('erasure_requests') IS NULL THEN true
            ELSE NOT has_table_privilege(:'reader', 'erasure_requests', 'SELECT') END AS ok;

\echo '== 9. events_raw 全部分区都不可读：期望 0 行 =='
SELECT c.relname AS readable_raw_partition
  FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
 WHERE i.inhparent = 'events_raw'::regclass AND has_table_privilege(:'reader', c.oid, 'SELECT');

\echo '== 10. 任何表都没有 INSERT / UPDATE / DELETE / TRUNCATE / REFERENCES / TRIGGER：期望 0 行 =='
SELECT c.relname, p.priv
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
 CROSS JOIN unnest(ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) AS p(priv)
 WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f')
   AND has_table_privilege(:'reader', c.oid, p.priv)
 ORDER BY 1, 2;

\echo '== 11. 授权全集（人工过目，只应出现上面第 6 项的表，且只有 SELECT） =='
SELECT table_name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges
  FROM information_schema.role_table_grants
 WHERE grantee = :'reader'
 GROUP BY table_name ORDER BY 1;

-- 12. 以只读账号登录后手工确认（期望全部失败 / 生效）：
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'SELECT 1 FROM events_raw LIMIT 1'          -- permission denied
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'SELECT 1 FROM pipeline_progress LIMIT 1'   -- permission denied
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'INSERT INTO agg_progress DEFAULT VALUES'  -- read-only transaction / permission denied
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'CREATE TABLE x(i int)'                    -- read-only transaction
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'SHOW statement_timeout'                   -- 30s
--   psql -h petgo-postgres -U <reader> -d <埋点库> -c 'SELECT pg_sleep(31)'                      -- statement timeout
--   psql -h petgo-postgres -U <reader> -d petgo -c 'SELECT 1 FROM users LIMIT 1'                   -- 业务库默认 PUBLIC CONNECT 未收回时能连上，但任何表都 permission denied
--   stag 账号连生产埋点库 / 生产账号连 stag 埋点库                                                 -- permission denied for database
--   同时开 4 个连接                                                                                -- too many connections
