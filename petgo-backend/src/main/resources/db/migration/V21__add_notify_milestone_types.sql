-- Story 6.1（PRD V1.0.0 修订 Fx · 2026-06-08，决策 F2/F5）：
-- notifications.type 枚举扩 3 类定时系统推送目标
--   PET_BIRTHDAY / COMPANION_ANNIVERSARY / MILESTONE_NODE
-- 供 6.7 定时类系统推送写入。type 落库为 VARCHAR，仅 CHECK 约束需同步。
-- V19 已应用、不可修改（Flyway 纪律）→ 新迁移 drop & recreate 约束。

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE'));
