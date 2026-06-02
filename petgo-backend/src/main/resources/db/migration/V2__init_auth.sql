-- Story 1.3：auth 模块地基。创建 users 表（本平台用户主体）+ refresh_tokens 表（refresh 轮换持久化）。
-- Flyway 序号：接 V1 基线之后单调分配（决策 E2）。schema 归 Flyway，ddl-auto=validate。

CREATE TABLE users (
    id                   BIGSERIAL    PRIMARY KEY,
    google_sub           VARCHAR(255) NOT NULL,
    email                VARCHAR(320),
    display_name         VARCHAR(255),
    avatar_url           VARCHAR(1024),
    nickname             VARCHAR(20),
    pet_status           VARCHAR(8),                       -- A|B|C（Story 1.6 写）
    onboarding_completed BOOLEAN      NOT NULL DEFAULT FALSE,
    role                 VARCHAR(16)  NOT NULL DEFAULT 'USER', -- USER|VET|ADMIN（本 Story 仅 USER）
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),  -- UTC
    deleted_at           TIMESTAMPTZ,                          -- 备注销匿名化（Epic 7）
    CONSTRAINT uq_users_google_sub UNIQUE (google_sub),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'VET', 'ADMIN')),
    CONSTRAINT ck_users_pet_status CHECK (pet_status IS NULL OR pet_status IN ('A', 'B', 'C'))
);

-- refresh 轮换：存不可逆 hash（绝不存明文），轮换=旧句柄置 revoked。
CREATE TABLE refresh_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
