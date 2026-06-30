-- Story 1.1（管理后台 Epic 1）：后台账号表 admin_accounts。
-- AG-1 / 决策 F-A：后台账号与 App users / vet_accounts 完全隔离（PRD 术语表）。
-- 旧 users.role=ADMIN + V7 password_hash 列保留停用，后台登录改走本表（决策 E2，Flyway 从 V30 顺延）。
-- 护栏：password_hash 仅 BCrypt（明文绝不落库/日志，env 注入）；枚举 varchar + UPPER；时间戳 UTC。

CREATE TABLE admin_accounts (
    id            BIGSERIAL    PRIMARY KEY,
    lark_email    VARCHAR(255) NOT NULL,                  -- 身份标识 + Lark 白名单键（1.2）
    display_name  VARCHAR(100) NOT NULL,
    account_type  VARCHAR(20)  NOT NULL,                  -- SUPER_ADMIN | STAFF
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    password_hash VARCHAR(255),                           -- 仅超管紧急入口；STAFF(Lark) 为 NULL
    created_by    BIGINT,                                 -- 创建者后台账号 id；首个超管为 NULL
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),    -- UTC
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),    -- UTC
    CONSTRAINT uq_admin_accounts_lark_email UNIQUE (lark_email),
    CONSTRAINT ck_admin_accounts_type   CHECK (account_type IN ('SUPER_ADMIN', 'STAFF')),
    CONSTRAINT ck_admin_accounts_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
