-- Story 3.2（V1.1.4 社区管控）：账号级处置的两个通知类型。
-- V1 的封号处置**根本不发站内通知** —— NotificationType 的 19 个值里没有任何封号/警告类，
-- admin 侧的停用逻辑与 notify 也毫无交集。「用户也真的收得到」是本 story 新增的能力。
--
-- ⚠️ 本迁移是 DROP + ADD 全量重建，**必须基于 V97 的完整 19 值追加**。
--   V97 本身就是为了修 V72 的教训才存在的：两条工作线各自 DROP+ADD，后跑的那条列表里
--   不含另一条加的三个值，结果审核线的通知全断。**在这里漏抄任何一个值都会重演那次事故。**
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'VET_REPLY', 'CONSULT_CLOSED', 'CONTENT_LIKED', 'CONTENT_COMMENTED', 'NEW_CONSULT_REQUEST',
    'PET_BIRTHDAY', 'COMPANION_ANNIVERSARY', 'MILESTONE_NODE', 'CONTENT_REMOVED', 'REPORT_REVIEWED',
    'CONTENT_REVIEW_APPROVED', 'CONTENT_REVIEW_REJECTED',
    'NAME_RESET', 'AVATAR_RESET', 'CONTENT_REVIEW_TIMED_OUT',
    'REFUND_REJECTED', 'TICKET_RESOLVED', 'CSAT_SURVEY', 'IDENTITY_REQUIRE_MODIFY',
    -- ↓ V1.1.4 Story 3.2 新增两值（上面 19 个是 V97 的原样全量，一个都没动）
    'ACCOUNT_WARNED', 'ACCOUNT_SUSPENDED'));
