-- analytics-pipeline Story 1.5：匿名 id ↔ 用户关联表 identity_links（只在埋点库执行）。架构 A7 / D-10 / A8。
-- 只追加：每次登录（带 USER token 且 userId 与 token 相符的 user_identified）一行；同一设备反复登录同一账号也每次一行。
-- 本表与后续 story 都不 UPDATE；唯一的删除来自 Story 1.8 注销擦除（按 user_id）。
-- 不与业务表建外键（FR-11，跨库也建不了）。user_id 只在埋点库内部 / AI 只读账号可见，不对外（A4）。
-- message_id：来源 user_identified 的 messageId。App 重发同一条时 sentAt 不同、校正后的 linked_at 也不同，
--   单靠主键挡不住重复，故加唯一约束兜住幂等（写入用 ON CONFLICT DO NOTHING）。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。

CREATE TABLE identity_links (
    anonymous_id  varchar(64)  NOT NULL,
    -- 内部用户 id，只在埋点库内部用
    user_id       bigint       NOT NULL,
    -- AnalyticsDistinctId.of(user_id)，sha256 hex
    user_key      varchar(64)  NOT NULL,
    -- 关联发生时间（UTC）：client_ts + (received_at − sent_at)，晚于 received_at 取 received_at（与明细层同口径）
    linked_at     timestamptz  NOT NULL,
    -- 来源 user_identified 的 messageId，重试幂等用
    message_id    varchar(64)  NOT NULL,
    CONSTRAINT pk_identity_links PRIMARY KEY (anonymous_id, linked_at),
    CONSTRAINT uq_identity_links_message_id UNIQUE (message_id)
);

-- Story 1.8 注销擦除按用户删
CREATE INDEX ix_identity_links_user_id ON identity_links (user_id);

COMMENT ON TABLE identity_links IS '匿名 id ↔ 用户关联，只追加；每次登录一行（D-10）';

-- 只读角色（AI 查数）只给 SELECT（A8 逐表授权）。角色名由 Flyway 占位符 readerRole 传入；
-- 本地 / CI 没有这个角色 → RAISE NOTICE 跳过（Story 1.1 定的条件授权写法）。
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${readerRole}') THEN
    EXECUTE format('GRANT SELECT ON %I TO %I', 'identity_links', '${readerRole}');
  ELSE
    RAISE NOTICE 'role ${readerRole} 不存在，跳过授权（本地 / CI 预期如此）';
  END IF;
END $$;
