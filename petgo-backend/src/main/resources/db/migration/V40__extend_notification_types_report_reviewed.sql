-- Story 4.1（管理后台 Epic 4）：举报人模糊闭环通知新增 REPORT_REVIEWED 通知类型。
-- 实测最大号+1 = V38（V37=扩 consult interrupt_reason）。扩 notifications.type CHECK 容纳新值，
-- 并补回 CONTENT_REMOVED（既有 NotificationType 枚举有此值但 V21 的 CHECK 漏列——作者下架通知此前会被 CHECK 拒，本迁移一并修复）。
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED'));
