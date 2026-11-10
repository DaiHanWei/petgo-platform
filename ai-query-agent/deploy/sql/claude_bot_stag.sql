-- ============================================================================
-- stag 只读库账号 claude_bot_stag（spec §7、NFR9）
--
-- 由 DBA 在服务器上以超级用户手工执行，云端开发环境未执行：
--   docker exec -it petgo-postgres psql -U postgres -v owner=<petgo_stag 表的属主> -f /path/claude_bot_stag.sql
--   然后在 psql 内执行：\password claude_bot_stag   （密码不写进本文件、不进 shell 历史）
--
-- 要点：
--   - 不继承 ops_readonly（NOINHERIT，且不 GRANT 任何角色）
--   - 只在 petgo_stag 内授 SELECT，并配 ALTER DEFAULT PRIVILEGES 覆盖以后新建的表
--   - statement_timeout 30s、连接上限 3、默认只读事务
-- ============================================================================
\set ON_ERROR_STOP on

-- 必须带 -v owner=...，在建号之前检查，避免半途失败
\if :{?owner}
\else
  \echo '缺少 -v owner=<petgo_stag 表的属主>，未做任何改动'
  \quit
\endif

-- 可重复执行：已存在时只校正属性
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'claude_bot_stag') AS need_create \gset
\if :need_create
  CREATE ROLE claude_bot_stag LOGIN;
\endif
ALTER ROLE claude_bot_stag LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
  CONNECTION LIMIT 3;

-- 注意：以下三项是会话默认值，账号自己可以用 SET 覆盖，不是硬限制。
-- 真正的只读保证来自权限（只授 SELECT）；连接上限 3 由 CONNECTION LIMIT 强制。
-- 30 秒超时被覆盖的风险见 runbook §7。
ALTER ROLE claude_bot_stag SET statement_timeout = '30s';
ALTER ROLE claude_bot_stag SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE claude_bot_stag SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE petgo_stag TO claude_bot_stag;

\connect petgo_stag
GRANT USAGE ON SCHEMA public TO claude_bot_stag;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO claude_bot_stag;
-- 以后由 :owner（Flyway 执行账号）新建的表自动授 SELECT
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO claude_bot_stag;

-- PUBLIC 默认拥有的建表 / 临时表权限：
--   PG15+ 默认已收回 public schema 的 CREATE；PG14 及以下需确认（verify 第 7 项）。
--   TEMP 默认授予 PUBLIC；收回会影响其他账号，属共享库变更，须 Dai 确认后另行执行。

-- 关于 petgo（生产库）：
-- PostgreSQL 默认给 PUBLIC 授予所有库的 CONNECT。claude_bot_stag 在 petgo 内没有任何表权限，
-- 读不到数据；若要连 CONNECT 也拒绝，需要 REVOKE CONNECT ON DATABASE petgo FROM PUBLIC，
-- 这会影响依赖 PUBLIC 的其他账号，属生产变更，须 Dai 确认后另行执行。核验见 verify_claude_bot_stag.sql。
