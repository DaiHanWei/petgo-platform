-- V1.3.4 follow · Story 3.1 —— 通知带触发人、点赞 / 场所评论「未读即合并」（AD-3 / AD-9，FR-141）。
--
-- · notifications 加四列，存量行不回填（存量无触发人，按 PRD 原样返回旧文案）：
--     aggregate_key —— 合并键（LIKE:POST:{postId} / COMMENT:PLACE:{placeId}）；未读期间同键只留一行；
--     comment_id    —— 仅 CONTENT_COMMENTED，指向 comments.id（列表按它现查评论原文，删了就不残留）；
--     variant       —— REPLY = 回复了你的评论，其余 NULL；不加 CHECK（取值由 Java 常量收口，
--                      避免再多一个要全量重建的共享约束）；
--     updated_at    —— 合并时刷新。
-- · notification_actors：触发人集合，只存 id 与时间，不存昵称（读取时现查，改名即时生效）。
-- · 部分唯一索引 uq_notifications_unread_aggregate 兜住并发：两个人同时赞同一帖，仍只有一条未读；
--   标记已读后该行退出索引，再来的赞新起一行。

ALTER TABLE notifications
    ADD COLUMN aggregate_key VARCHAR(64) NULL,
    ADD COLUMN comment_id    BIGINT      NULL,
    ADD COLUMN variant       VARCHAR(16) NULL,
    ADD COLUMN updated_at    TIMESTAMPTZ NULL;

CREATE TABLE notification_actors (
    notification_id BIGINT      NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    actor_user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    acted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, actor_user_id)
);

-- 用户行删除时 FK 级联要用；Story 3.2 的 24 小时去重按 actor 查。
CREATE INDEX ix_notification_actors_actor ON notification_actors (actor_user_id);

CREATE UNIQUE INDEX uq_notifications_unread_aggregate
    ON notifications (recipient_user_id, aggregate_key)
    WHERE is_read = false AND aggregate_key IS NOT NULL;

COMMENT ON COLUMN notifications.aggregate_key IS
    '合并键（V1.3.4 AD-9）：LIKE:POST:{postId} / COMMENT:PLACE:{placeId}；未读即合并，由部分唯一索引兜底并发。';
COMMENT ON COLUMN notifications.comment_id IS
    '仅 CONTENT_COMMENTED：指向 comments.id，列表现查评论原文（AD-3）；场所评论不填。';
COMMENT ON COLUMN notifications.variant IS
    'REPLY = 回复了你的评论，其余 NULL（AD-3）。无 CHECK，取值由 Notification.VARIANT_* 常量收口。';
COMMENT ON COLUMN notifications.updated_at IS '合并刷新时刻（UTC）；新建时等于 created_at。';
COMMENT ON TABLE notification_actors IS
    '通知触发人集合（V1.3.4 AD-3）：只存 id 与时间，不存昵称，读取时现查。';
