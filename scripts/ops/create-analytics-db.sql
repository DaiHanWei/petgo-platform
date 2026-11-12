-- =============================================================================
-- 建埋点库 + 写入 / 只读账号（analytics-pipeline Story 1.1，架构 A8）
-- =============================================================================
-- 🔴 运维手动执行，不进任何自动流程：
--    在 petgo-postgres 实例上，以超级用户连 postgres 库执行；生产 / stag 各一次，
--    必须在【首次部署含埋点管道的后端之前】执行（否则后端起来后埋点管道停用 + TECH 告警，业务不受影响）。
--    步骤与 env 配置见 docs/runbooks/runbook-analytics-pipeline.md「部署前先建库」。
--
-- 用法（密码经 psql 变量传入，绝不写进本文件）：
--   psql -v ON_ERROR_STOP=1 -v env=prod -v writer_pw="$W" -v reader_pw="$R" \
--        -U <超级用户> -d postgres -f scripts/ops/create-analytics-db.sql
--   服务器上经容器执行：
--   docker exec -i petgo-postgres psql -v ON_ERROR_STOP=1 -v env=stag -v writer_pw="$W" -v reader_pw="$R" \
--        -U petgo -d postgres < scripts/ops/create-analytics-db.sql
--
-- env=prod → 库 petgo_analytics      / 写入 analytics_writer      / 只读 analytics_reader
-- env=stag → 库 petgo_analytics_stag / 写入 analytics_writer_stag / 只读 analytics_reader_stag
--   （stag 用独立角色：各自 CONNECTION LIMIT 互不挤占，生产 AI 账号也连不进 stag 库，反之亦然）
--
-- 做了什么：
--   - 写入账号：LOGIN、CONNECTION LIMIT 5、statement_timeout=120s；是埋点库 OWNER（跑迁移、建分区；
--     PG15+ public schema 归库属主）。汇总 / 擦除任务在事务内 SET LOCAL 放宽超时。
--   - 只读账号：LOGIN、NOINHERIT、CONNECTION LIMIT 3、只有 CONNECT 权限；会话默认 statement_timeout=30s、
--     idle_in_transaction_session_timeout=60s、default_transaction_read_only=on（Story 4.8）。
--     核验：ai-query-agent/deploy/sql/verify_analytics_reader.sql。
--     🔴 不写 ALTER DEFAULT PRIVILEGES：表权限由各 story 的迁移逐表 GRANT（白名单；events_raw 等不授权）。
--   - REVOKE ALL ON DATABASE ... FROM PUBLIC：同实例上的业务账号、ops_readonly、claude_bot 等角色
--     默认能 CONNECT 任何库，不收回它们都能连进埋点库；也挡住 stag 角色连生产埋点库。
--
-- 可重复执行：CREATE ROLE / CREATE DATABASE 不支持 IF NOT EXISTS，且 psql 变量不在 DO $$ $$ 里展开，
-- 故用 SELECT format(...) WHERE NOT EXISTS (...) \gexec 生成；已存在的角色用 ALTER ROLE 重设密码与限额，
-- 重复执行后状态一致（改密码也用它：带新密码重跑即可，记得同步 ~/.env.petgo*）。
-- ⚠️ 若实例开了 log_statement=all / ddl，ALTER ROLE ... PASSWORD 会进 postgres 日志；执行前确认未开。
-- =============================================================================

\set ON_ERROR_STOP on

-- ---- 参数校验 ----
\if :{?env}
\else
  \echo '缺少参数：-v env=prod 或 -v env=stag'
  SELECT 'missing psql variable: env'::int;
\endif
\if :{?writer_pw}
\else
  \echo '缺少参数：-v writer_pw=...'
  SELECT 'missing psql variable: writer_pw'::int;
\endif
\if :{?reader_pw}
\else
  \echo '缺少参数：-v reader_pw=...'
  SELECT 'missing psql variable: reader_pw'::int;
\endif

SELECT (:'env' IN ('prod', 'stag')) AS env_ok \gset
\if :env_ok
\else
  \echo 'env 只能是 prod 或 stag'
  SELECT 'invalid psql variable: env'::int;
\endif

SELECT CASE :'env' WHEN 'stag' THEN '_stag' ELSE '' END AS sfx \gset
\set db_name petgo_analytics :sfx
\set writer_role analytics_writer :sfx
\set reader_role analytics_reader :sfx
\echo '目标：库' :db_name '/ 写入' :writer_role '/ 只读' :reader_role

-- ---- 写入账号 ----
SELECT format('CREATE ROLE %I LOGIN', :'writer_role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'writer_role') \gexec
ALTER ROLE :"writer_role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    CONNECTION LIMIT 5 PASSWORD :'writer_pw';
ALTER ROLE :"writer_role" SET statement_timeout = '120s';

-- ---- 只读账号（AI 查数，FR-27；Story 4.8 补齐会话属性） ----
SELECT format('CREATE ROLE %I LOGIN', :'reader_role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'reader_role') \gexec
ALTER ROLE :"reader_role" WITH LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    CONNECTION LIMIT 3 PASSWORD :'reader_pw';
-- 以下三项是会话默认值（与业务库 claude_bot_stag 同一套），账号自己可用 SET 覆盖，不是硬限制：
-- 真正的只读保证来自权限（迁移只逐表授 SELECT）；连接上限 3 由 CONNECTION LIMIT 强制。
-- 30 秒超时可被会话 SET 覆盖的残留风险见 docs/runbooks/runbook-ai-query-agent.md §7。
ALTER ROLE :"reader_role" SET statement_timeout = '30s';
ALTER ROLE :"reader_role" SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE :"reader_role" SET default_transaction_read_only = on;

-- ---- 埋点库（写入账号为属主） ----
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'db_name', :'writer_role', 'UTF8')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec
ALTER DATABASE :"db_name" OWNER TO :"writer_role";

-- ---- 库级权限：收回 PUBLIC，只给只读账号 CONNECT（表权限由迁移逐表授予） ----
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"reader_role";

-- ---- 回显核对 ----
SELECT rolname, rolcanlogin, rolconnlimit, rolconfig
  FROM pg_roles WHERE rolname IN (:'writer_role', :'reader_role') ORDER BY rolname;
SELECT datname, pg_get_userbyid(datdba) AS owner, datacl
  FROM pg_database WHERE datname = :'db_name';
