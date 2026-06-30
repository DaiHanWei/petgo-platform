-- Story 1.3（管理后台 Epic 1）：操作审计日志 admin_audit_logs（哈希链 append-only，AM-C1 / A6）。
-- 决策 E2：Flyway 从实测最大号 V31 顺延为 V32（勿照搬 architecture 示例号）。
-- 不可篡改三道防线：① 应用窄仓库接口不暴露 delete/改写 ② 行间 SHA-256 哈希链（prev_hash→row_hash，篡改可检）
--                  ③ 本迁移内 BEFORE UPDATE OR DELETE 触发器，DB 层硬拒（即便误删/越权 SQL 亦被阻断）。
-- 护栏：created_at 由应用侧写入（UTC，truncatedTo MICROS 与本列精度对齐，保证回读可复算哈希），不设 DEFAULT；
--      prev_hash/row_hash 为 64 位 16 进制摘要 → VARCHAR(64)（非 CHAR，避开 Hibernate CHAR 映射坑）。
-- 永久保留：无清理任务、无 TTL（AC6）。

CREATE TABLE admin_audit_logs (
    id               BIGSERIAL    PRIMARY KEY,
    actor_account_id BIGINT,                              -- admin_accounts.id；系统发起可为 NULL
    action_type      VARCHAR(64)  NOT NULL,               -- UPPER_SNAKE 过去式（EMERGENCY_LOGIN_SUCCEEDED 等）
    target_type      VARCHAR(64),                         -- 目标资源类型（ADMIN_ACCOUNT / CONTENT_POST ...）
    target_id        VARCHAR(128),                        -- 不可枚举 token 或业务 id 字符串（不外露自增 id）
    summary          VARCHAR(500),                        -- 人类可读摘要；严禁含密码/令牌/签名 URL/健康数据
    created_at       TIMESTAMPTZ  NOT NULL,               -- UTC，应用写入（入哈希）
    prev_hash        VARCHAR(64)  NOT NULL,               -- 前一行 row_hash（首行=创世值 64 个 '0'）
    row_hash         VARCHAR(64)  NOT NULL,               -- 本行 SHA-256
    CONSTRAINT uq_admin_audit_logs_row_hash UNIQUE (row_hash)
);

CREATE INDEX idx_admin_audit_logs_created_at ON admin_audit_logs (created_at DESC);
CREATE INDEX idx_admin_audit_logs_actor      ON admin_audit_logs (actor_account_id);
CREATE INDEX idx_admin_audit_logs_action     ON admin_audit_logs (action_type);

-- append-only DB 强制：任何 UPDATE / DELETE 均抛异常阻断。
CREATE OR REPLACE FUNCTION trg_admin_audit_logs_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit_logs is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER admin_audit_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON admin_audit_logs
    FOR EACH ROW EXECUTE FUNCTION trg_admin_audit_logs_block_mutation();
