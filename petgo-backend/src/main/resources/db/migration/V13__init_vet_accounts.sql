-- Story 5.1: 兽医账号体系 —— 创建 vet_accounts 表（Epic 5 在线兽医咨询的账号根）。
-- 兽医走账密 BCrypt 登录，签发 role=VET JWT，与用户侧 Google 流程隔离。
-- 护栏：password_hash 仅存 BCrypt 哈希，明文绝不落库/落日志；无「忘记密码」相关列（重置走 Admin 直改 hash）。
-- status 的 BANNED 语义（5.7 封禁）本故事先落「BANNED 不可登录」。时间戳一律 UTC。

CREATE TABLE vet_accounts (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,               -- 登录账号（唯一）
    password_hash VARCHAR(255) NOT NULL,               -- BCrypt 哈希；明文绝不落库
    display_name  VARCHAR(64)  NOT NULL,               -- 兽医昵称（对话/历史展示）
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | BANNED（5.7 封禁预留）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    CONSTRAINT uq_vet_accounts_username UNIQUE (username),
    CONSTRAINT ck_vet_accounts_status CHECK (status IN ('ACTIVE', 'BANNED'))
);

-- refresh_tokens 多主体区分（决策：兽医复用 Epic 1 refresh 体系，但 user_id 命名空间与 users 表独立，
-- 必须用 subject_type 区分，防 vet 的 refresh 在 /auth/refresh 被当作同 id 的 user 误签 token）。
ALTER TABLE refresh_tokens
    ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'USER';
