-- Story 6.1: 推送基建 —— 创建 notifications 表（统一推送出口 + 6.6 通知中心读取源）。
-- 复用腾讯 IM 离线推送（底层 APNs/FCM），不引入独立 TPNS。
-- 对外只暴露不可枚举 deep_link_token（绝不顺序 id）；target_ref 仅内部回查目标资源。
-- payload/深链绝不带顺序 id 或健康数据明文。时间戳一律 UTC。

CREATE TABLE notifications (
    id                 BIGSERIAL    PRIMARY KEY,
    recipient_user_id  BIGINT       NOT NULL,
    type               VARCHAR(32)  NOT NULL,            -- VET_REPLY|CONSULT_CLOSED|CONTENT_LIKED|CONTENT_COMMENTED|NEW_CONSULT_REQUEST
    title              VARCHAR(120),
    body               VARCHAR(255),
    deep_link_type     VARCHAR(32),                      -- 深链目标类型（客户端映射 go_router location）
    deep_link_token    VARCHAR(64),                      -- 不可枚举 token（对外定位用，非顺序 id）
    target_ref         VARCHAR(64),                      -- 内部回查目标资源（不外泄）
    is_read            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(), -- UTC
    read_at            TIMESTAMPTZ,
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST'))
);

-- 6.6 通知中心按收件人倒序拉取。
CREATE INDEX idx_notifications_recipient_created ON notifications (recipient_user_id, created_at DESC);
