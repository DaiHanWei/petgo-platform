-- ============================================================================
-- claude_bot_stag 核验（Story 1.2 AC3，L2，在服务器手工执行）
--   docker exec -it petgo-postgres psql -U postgres -f /path/verify_claude_bot_stag.sql
-- 每一项都应符合注释里的期望值。
-- ============================================================================

-- 1. 角色属性：rolinherit=f，rolconnlimit=3，不是超级用户
SELECT rolname, rolinherit, rolconnlimit, rolsuper, rolcreatedb, rolcreaterole
FROM pg_roles WHERE rolname = 'claude_bot_stag';

-- 2. 角色级设置：含 statement_timeout=30s、default_transaction_read_only=on
SELECT setrole::regrole, setdatabase, setconfig
FROM pg_db_role_setting WHERE setrole = 'claude_bot_stag'::regrole;

-- 3. 不是任何角色（含 ops_readonly）的成员：期望 0 行
SELECT r.rolname AS member_of
FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
WHERE m.member = 'claude_bot_stag'::regrole;

-- 4. 在 petgo（生产库）里没有任何表权限：期望 0 行
\connect petgo
SELECT table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee IN ('claude_bot_stag', 'PUBLIC') AND table_schema = 'public';

-- 5. 在 petgo_stag 里只有 SELECT：期望只出现 SELECT
\connect petgo_stag
SELECT DISTINCT privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'claude_bot_stag';

-- 7. public schema 的 CREATE 不对 PUBLIC 开放：期望 f
SELECT has_schema_privilege('claude_bot_stag', 'public', 'CREATE') AS can_create_in_public;

-- 6. 以 claude_bot_stag 登录后再手工确认（期望全部失败）：
--   psql -h petgo-postgres -U claude_bot_stag -d petgo -c 'SELECT 1 FROM users LIMIT 1'        -- permission denied
--   psql -h petgo-postgres -U claude_bot_stag -d petgo_stag -c 'CREATE TABLE x(i int)'          -- read-only transaction
--   psql -h petgo-postgres -U claude_bot_stag -d petgo_stag -c 'SELECT pg_sleep(31)'            -- statement timeout
--   同时开 4 个连接                                                                              -- too many connections
