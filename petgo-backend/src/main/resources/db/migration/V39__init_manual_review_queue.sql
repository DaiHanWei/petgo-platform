-- Story 4.3（管理后台 Epic 4）：人工审核队列 manual_review_queue（AB-3C，预建未激活）。
-- 实测最大号+1 = V39（V38=扩 notifications.type）。激活后未过自动审核内容入此队列挂起，
-- 运营通过/拒绝或超 3 天自动丢弃。status varchar UPPER_SNAKE；时间 timestamptz UTC；不外露自增 id（内部工具）。
CREATE TABLE manual_review_queue (
    id           BIGSERIAL    PRIMARY KEY,
    content_id   BIGINT       NOT NULL,
    submitted_at TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_by   BIGINT,                                 -- admin_accounts.id（处置人，PENDING 时空）
    decided_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_manual_review_queue_status CHECK
        (status IN ('PENDING', 'APPROVED', 'REJECTED', 'TIMED_OUT'))
);

CREATE INDEX idx_manual_review_queue_status    ON manual_review_queue (status);
CREATE INDEX idx_manual_review_queue_submitted ON manual_review_queue (submitted_at);
CREATE INDEX idx_manual_review_queue_content   ON manual_review_queue (content_id);
