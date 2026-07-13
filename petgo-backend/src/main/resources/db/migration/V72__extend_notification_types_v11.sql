-- Story 4.4（V1.1 Epic 4）：客服判定 —— 驳回退款需求发「退款申请未通过」REFUND_REJECTED 通知。
-- 一次性扩全 4 值（extend_notification_types_v11），避免 4-7/9-x 二次迁移：
--   REFUND_REJECTED（本 story 发）、TICKET_RESOLVED / CSAT_SURVEY（4-7 结案/问卷占位）、
--   IDENTITY_REQUIRE_MODIFY（Epic 9 身份核验占位）。
-- 实测当前 max = V71（4-3 init_refund_requests）。扩 notifications.type CHECK 容纳新值（沿用 V40/V43 DROP+ADD 范式）。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY'));
