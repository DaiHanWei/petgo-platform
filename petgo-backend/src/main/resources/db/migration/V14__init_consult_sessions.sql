-- Story 5.3: 兽医咨询会话状态机入口 —— 创建 consult_sessions 表。
-- 状态机：WAITING →(接单 5.5) IN_PROGRESS →(结束 5.6) PENDING_CLOSE →(评分/超时 5.6) CLOSED；
--         旁路 WAITING →(取消，本故事) CANCELLED、IN_PROGRESS →(封禁 5.7) INTERRUPTED。
-- 本故事用到 WAITING / CANCELLED；其余状态值为后续 Story 预留（CHECK 一次性放全集，避免反复改约束）。
-- DB 为权威；待接单队列态在 Redis（只存 sessionId+排序键，收窄）。时间戳一律 UTC。
-- AI 升级上下文字段（评级/描述/图片引用）由 Story 5.4 增列，本表预留可扩展（source=AI_UPGRADE）。

CREATE TABLE consult_sessions (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    vet_id             BIGINT,                              -- 接单后填（Story 5.5）
    status             VARCHAR(16)  NOT NULL,               -- WAITING|IN_PROGRESS|PENDING_CLOSE|CLOSED|INTERRUPTED|CANCELLED
    source             VARCHAR(16)  NOT NULL DEFAULT 'DIRECT', -- DIRECT | AI_UPGRADE（5.4）
    waiting_started_at TIMESTAMPTZ,                         -- 超时(1min)计时基准；继续等待时重置
    im_conversation_id VARCHAR(128),                        -- 腾讯 IM 会话标识（Story 5.5）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    CONSTRAINT ck_consult_sessions_status CHECK (
        status IN ('WAITING', 'IN_PROGRESS', 'PENDING_CLOSE', 'CLOSED', 'INTERRUPTED', 'CANCELLED')),
    CONSTRAINT ck_consult_sessions_source CHECK (source IN ('DIRECT', 'AI_UPGRADE'))
);

CREATE INDEX idx_consult_sessions_status ON consult_sessions (status);
CREATE INDEX idx_consult_sessions_user_id ON consult_sessions (user_id);
-- 「同时仅 1 个进行中」约束的高频查询：某用户的占用态（WAITING/IN_PROGRESS/PENDING_CLOSE）。
CREATE INDEX idx_consult_sessions_user_status ON consult_sessions (user_id, status);
