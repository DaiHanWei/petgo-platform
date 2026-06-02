-- Story 3.7：内容举报工单表（moderation 模块）。Flyway 序号接 V10 之后单调分配（决策 E2）。
-- V1 仅内容举报（无评论/用户举报）；无自动下架（status 由 ADMIN 人工流转）。
-- 防重复：同 reporter 对同 post 唯一（uq）；重复举报由 service 幂等吞掉。

CREATE TABLE content_reports (
    id          BIGSERIAL    PRIMARY KEY,
    post_id     BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason_type VARCHAR(16)  NOT NULL,                 -- ILLEGAL | MISINFO | INAPPROPRIATE | HARASSMENT | OTHER
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | RESOLVED | DISMISSED
    handled_by  BIGINT,                                -- 处理的 ADMIN id（可空）
    handled_at  TIMESTAMPTZ,                           -- 处理时刻（可空）
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),   -- UTC
    CONSTRAINT ck_content_reports_reason CHECK (reason_type IN ('ILLEGAL','MISINFO','INAPPROPRIATE','HARASSMENT','OTHER')),
    CONSTRAINT ck_content_reports_status CHECK (status IN ('PENDING','RESOLVED','DISMISSED')),
    CONSTRAINT uq_content_reports_reporter_post UNIQUE (post_id, reporter_id),
    CONSTRAINT fk_content_reports_post FOREIGN KEY (post_id) REFERENCES content_posts (id),
    CONSTRAINT fk_content_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id)
);

CREATE INDEX idx_content_reports_status ON content_reports (status, created_at DESC);
CREATE INDEX idx_content_reports_post   ON content_reports (post_id);
